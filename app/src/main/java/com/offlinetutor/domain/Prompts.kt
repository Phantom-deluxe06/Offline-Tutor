package com.offlinetutor.domain

import com.offlinetutor.llm.Llama3Prompt
import com.offlinetutor.llm.TokenEstimator

enum class ExplainLanguage(val code: String, val englishName: String, val nativeName: String, val beta: Boolean) {
    EN("en", "English", "English", false),
    TA("ta", "Tamil", "தமிழ்", true),
    BN("bn", "Bengali", "বাংলা", true);

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: EN
    }
}

/**
 * All prompts. Budget (export context = 1024): prompt <= ~600 tokens, output 300-400.
 * JSON tasks use an assistant prefill so the 1B model starts inside the right structure.
 */
object Prompts {
    const val PROMPT_BUDGET_TOKENS = 600

    fun tutorSystem(grade: Int, lang: ExplainLanguage): String = buildString {
        append("You are Guru, a kind and patient tutor for Indian school students. ")
        append("The student is in Grade $grade. Explain step by step in simple words. ")
        append("Use everyday Indian examples: cricket scores, rupees and paise, trains, monsoon rain, farming, festivals, the local kirana shop. ")
        append("Keep answers under 150 words unless asked for more. If you are not sure, say so honestly.")
        append(languageRule(lang))
    }

    private fun languageRule(lang: ExplainLanguage) = when (lang) {
        ExplainLanguage.EN -> ""
        ExplainLanguage.TA -> " Reply in Tamil (தமிழ்) using Tamil script. Keep scientific and maths terms in English inside brackets."
        ExplainLanguage.BN -> " Reply in Bengali (বাংলা) using Bengali script. Keep scientific and maths terms in English inside brackets."
    }

    private const val JSON_SYSTEM =
        "You are an expert Indian school teacher. You output only valid JSON, with no text before or after it."

    // ---------- Roadmap ----------
    // On-device eval: nested JSON roadmaps were 0-13% valid at 1B (the model re-opened objects and
    // repeated keys), so roadmaps use numbered plain-text lines instead, parsed leniently.
    const val ROADMAP_PREFIX = "1. "
    const val ROADMAP_STEPS = 5

    fun roadmap(grade: Int, course: String): String {
        val user = """
            Write a step-by-step study roadmap for a Grade $grade student in India learning $course.
            Order the steps from basics to advanced and match the Grade $grade syllabus (NCERT level).
            Write exactly $ROADMAP_STEPS numbered lines and nothing else. Each line has this format:
            number. Step title - one short sentence goal - Subtopics: subtopic, subtopic, subtopic
            Example of the format for a music course (do not copy it):
            1. Rhythm basics - Learn to keep a steady beat - Subtopics: beat, tempo, clapping patterns
        """.trimIndent()
        return Llama3Prompt.single(ROADMAP_SYSTEM, user, ROADMAP_PREFIX)
    }

    private val nextStepAfterLast = Regex("\\n\\s*(?:step\\s*)?${ROADMAP_STEPS + 1}\\s*[.):]", RegexOption.IGNORE_CASE)

    /** Stop as soon as the model starts a step beyond the requested count. */
    fun roadmapDone(text: String): Boolean = nextStepAfterLast.containsMatchIn(text)

    private const val ROADMAP_SYSTEM =
        "You are an expert Indian school teacher who writes short, clear study plans."

    // ---------- MCQ ----------
    const val MCQ_PREFIX = "{\"question\":\""

    fun difficultyText(level: Int) = when (level) {
        AdaptiveDifficulty.EASY -> "EASY: direct recall of one basic fact or definition"
        AdaptiveDifficulty.MEDIUM -> "MEDIUM: apply one concept in a single step"
        else -> "HARD: multi-step reasoning or a tricky application"
    }

