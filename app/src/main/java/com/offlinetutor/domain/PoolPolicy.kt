package com.offlinetutor.domain

/**
 * 3b: ready-pool sizing. Measured cost of one fully tagged LLM question on the edge 40 is ~16–25 s and a student
 * spends ~20–30 s per question, so keeping 3 ready per level near the student's rating lets refill keep up.
 */
object PoolPolicy {
    const val PER_LEVEL = 3
    /** Storage cap per profile (3c): pool rows are small text, this keeps the table bounded. */
    const val PROFILE_CAP = 60

    /** Shadow candidates one level up and one down, within easy..hard. */
    fun neighbours(level: Int): List<Int> = listOf(level - 1, level + 1).filter { it in AdaptiveDifficulty.EASY..AdaptiveDifficulty.HARD }

    /** Share of [servedFrom] values that were pool hits (null when nothing was served). */
    fun hitRate(servedFrom: List<String?>): Double? {
        val served = servedFrom.filterNotNull()
        return if (served.isEmpty()) null else served.count { it == "pool" }.toDouble() / served.size
    }
}

/** 3b metrics for the benchmark screen, computed from served questions (newest first). */
data class ServeStats(
    val served: Int,
    /** Pool hits among the last [window] served questions. */
    val hitRateLast: Double?,
    val window: Int,
    val medianWaitPoolMs: Long?,
    val medianWaitLiveMs: Long?,
    val medianWaitInstantMs: Long?,
    /** Median generation time of LLM questions that were fully tagged (diagnostic). */
    val medianTaggedGenMs: Long?,
) {
    data class Row(val servedFrom: String?, val waitMs: Long?, val genMs: Long?, val source: String, val tagged: Boolean)

    companion object {
        fun median(xs: List<Long>): Long? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        fun from(rows: List<Row>, window: Int = 10): ServeStats {
            val r = rows.filter { it.servedFrom != null }
            fun waits(from: String) = median(r.filter { it.servedFrom == from }.mapNotNull { it.waitMs })
            return ServeStats(
                served = r.size,
                hitRateLast = PoolPolicy.hitRate(r.take(window).map { it.servedFrom }),
                window = window,
                medianWaitPoolMs = waits("pool"),
                medianWaitLiveMs = waits("live"),
                medianWaitInstantMs = waits("instant-template"),
                medianTaggedGenMs = median(r.filter { it.source == "llm" && it.tagged }.mapNotNull { it.genMs?.takeIf { g -> g > 0 } }),
            )
        }
    }
}
