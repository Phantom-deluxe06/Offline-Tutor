package com.offlinetutor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Code-level "never ask the same question twice": normalise the stem and compare it to
 * the student's whole Room quiz history with an exact match, a word-set Jaccard check
 * and a Levenshtein ratio. The prompt's "do not repeat" line is only a second layer.
 */
object QuestionDeduper {
    private val stopWords = setOf(
        "the", "a", "an", "of", "is", "are", "what", "which", "following", "to", "in", "and",
        "for", "on", "by", "with", "be", "that", "this", "does", "do", "how", "from", "at", "as", "it",
    )
    private val leadingLabel = Regex("^(q(uestion)?\\s*\\d*\\s*[:.)-]\\s*)")

    fun normalize(s: String): String = s.lowercase()
        .replace(leadingLabel, "")
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun contentWords(norm: String): Set<String> =
        norm.split(' ').filter { it.length > 1 && it !in stopWords }.toSet()

    fun isDuplicate(stem: String, historyNormalized: Collection<String>): Boolean {
        val n = normalize(stem)
        if (n.isEmpty()) return true
        if (n in historyNormalized) return true
        val words = contentWords(n)
        val nums = numbers(n)
        for (h in historyNormalized) {
            if (h.isEmpty()) continue
            val hw = contentWords(h)
            if (words.isNotEmpty() && hw.isNotEmpty()) {
                val inter = words.intersect(hw).size.toDouble()
                val union = words.union(hw).size.toDouble()
                val jaccard = inter / union
                if (jaccard >= JACCARD) return true
                // Seen on-device: "12 loaves of bread per day" vs "12 loaves of flour daily" (Jaccard 0.58).
                // The same numbers plus half the words in common is the same word problem reworded.
                if (nums.isNotEmpty() && nums == numbers(h) && jaccard >= JACCARD_SAME_NUMBERS) return true
            }
            val shorter = min(n.length, h.length)
            val longer = max(n.length, h.length)
            if (shorter * 10 >= longer * 7 && levenshteinRatio(n, h) >= LEVENSHTEIN) return true
        }
        return false
    }

    fun levenshteinRatio(a: String, b: String): Double {
        val x = a.take(300)
        val y = b.take(300)
        if (x == y) return 1.0
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[y.length].toDouble() / max(x.length, y.length)
    }

    private fun numbers(norm: String): Set<String> = Regex("\\d+").findAll(norm).map { it.value }.toSet()

    const val JACCARD = 0.75
    const val JACCARD_SAME_NUMBERS = 0.5
    const val LEVENSHTEIN = 0.85
}
