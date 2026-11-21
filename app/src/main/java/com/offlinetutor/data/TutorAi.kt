package com.offlinetutor.data

import com.offlinetutor.domain.DiagMcq
import com.offlinetutor.domain.ExpressionVerifier
import com.offlinetutor.domain.McqParse
import com.offlinetutor.domain.McqParser
import com.offlinetutor.domain.Mcq
import com.offlinetutor.domain.ProblemTemplates
import com.offlinetutor.domain.Prompts
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.TemplateType
import com.offlinetutor.domain.Verdict
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.llm.GenRequest
import com.offlinetutor.llm.GenResult
import com.offlinetutor.llm.LlmEngine
import com.offlinetutor.llm.TaskType

data class LogMeta(
    val evalRun: String? = null,
    val evalSubject: String? = null,
    val evalGrade: Int? = null,
    val difficulty: Int? = null,
)

/** Runs a generation on the engine and logs every run to Room (exported as CSV for the report). */
class TutorAi(val engine: LlmEngine, private val logs: LogDao) {

    suspend fun run(
        req: GenRequest,
        meta: LogMeta = LogMeta(),
        validate: ((GenResult) -> Pair<Boolean?, Boolean?>)? = null,
        onPartial: (String) -> Unit = {},
    ): GenResult {
        val r = engine.generate(req, onPartial)
        val (valid, dup) = validate?.invoke(r) ?: (null to null)
        log(r, req.task, meta, valid, dup)
        return r
    }

    suspend fun log(r: GenResult, task: TaskType, meta: LogMeta, validJson: Boolean?, duplicate: Boolean?, verdict: String? = null) {
        logs.insert(
            GenerationLog(
                task = task.name,
                model = r.model,
                evalRun = meta.evalRun,
                evalSubject = meta.evalSubject,
                evalGrade = meta.evalGrade,
                difficulty = meta.difficulty,
                promptChars = r.promptChars,
                promptTokensEst = r.promptTokensEst,
                promptTokens = r.stats?.promptTokens,
                generatedTokens = r.generatedTokens,
                maxNewTokens = r.maxNewTokens,
                temperature = r.temperature,
                ttftMs = r.ttftMs,
                wallMs = r.wallMs,
                decodeTokPerSec = r.decodeTokPerSec,
                prefillTokPerSec = r.stats?.prefillTokPerSec,
                peakRssKb = r.mem.vmHwmKb,
                rssKb = r.mem.vmRssKb,
                deviceAvailKb = r.mem.deviceAvailKb,
                thermalStatus = r.thermalStatus,
                thermalPolicy = r.thermalPolicy,
                stopReason = r.stopReason.name,
                guardReason = r.guardReason,
                validJson = validJson,
                duplicate = duplicate,
                error = r.error,
                output = r.text.take(4000),
                rawStats = r.rawStats,
                verdict = verdict,
            ),
        )
    }

    suspend fun logLoad(model: String, loadMs: Long, peakKb: Long, rssKb: Long, availKb: Long, error: String?) {
        logs.insert(
            GenerationLog(
                task = "LOAD", model = model, wallMs = loadMs, peakRssKb = peakKb, rssKb = rssKb,
                deviceAvailKb = availKb, stopReason = if (error == null) "OK" else "ERROR", error = error,
            ),
        )
    }
}

/** One MCQ attempt: generate → parse/validate → dedupe against history. Shared by the quiz and the eval set. */
class McqGenerator(private val ai: TutorAi, private val flags: FeatureFlags) {
    data class Outcome(
        val mcq: Mcq?,
        val valid: Boolean,
        val duplicate: Boolean,
        val reason: String?,
        val result: GenResult,
        /** 2b: verifier verdict (null when the verifier is off or the question was invalid). */
        val verdict: Verdict? = null,
    )

