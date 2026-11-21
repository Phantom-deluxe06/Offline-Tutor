package com.offlinetutor.domain

/**
 * 3c: the Night Shift's work list, built once per night and stored as strings so a WorkManager run that ends
 * (time budget, device no longer idle/charging) can resume at the same task on its next run.
 */
sealed interface NightTask {
    /** Fill tomorrow's pool for a weak concept / review target. */
    data class Pool(val profileId: Long, val course: String, val concept: String, val level: Int) : NightTask
    /** Pre-write the lesson of a pending micro-fix step. */
    data class Fix(val profileId: Long, val stepId: Long) : NightTask
    /** Report evaluation batch: one fully tagged LLM MCQ. */
    data class EvalMcq(val index: Int) : NightTask
    /** Report evaluation batch: one template problem (LLM story + Kotlin answer). */
    data class EvalTemplate(val index: Int) : NightTask

    fun encode(): String = when (this) {
        is Pool -> listOf("pool", profileId, course.replace("|", "/"), concept.replace("|", "/"), level).joinToString("|")
        is Fix -> "fix|$profileId|$stepId"
        is EvalMcq -> "mcq|$index"
        is EvalTemplate -> "tpl|$index"
    }

    companion object {
        fun decode(s: String): NightTask? {
            val p = s.split("|")
            return when (p.firstOrNull()) {
                "pool" -> if (p.size == 5) Pool(p[1].toLong(), p[2], p[3], p[4].toInt()) else null
                "fix" -> if (p.size == 3) Fix(p[1].toLong(), p[2].toLong()) else null
                "mcq" -> p.getOrNull(1)?.toIntOrNull()?.let { EvalMcq(it) }
                "tpl" -> p.getOrNull(1)?.toIntOrNull()?.let { EvalTemplate(it) }
                else -> null
            }
        }
    }
}

object NightPlan {
    /** Default report batch sizes from the spec: 50 LLM MCQs + 30 template problems. */
    const val EVAL_MCQS = 50
    const val EVAL_TEMPLATES = 30
    /** Weak concepts filled per profile per night. */
    const val WEAK_PER_PROFILE = 4

    data class ProfileWork(
        val profileId: Long,
        /** (course, concept, level), weakest first. */
        val weak: List<Triple<String, String, Int>>,
        /** REVIEW-step targets (course, concept, level). */
        val reviews: List<Triple<String, String, Int>>,
        /** MICRO_FIX steps without a lesson yet. */
        val pendingFixes: List<Long>,
    )

    /** Student work first (pool for weak concepts and reviews, fix lessons), then the evaluation batch. */
    fun build(work: List<ProfileWork>, evalMcqs: Int = EVAL_MCQS, evalTemplates: Int = EVAL_TEMPLATES): List<NightTask> = buildList {
        for (w in work) {
            val targets = (w.weak.take(WEAK_PER_PROFILE) + w.reviews).distinctBy { it.second to it.third }
            targets.forEach { (course, concept, level) -> add(NightTask.Pool(w.profileId, course, concept, level)) }
            w.pendingFixes.forEach { add(NightTask.Fix(w.profileId, it)) }
        }
        repeat(evalMcqs) { add(NightTask.EvalMcq(it)) }
        repeat(evalTemplates) { add(NightTask.EvalTemplate(it)) }
    }

    /** Eval MCQ i → (subject index, grade index, level): every subject x grade, levels rotated so each is covered. */
    fun mcqSlot(i: Int, subjects: Int, grades: Int): Triple<Int, Int, Int> {
        val combos = subjects * grades
        val combo = i % combos
        val level = ((i / combos) + combo) % 3 + 1
        return Triple(combo / grades, combo % grades, level)
    }

    /** Eval template j → (type index, level): types cycled, then levels. */
    fun templateSlot(j: Int, types: Int): Pair<Int, Int> = (j % types) to ((j / types) % 3 + 1)
}
