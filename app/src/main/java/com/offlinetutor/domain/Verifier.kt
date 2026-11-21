package com.offlinetutor.domain

import net.objecthunter.exp4j.ExpressionBuilder
import kotlin.math.abs
import kotlin.math.max

/** 2b: outcome of checking the arithmetic of a question's answer key. */
enum class Verdict {
    /** The marked answer equals the evaluated expression. */
    VERIFIED,
    /** A different option matched, so the key was moved to it. */
    FIXED,
    /** No option matches the expression (or several do): regenerate. */
    DISCARD,
    /** No expression, or options are not numbers: nothing to check. */
    UNVERIFIABLE,
    /** 2a: Kotlin computed the answer itself (correct by construction). */
    TEMPLATE,
}

data class VerifyResult(val verdict: Verdict, val value: Double?, val answer: Int)

/**
 * Evaluates the model's `"expression"` with exp4j and compares it to every option's number.
 * This checks the arithmetic of the key only, not whether the question is pedagogically right.
 */
object ExpressionVerifier {
    private val leading = Regex("^\\s*(?:rs\\.?|₹|inr)?\\s*(-?\\d[\\d,]*(?:\\.\\d+)?)(?:\\s*/\\s*(\\d+))?", RegexOption.IGNORE_CASE)

    /** Turns "120 ÷ 1.5", "12 x 7", "15% of 400" style text into something exp4j can parse. */
    fun normalize(expr: String): String? {
        var e = expr.trim().trimEnd('=', ' ').lowercase()
        if (e.isEmpty()) return null
        e = e.replace("×", "*").replace("÷", "/").replace("−", "-").replace("–", "-")
            .replace(Regex("(?<=\\d),(?=\\d{3})"), "")              // 1,200 → 1200
            .replace(Regex("(?<=[\\d)])\\s*x\\s*(?=[\\d(])"), "*")   // 12 x 7 → 12*7
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of"), "($1/100)*")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%"), "($1/100)")
            .replace(Regex("\\bof\\b"), "*")
        // Anything left besides numbers, operators and sqrt means the model wrote words or units.
        val stripped = e.replace("sqrt", "")
        if (stripped.any { it.isLetter() }) return null
        return e
    }

    fun evaluate(expr: String?): Double? {
        val e = expr?.let { normalize(it) } ?: return null
        return try {
            val v = ExpressionBuilder(e).build().evaluate()
            if (v.isNaN() || v.isInfinite()) null else v
        } catch (_: Exception) {
            null
        }
    }

    /** Leading number of an option ("84 loaves" → 84, "Rs 1,200" → 1200, "3/4" → 0.75). */
    fun optionValue(option: String): Double? {
        val m = leading.find(option) ?: return null
        val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val den = m.groupValues[2].toDoubleOrNull()
        return if (den != null && den != 0.0) num / den else num
    }

    /** Tolerance allows for options rounded to whole numbers or 2 decimals. */
    fun close(a: Double, b: Double): Boolean = abs(a - b) <= max(0.01, 0.005 * abs(b))

    fun verify(expression: String?, options: List<String>, marked: Int): VerifyResult {
        val v = evaluate(expression) ?: return VerifyResult(Verdict.UNVERIFIABLE, null, marked)
        val values = options.map { optionValue(it) }
        if (values.any { it == null }) return VerifyResult(Verdict.UNVERIFIABLE, v, marked)
        val matches = values.indices.filter { close(v, values[it]!!) }
        return when {
            marked in matches && matches.size == 1 -> VerifyResult(Verdict.VERIFIED, v, marked)
            matches.size == 1 -> VerifyResult(Verdict.FIXED, v, matches[0])
            else -> VerifyResult(Verdict.DISCARD, v, marked)
        }
    }
}
