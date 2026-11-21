package com.offlinetutor.domain

/**
 * 2-fix-3: one failure reason per rejected generation, so yield drops can be explained from the logs.
 * The category is the text before the first ':' ("parse", "truncated", "repetition", "rule", "duplicate", …).
 */
object FailReasons {
    const val DUPLICATE = "duplicate"
    const val TRUNCATED = "truncated: hit maxNewTokens before the top-level object closed"

    /**
     * [invalidReason] = the parser/validator reason (null when the MCQ was valid), [stopReason] = engine stop
     * reason name, [guardReason] = repetition-guard detail. Truncation and the repetition guard explain a parse
     * failure better than the parse error itself, so they win.
     */
    fun forMcq(invalidReason: String?, stopReason: String, guardReason: String?, duplicate: Boolean): String? {
        if (invalidReason == null) return if (duplicate) DUPLICATE else null
        return when {
            stopReason == "MAX_TOKENS" -> TRUNCATED
            stopReason == "REPETITION" -> "repetition: ${guardReason ?: "guard stop"}"
            isParseError(invalidReason) -> "parse: $invalidReason"
            invalidReason.startsWith("rule:") -> invalidReason
            else -> "rule: $invalidReason"
        }
    }

    fun isParseError(reason: String): Boolean =
        reason.startsWith("no JSON") || reason.startsWith("bad JSON") || reason.startsWith("follow-up: no JSON") ||
            reason.startsWith("follow-up: bad JSON")

    fun category(reason: String?): String? = reason?.substringBefore(':')?.trim()
}
