package com.offlinetutor.domain

/**
 * 6a Teach Kutty: score a student's explanation against 3–5 key points by keyword / phrase match, and parse the
 * key points the model wrote beforehand. Pure Kotlin, JVM-tested.
 */
object KuttyScoring {
    private val stop = setOf(
        "a", "an", "the", "of", "in", "on", "to", "for", "by", "with", "and", "or", "is", "are", "was", "were", "be", "it",
        "its", "this", "that", "these", "those", "as", "at", "from", "into", "can", "will", "has", "have", "had", "which",
        "when", "what", "how", "why", "we", "you", "they", "their", "our", "so", "if", "then", "than", "also", "more", "most",
        "very", "each", "all", "one", "two", "use", "used", "using", "called", "make", "makes", "made", "does", "do",
    )

    /** Lower-cased content words with a light plural / -ing / -ed strip so "plants" matches "plant". */
    fun keywords(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 && it !in stop }
        .map { stem(it) }.distinct()

    private fun stem(w: String): String = when {
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
        w.endsWith("ed") && w.length > 4 -> w.dropLast(2)
        w.endsWith("es") && w.length > 4 -> w.dropLast(2)
        w.endsWith("s") && w.length > 3 -> w.dropLast(1)
        else -> w
    }

    data class PointResult(val point: String, val covered: Boolean, val matched: List<String>, val missing: List<String>)
    data class Score(val points: List<PointResult>) {
        val covered: Int get() = points.count { it.covered }
        val total: Int get() = points.size
    }

    /** A key point counts as covered when at least half of its content words (min 1) appear in the explanation. */
    fun score(explanation: String, keyPoints: List<String>): Score {
        val have = keywords(explanation).toSet()
        return Score(keyPoints.map { p ->
            val kw = keywords(p)
            val matched = kw.filter { it in have }
            val need = maxOf(1, (kw.size + 1) / 2)
            PointResult(p, kw.isNotEmpty() && matched.size >= need, matched, kw - matched.toSet())
        })
    }

    /** "1. point" / "- point" lines → 3..5 short points, or null if the model did not produce enough. */
    fun parsePoints(raw: String): List<String>? {
        val pts = raw.lines().mapNotNull { l ->
            Regex("^\\s*(?:\\d+[.)]|[-*•])\\s*(.+)$").find(l)?.groupValues?.get(1)?.trim()?.trimEnd('.')
        }.filter { it.length in 8..160 && keywords(it).isNotEmpty() }.distinctBy { it.lowercase() }
        return pts.take(5).takeIf { it.size >= 3 }
    }

    /** Kutty's follow-up: the first question sentence the model wrote, or null. */
    fun parseQuestion(raw: String): String? = Regex("[^.!?\\n]*\\?").find(raw.replace("\"", ""))?.value?.trim()
        ?.takeIf { it.length >= 8 }
}

/** 6b Textbook mode: split OCR text into passages and build an FTS4 MATCH query from a topic. */
object Textbook {
    /** Paragraph-ish passages of at most [maxChars], so 1–2 of them fit the quiz prompt budget. */
    fun passages(text: String, maxChars: Int = 500): List<String> {
        val paras = text.replace("\r", "").split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length >= 40 }
        val out = ArrayList<String>()
        for (p in paras) {
            if (p.length <= maxChars) { out.add(p); continue }
            var cur = StringBuilder()
            for (s in p.split(Regex("(?<=[.!?।])\\s+"))) {
                if (cur.length + s.length + 1 > maxChars && cur.isNotEmpty()) { out.add(cur.toString().trim()); cur = StringBuilder() }
                cur.append(s).append(' ')
            }
            if (cur.isNotBlank()) out.add(cur.toString().trim().take(maxChars))
        }
        return out
    }

    /** OR-query of the topic's content words for FTS4 MATCH (prefix match), or null when nothing usable. */
    fun matchQuery(topic: String): String? = KuttyScoring.keywords(topic).filter { w -> w.all { it.isLetterOrDigit() } }
        .take(6).takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "$it*" }
}

/** 6a prompts. Key points are written before the student explains, so the score does not depend on their wording. */
object KuttyPrompts {
    const val POINTS_PREFIX = "1. "

    fun keyPoints(grade: Int, concept: String, course: String): String {
        val user = buildString {
            append("A Grade $grade student in India will explain \"$concept\" ($course) to a younger child.\n")
            append("Write the 4 key points a good explanation must include. One short sentence each, as numbered lines, nothing else.")
        }
        return com.offlinetutor.llm.Llama3Prompt.single(
            "You are an expert Indian school teacher who writes short, clear key points.", user, POINTS_PREFIX)
    }

    /** Kutty = a curious, slightly confused 8-year-old who asks ONE naive question about what was unclear. */
    fun followUp(concept: String, explanation: String, missed: List<String>): String {
        val user = buildString {
            append("Your big brother or sister just explained \"$concept\" to you like this:\n\"${explanation.take(900)}\"\n")
            if (missed.isNotEmpty()) append("They did not explain: ${missed.take(2).joinToString("; ")}.\n")
            append("Ask ONE short, simple question that a confused 8-year-old would ask about it. Only the question.")
        }
        return com.offlinetutor.llm.Llama3Prompt.single(
            "You are Kutty, a curious and slightly confused 8-year-old child in India. You speak simply and kindly.", user)
    }
}