    fun mcq(
        grade: Int,
        course: String,
        subtopic: String,
        level: Int,
        recentStems: List<String>,
        weakSubtopics: List<String>,
    ): String {
        fun build(stems: List<String>): String {
            // On-device testing showed the 1B model copies full earlier questions verbatim when
            // they are in the prompt, so only their keywords are passed as "already covered".
            val avoid = if (stems.isEmpty()) "" else
                "\nAlready asked about (ask about something different): " +
                    stems.map { keywords(it) }.filter { it.isNotBlank() }.distinct().joinToString("; ")
            val weak = if (weakSubtopics.isEmpty()) "" else
                "\nThe student is weak at: ${weakSubtopics.joinToString(", ")}."
            val user = """
                Write ONE new multiple-choice question for a Grade $grade student in India.
                Subject: $course
                Topic: $subtopic
                Difficulty: ${difficultyText(level)}$weak$avoid
                Rules: options A, B, C and D are all different and only one is correct. Never ask the same question twice.
                "answer" is the letter of the correct option.
                Return ONLY JSON in exactly this shape:
                {"question":"...","A":"...","B":"...","C":"...","D":"...","answer":"B","explanation":"one or two sentences"}
            """.trimIndent()
            return Llama3Prompt.single(JSON_SYSTEM, user, MCQ_PREFIX)
        }
        // Keep at most the last 10 stems, and drop more if the prompt is over budget.
        var stems = recentStems.take(10)
        var p = build(stems)
        while (TokenEstimator.estimate(p) > PROMPT_BUDGET_TOKENS && stems.isNotEmpty()) {
            stems = stems.dropLast(1)
            p = build(stems)
        }
        return p
    }

    private val stop = setOf(
        "what", "which", "who", "whom", "when", "where", "why", "how", "is", "are", "was", "were", "the", "a", "an",
        "of", "in", "on", "to", "for", "by", "with", "and", "or", "do", "does", "did", "many", "much", "make", "makes",
        "written", "following", "called", "known", "value", "find", "one", "this", "that", "it", "as", "at", "be",
    )

    /** Up to 4 content words of a question stem, e.g. "paise rupee" / "3/4 percentage". */
    fun keywords(stem: String): String = stem.lowercase()
        .split(Regex("[^a-z0-9/.%+=-]+"))
        .filter { it.length > 1 && it !in stop }
        .take(4)
        .joinToString(" ")

    const val ANSWER_PREFIX = "{\"answer\":\""

    /** Follow-up when the model wrote the question and options but stopped before the answer. */
    fun mcqAnswer(grade: Int, question: String, options: List<String>): String {
        val user = buildString {
            append("A Grade $grade student must answer this question.\n")
            append("Question: $question\n")
            options.forEachIndexed { i, o -> append("${'A' + i}) $o\n") }
            append("Which option is correct? Think about it carefully, then return ONLY JSON in exactly this shape:\n")
            append("{\"answer\":\"B\",\"explanation\":\"one or two sentences\"}")
        }
        return Llama3Prompt.single(JSON_SYSTEM, user, ANSWER_PREFIX)
    }

    // ---------- Explanations ----------
    fun explainAnswer(grade: Int, lang: ExplainLanguage, question: String, correctOption: String, explanation: String): String {
        val user = """
            Question: $question
            Correct answer: $correctOption
            Short reason: $explanation
            Explain why this answer is correct, step by step, for a Grade $grade student. Use a simple Indian everyday example. Keep it under 120 words.
        """.trimIndent()
        return Llama3Prompt.single(tutorSystem(grade, lang), user)
    }

    // ---------- Chat ----------
    data class ChatTurn(val fromUser: Boolean, val text: String)

    /** Keeps the newest turns that fit the budget (Indic scripts cost ~4x more tokens). */
    fun chat(grade: Int, lang: ExplainLanguage, course: String?, history: List<ChatTurn>): String {
        val system = tutorSystem(grade, lang) + (course?.let { " The student is currently studying $it." } ?: "")
        val budget = PROMPT_BUDGET_TOKENS - TokenEstimator.estimate(system) - 30
        val kept = ArrayList<ChatTurn>()
        var used = 0
        for (t in history.asReversed()) {
            val text = t.text.take(if (t.fromUser) 600 else 900)
            val cost = TokenEstimator.estimate(text) + 8
            if (used + cost > budget && kept.isNotEmpty()) break
            kept.add(t.copy(text = text))
            used += cost
            if (kept.size >= 6) break
        }
        kept.reverse()
        // The template needs to start with a user turn.
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeAt(0)
        val turns = kept.map {
            Llama3Prompt.Turn(if (it.fromUser) Llama3Prompt.Role.USER else Llama3Prompt.Role.ASSISTANT, it.text)
        }
        return Llama3Prompt.build(system, turns)
    }

    // ---------- Benchmark ----------
    const val BENCH_QUESTION = "Explain photosynthesis to a Grade 7 student in five short sentences."

    fun bench(): String = Llama3Prompt.single(tutorSystem(7, ExplainLanguage.EN), BENCH_QUESTION)
}
