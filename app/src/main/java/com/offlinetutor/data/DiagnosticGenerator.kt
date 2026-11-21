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
    data class Outcome(
        val mcq: DiagMcq?,
        val valid: Boolean,
        val duplicate: Boolean,
        val reason: String?,
        val result: GenResult,
        val verdict: com.offlinetutor.domain.Verdict? = null,
    )

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
        val m = base.mcq ?: return Outcome(null, base.valid, base.duplicate, base.reason, base.result, base.verdict)

        val wrong = m.options.indices.filter { it != m.answer }.map { m.options[it] }
        val correct = m.options[m.answer]
        val tagMeta = meta.copy(difficulty = level, evalRun = meta.evalRun?.let { "$it:tags" })
        val tags = MutableList<com.offlinetutor.domain.OptionTag?>(3) { null }
        var prefix = Prompts.tagPrefix(wrong.first())
        // Measured on-device: the model often stops after the first block. Continue with the tagged blocks
        // plus the next header as the prefix (at most 2 extra calls). A row is "valid" when tagging completed.
        for (call in 0 until 3) {
            val t = ai.engine.generate(
                GenRequest(TaskType.MCQ_TAGS, Prompts.diagnosticTagLines(m.question, correct, wrong, prefix),
                    assistantPrefix = prefix, stopWhen = Prompts::tagLinesDone),
            )
            DiagnosticParser.parseTagLines(t.text).forEachIndexed { i, tag -> if (tags[i] == null && tag != null) tags[i] = tag }
            val complete = tags.all { it != null }
            ai.log(t, TaskType.MCQ_TAGS, tagMeta, complete, null)
            if (complete) break
            // Resume at the first untagged option, replaying the tagged ones before it as the prefix.
            val next = tags.indexOfFirst { it == null }
            prefix = Prompts.tagContinuationPrefix(wrong, tags.take(next).filterNotNull(), next)
        }
        val parsed = DiagnosticParser.fromMcq(m, conceptId, tags)

        return when (parsed) {
            is DiagParse.Ok -> Outcome(parsed.mcq, true, false, null, base.result, base.verdict)
            is DiagParse.NeedsTags -> Outcome(null, false, false, "wrong options not tagged", base.result, base.verdict)
            is DiagParse.Invalid -> Outcome(null, false, false, parsed.reason, base.result, base.verdict)
        }
    }
}