    suspend fun attempt(
        grade: Int,
        course: String,
        subtopic: String,
        level: Int,
        recentStems: List<String>,
        weak: List<String>,
        historyNormalized: Collection<String>,
        meta: LogMeta,
        onPartial: (String) -> Unit = {},
        focus: String? = null,
    ): Outcome {
        val prompt = Prompts.mcq(grade, course, subtopic, level, recentStems, weak, focus)
        val m = meta.copy(difficulty = level)
        val r = ai.engine.generate(GenRequest(TaskType.MCQ, prompt, assistantPrefix = Prompts.MCQ_PREFIX), onPartial)
        var parsed: McqParse = McqParser.parse(r.text)

        // The 1B model often closes the object right after option D. If the question and the four
        // options are good, ask only for the answer in a short follow-up instead of discarding them.
        if (parsed is McqParse.Invalid) {
            val stem = McqParser.parseStem(r.text)
            if (stem != null && !QuestionDeduper.isDuplicate(stem.question, historyNormalized)) {
                val f = ai.engine.generate(
                    GenRequest(TaskType.MCQ_ANSWER, Prompts.mcqAnswer(grade, stem.question, stem.options),
                        assistantPrefix = Prompts.ANSWER_PREFIX),
                )
                parsed = McqParser.completeWith(stem, f.text)
                ai.log(f, TaskType.MCQ_ANSWER, m.copy(evalRun = m.evalRun?.let { "$it:answer" }),
                    parsed is McqParse.Ok, null)
            }
        }
        // 2b: symbolic check of the key. VERIFIED keeps it, FIXED moves it to the option that matches the
        // evaluated expression, DISCARD rejects the question (regenerated by the caller).
        var verdict: Verdict? = null
        (parsed as? McqParse.Ok)?.let { okParse ->
            if (flags.isOn(Feature.VERIFIER)) {
                val v = ExpressionVerifier.verify(okParse.mcq.expression, okParse.mcq.options, okParse.mcq.answer)
                verdict = v.verdict
                parsed = when (v.verdict) {
                    Verdict.FIXED -> McqParse.Ok(okParse.mcq.copy(answer = v.answer))
                    Verdict.DISCARD -> McqParse.Invalid("verifier: no single option equals ${okParse.mcq.expression}")
                    else -> okParse
                }
            }
        }

        val ok = parsed as? McqParse.Ok
        val dup = ok != null && QuestionDeduper.isDuplicate(ok.mcq.question, historyNormalized)
        // The main row records the final outcome (after any follow-up) for the eval metrics.
        ai.log(r, TaskType.MCQ, m, ok != null, if (ok != null) dup else null, verdict?.name)

        return when (val p = parsed) {
            is McqParse.Ok -> Outcome(if (dup) null else p.mcq, true, dup, if (dup) "duplicate of an earlier question" else null, r, verdict)
            is McqParse.Invalid -> Outcome(null, false, false, p.reason, r, verdict)
        }
    }
}

/**
 * 2a: Kotlin-owned template problem. Kotlin chooses the numbers, the answer and tagged distractors; the model
 * only writes the story around the given quantities. Its text is used only if it contains every given number
 * and no other number (so it can neither change the problem nor leak the answer); otherwise Kotlin's own text.
 * Works without a model too (Kotlin text).
 */
class TemplateGenerator(private val ai: TutorAi) {
    data class Result(val mcq: DiagMcq, val llmText: Boolean)

    suspend fun build(
        grade: Int,
        type: TemplateType,
        level: Int,
        concept: String,
        historyNormalized: Collection<String>,
        meta: LogMeta,
        onPartial: (String) -> Unit = {},
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Result? {
        repeat(3) {
            val p = ProblemTemplates.generate(type, level, random)
            val text = try {
                val r = ai.engine.generate(
                    GenRequest(TaskType.TEMPLATE_TEXT, Prompts.templateText(grade, p), stopWhen = { it.trimEnd().endsWith("?") }),
                    onPartial,
                )
                val t = Prompts.cleanTemplateText(r.text, p.ask)
                val ok = t != null && ProblemTemplates.textKeepsNumbers(t, p.givens) && ProblemTemplates.noExtraNumbers(t, p.givens)
                ai.log(r, TaskType.TEMPLATE_TEXT, meta.copy(difficulty = level), ok, null, Verdict.TEMPLATE.name)
                if (ok) t else null
            } catch (_: ModelMissingException) {
                null // no model installed: Kotlin's own wording still gives a correct question
            }
            val question = text ?: p.defaultText
            if (!QuestionDeduper.isDuplicate(question, historyNormalized)) return Result(p.toDiag(concept, question), text != null)
        }
        return null
    }
}
