package com.offlinetutor.domain

import org.json.JSONObject

/** Fixed misconception categories (assets/misconceptions.json). Every tagged wrong option maps to one. */
enum class Misconception {
    SIGN_ERROR, UNIT_CONVERSION, FORMULA_SWAP, OPERATION_SWAP,
    CONCEPT_CONFUSION, PARTIAL_STEP, MISREAD_QUESTION, OVERGENERALISATION;

    companion object {
        /** Lenient lookup: "formula swap", "Formula_Swap" and "FORMULA-SWAP" all map to FORMULA_SWAP. */
        fun of(code: String?): Misconception? {
            if (code.isNullOrBlank()) return null
            val key = code.trim().uppercase().replace(Regex("[^A-Z]+"), "_").trim('_')
            return entries.firstOrNull { it.name == key }
        }
    }
}

data class MisconceptionInfo(val code: Misconception, val description: String, val example: String)

object MisconceptionTaxonomy {
    /** Parses assets/misconceptions.json; unknown codes in the file are ignored. */
    fun parse(json: String): List<MisconceptionInfo> {
        val arr = JSONObject(json).getJSONArray("categories")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            Misconception.of(o.optString("code"))?.let { MisconceptionInfo(it, o.optString("description"), o.optString("example")) }
        }
    }

    /** One line per code, used inside prompts (kept short for the 1024-token context). */
    fun promptList(items: List<MisconceptionInfo>): String =
        items.joinToString("\n") { "${it.code.name}: ${it.description}" }
}

/** Stable concept ids derived from roadmap step titles ("Ratio and Proportion" → "ratio-and-proportion"). */
object Concepts {
    fun slug(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trimEnd('-')
            .ifEmpty { "topic" }
}

/** 1c: what a (correct, confident) pair says about the student. */
enum class AttemptClass { MASTERY, FRAGILE, DEEP_MISCONCEPTION, GAP, UNRATED }

object Confidence {
    fun classify(correct: Boolean, confident: Boolean?): AttemptClass = when {
        confident == null -> AttemptClass.UNRATED
        correct && confident -> AttemptClass.MASTERY
        correct && !confident -> AttemptClass.FRAGILE
        !correct && confident -> AttemptClass.DEEP_MISCONCEPTION
        else -> AttemptClass.GAP
    }
}
