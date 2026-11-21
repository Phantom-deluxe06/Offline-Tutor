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
            number. Step title - one short sentence goal - Subtopics: subtopic, subtopic - Needs: earlier step numbers it builds on, or none
            Example of the format for a music course (do not copy it):
            1. Rhythm basics - Learn to keep a steady beat - Subtopics: beat, tempo, clapping patterns - Needs: none
            2. Reading notes - Name notes on the staff - Subtopics: treble clef, note names - Needs: 1
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
        focus: String? = null,
        // 2-fix-2: off in the app; only the eval A/B (2-fix-3) asks for the expression field.
        withExpression: Boolean = false,
        // 2-fix-2: numeric questions come from Kotlin templates, so free-form LLM MCQs stay conceptual.
        conceptual: Boolean = false,
    ): String {
        fun build(stems: List<String>): String {
            val exprRule = if (withExpression)
                "\nIf the answer is a number, \"expression\" is the arithmetic that gives it, for example 120/1.5; otherwise leave it empty." else ""
            val conceptRule = if (conceptual)
                "\nAsk about an idea, definition, reason or example. Do not ask a calculation question." else ""
            val shape = "{\"question\":\"...\",\"A\":\"...\",\"B\":\"...\",\"C\":\"...\",\"D\":\"...\",\"answer\":\"B\",\"explanation\":\"one or two sentences\"" +
                (if (withExpression) ",\"expression\":\"\"}" else "}")
            // On-device testing showed the 1B model copies full earlier questions verbatim when
            // they are in the prompt, so only their keywords are passed as "already covered".
            val avoid = if (stems.isEmpty()) "" else
                "\nAlready asked about (ask about something different): " +
                    stems.map { keywords(it) }.filter { it.isNotBlank() }.distinct().joinToString("; ")
            val weak = (if (weakSubtopics.isEmpty()) "" else
                "\nThe student is weak at: ${weakSubtopics.joinToString(", ")}.") +
                // 1e: a fix step's check question targets the misconception being repaired.
                (focus?.let { "\nThe question must check whether the student still makes this mistake: $it" }.orEmpty())
            val user = """
                Write ONE new multiple-choice question for a Grade $grade student in India.
                Subject: $course
                Topic: $subtopic
                Difficulty: ${difficultyText(level)}$weak$avoid$conceptRule
                Rules: options A, B, C and D are all different and only one is correct. Never ask the same question twice.
                "answer" is the letter of the correct option.$exprRule
                Return ONLY JSON in exactly this shape:
                $shape
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

    // ---------- 1b: diagnostic MCQ ----------
    // Measured on-device: the 1B model could not produce the nested diagnostic JSON in one shot (it copied
    // "..."/"CODE" placeholders and invented options E–G). So a diagnostic MCQ is built in two steps:
    // the plain MCQ (validated path), then this short line-format call that tags each wrong option.
    /**
     * Measured on-device: asked for "B) CODE - reason" lines, the 1B model wrote one block per option
     * ("The mistake type: …" / "The reason: …"), so the prompt asks for exactly that block shape and the
     * first block is prefilled up to "Mistake type: " so a code comes first.
     */
    fun tagPrefix(firstWrong: String) = "B) $firstWrong\nMistake type: "

    /** Continuation prefix: the blocks already tagged, then the next block header up to "Mistake type: ". */
    fun tagContinuationPrefix(wrong: List<String>, done: List<OptionTag>, next: Int): String = buildString {
        done.forEachIndexed { i, t -> append("${'B' + i}) ${wrong[i]}\nMistake type: ${t.code.name}\nReason: ${t.why}\n\n") }
        append("${'B' + next}) ${wrong[next]}\nMistake type: ")
    }

    fun diagnosticTagLines(question: String, correct: String, wrong: List<String>, prefix: String = tagPrefix(wrong.firstOrNull().orEmpty())): String {
        val user = buildString {
            append("Question: $question\nCorrect answer: $correct\nWrong answers:\n")
            wrong.forEachIndexed { i, w -> append("${'B' + i}) $w\n") }
            append("Mistake types:\n")
            append("SIGN_ERROR: a sign or direction was flipped\n")
            append("UNIT_CONVERSION: units not converted, or converted wrongly\n")
            append("FORMULA_SWAP: used a related but wrong formula\n")
            append("OPERATION_SWAP: did the wrong operation (+, -, x, /)\n")
            append("CONCEPT_CONFUSION: mixed up two related ideas\n")
            append("PARTIAL_STEP: stopped one step early\n")
            append("MISREAD_QUESTION: answered a different question\n")
            append("OVERGENERALISATION: used a rule where it does not apply\n")
            append("For each wrong answer, say which mistake type makes a student choose it. Answer with one block per wrong answer:\n")
            append("B) <wrong answer>\nMistake type: <one type from the list>\nReason: <short reason>")
        }
        return Llama3Prompt.single(TAG_SYSTEM, user, prefix)
    }

    /** Stop once the D tag is complete (its line has ended), in either the block or the one-line shape. */
    fun tagLinesDone(text: String): Boolean = text.endsWith("\n") && DiagnosticParser.parseTagLines(text).getOrNull(2) != null

    private const val TAG_SYSTEM = "You are an expert Indian school teacher who knows why students choose wrong answers."

    /** 1e: the ~2-minute micro-fix lesson for one misconception. */
    fun microFix(grade: Int, lang: ExplainLanguage, concept: String, codeDescription: String, why: String, question: String?): String {
        val user = buildString {
            append("A Grade $grade student learning \"$concept\" made this mistake: $codeDescription")
            if (why.isNotBlank()) append(" (they $why)")
            append(".\n")
            question?.let { append("The question was: $it\n") }
            append("Write a short lesson (about 120 words) that fixes this mistake:\n")
            append("1. Say clearly what the mistake is and why it is wrong.\n")
            append("2. Explain the right way step by step.\n")
            append("3. Give ONE analogy from everyday Indian life, starting the line with \"Analogy:\".")
        }
        return Llama3Prompt.single(tutorSystem(grade, lang), user)
    }

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

    // ---------- 2a: template word problems ----------
    private const val TEMPLATE_SYSTEM =
        "You write short story settings for school word problems set in everyday Indian life. You never write numbers."

    private val storyExamples = listOf(
        "Meena carries {A} home from the ration shop.",
        "Meena carries {A} home from the ration shop, walking for {B}.",
        "Meena's uncle keeps {A} in the village post office at {B} for {C}.",
    )

    /**
     * 2-fix-1: the model writes only the setting, with placeholders for the quantities. No digits anywhere in the
     * prompt (measured: "Grade 8" in the prompt was echoed into the story). Kotlin fills the numbers and appends
     * its own question sentence.
     */
    fun templateStory(grade: Int, p: TemplateProblem): String {
        val band = if (grade <= 8) "middle-school" else "high-school"
        val user = buildList {
            add("Write one or two sentences about ${p.setting} for a $band student.")
            add("Use each of these placeholders exactly once, written exactly like this, instead of the quantities:")
            p.slots.forEachIndexed { i, s -> add("${ProblemTemplates.placeholder(i)} = ${s.meaning}") }
            add("Do not write any numbers or number words (like five, twice or half). Do not ask a question and do not solve anything.")
            add("Example for a different problem: ${storyExamples[(p.slots.size - 1).coerceIn(0, storyExamples.lastIndex)]}")
        }.joinToString("\n")
        return Llama3Prompt.single(TEMPLATE_SYSTEM, user)
    }

    /** Stop once every placeholder has been written and the sentence has ended. */
    fun storyDone(p: TemplateProblem): (String) -> Boolean = { text ->
        p.placeholders.all { it in text } && text.trimEnd().let { it.endsWith('.') || it.endsWith('!') }
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
