package com.offlinetutor.data

import com.offlinetutor.domain.DiagMcq
import com.offlinetutor.domain.DiagParse
import com.offlinetutor.domain.DiagnosticParser
import com.offlinetutor.domain.Prompts
import com.offlinetutor.llm.GenRequest
import com.offlinetutor.llm.GenResult
import com.offlinetutor.llm.TaskType

/**
 * 1b: one diagnostic MCQ attempt, in two steps (measured: the 1B model could not write the nested
 * diagnostic JSON in one shot):
 *  1. the plain MCQ through [McqGenerator] (validate → answer follow-up → dedupe), logged as MCQ;
 *  2. a short line-format call that tags each wrong option with a misconception code + why,
 *     logged as MCQ_TAGS under "<run>:tags".
 * Valid only when every wrong option gets a known code and a reason.
 */
class DiagnosticGenerator(private val ai: TutorAi, private val mcq: McqGenerator) {
    data class Outcome(val mcq: DiagMcq?, val valid: Boolean, val duplicate: Boolean, val reason: String?, val result: GenResult)

    suspend fun attempt(
        grade: Int,
        course: String,
        @Suppress("UNUSED_PARAMETER") conceptTitle: String,
        conceptId: String,
        subtopic: String,
        level: Int,
        recentStems: List<String>,
        weak: List<String>,
        historyNormalized: Collection<String>,
        meta: LogMeta,
        focus: String? = null,
        onPartial: (String) -> Unit = {},
    ): Outcome {
        val base = mcq.attempt(grade, course, subtopic, level, recentStems, weak, historyNormalized, meta, onPartial, focus)
        val m = base.mcq ?: return Outcome(null, base.valid, base.duplicate, base.reason, base.result)

        val wrong = m.options.indices.filter { it != m.answer }.map { m.options[it] }
        val t = ai.engine.generate(
            GenRequest(TaskType.MCQ_TAGS, Prompts.diagnosticTagLines(m.question, m.options[m.answer], wrong),
                assistantPrefix = Prompts.tagPrefix(wrong.first()), stopWhen = Prompts::tagLinesDone),
        )
        val parsed = DiagnosticParser.fromMcq(m, conceptId, DiagnosticParser.parseTagLines(t.text))
        ai.log(t, TaskType.MCQ_TAGS, meta.copy(difficulty = level, evalRun = meta.evalRun?.let { "$it:tags" }), parsed is DiagParse.Ok, null)

        return when (parsed) {
            is DiagParse.Ok -> Outcome(parsed.mcq, true, false, null, base.result)
            is DiagParse.NeedsTags -> Outcome(null, false, false, "wrong options not tagged", base.result)
            is DiagParse.Invalid -> Outcome(null, false, false, parsed.reason, base.result)
        }
    }
}
