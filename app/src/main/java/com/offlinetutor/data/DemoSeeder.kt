package com.offlinetutor.data

import com.offlinetutor.domain.DiagMcq
import com.offlinetutor.domain.DiagOption
import com.offlinetutor.domain.Misconception
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.RoadmapDraft

/**
 * Debug "Seed demo student": a Grade 7 maths profile whose history, replayed through the real
 * [AdaptiveBrain], triggers a MICRO_FIX (confident FORMULA_SWAP on percentages) and root-cause
 * backtracking ("Stuck on Percentages because Fractions is shaky"). No model needed.
 */
class DemoSeeder(
    private val profiles: ProfileRepository,
    private val roadmaps: RoadmapDao,
    private val quiz: QuizDao,
    private val brain: AdaptiveBrain,
) {
    private data class Attempt(val concept: String, val level: Int, val q: DiagMcq, val chosen: Int, val confident: Boolean, val ms: Long)

    suspend fun seed(): Long {
        val course = "Mathematics"
        val id = profiles.create("Demo Student", 7, listOf(course), "en", null)
        val profile = profiles.get(id)!!
        val drafts = listOf(
            RoadmapDraft("Whole numbers", "Add, subtract, multiply and divide with confidence", listOf("Place value", "Operations"), emptyList()),
            RoadmapDraft("Fractions", "Understand parts of a whole and compare fractions", listOf("Equivalent fractions", "Fractions of a quantity"), listOf(1)),
            RoadmapDraft("Ratios", "Compare quantities using ratios", listOf("Simplifying ratios", "Sharing in a ratio"), listOf(2)),
            RoadmapDraft("Percentages", "Find percentages of amounts and percentage change", listOf("Percent of a number", "Percentage change"), listOf(2, 3)),
            RoadmapDraft("Profit and loss", "Work out profit, loss and discounts", listOf("Profit percent", "Discount"), listOf(4)),
        )
        val steps = RoadmapRepository.toSteps(id, course, drafts, "demo")
        roadmaps.replaceCourse(id, course, steps.mapIndexed { i, s -> if (i == 0) s.copy(done = true) else s })
        val c = steps.map { it.concept }

        fun q(stem: String, opts: List<Pair<String, Pair<Misconception, String>?>>, explanation: String) =
            DiagMcq(stem, "", null, opts.mapIndexed { i, (t, tag) -> DiagOption(t, tag == null, tag?.first, tag?.second) }, explanation)

        val whole = q("A school has 12 classes with 35 students each. How many students are there?",
            listOf("420" to null, "47" to (Misconception.OPERATION_SWAP to "added instead of multiplying"),
                "350" to (Misconception.PARTIAL_STEP to "multiplied by 10 only"), "4200" to (Misconception.UNIT_CONVERSION to "added an extra zero")),
            "12 x 35 = 420.")
        val frac = q("What is 3/5 of 40 rupees?",
            listOf("24 rupees" to null, "8 rupees" to (Misconception.PARTIAL_STEP to "found 1/5 but did not multiply by 3"),
                "15 rupees" to (Misconception.OPERATION_SWAP to "subtracted instead of taking a fraction"),
                "66 rupees" to (Misconception.FORMULA_SWAP to "divided by 3 and multiplied by 5")),
            "40 / 5 = 8, and 8 x 3 = 24.")
        val ratio = q("Share 30 sweets between Asha and Ravi in the ratio 2 : 3. How many does Ravi get?",
            listOf("18" to null, "12" to (Misconception.MISREAD_QUESTION to "gave Asha's share"),
                "15" to (Misconception.OVERGENERALISATION to "split equally"), "10" to (Misconception.PARTIAL_STEP to "found one part x 2 only")),
            "5 parts = 30, so 1 part = 6 and Ravi gets 3 x 6 = 18.")
        val pct = q("A shirt costs 400 rupees. What is 15% of its price?",
            listOf("60 rupees" to null, "2667 rupees" to (Misconception.FORMULA_SWAP to "divided by 15 and multiplied by 100"),
                "385 rupees" to (Misconception.OPERATION_SWAP to "subtracted 15 from 400"),
                "6 rupees" to (Misconception.UNIT_CONVERSION to "divided by 1000 instead of 100")),
            "15% of 400 = 15 / 100 x 400 = 60.")

        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        val attempts = listOf(
            Attempt(c[0], 1, whole, 0, true, 5_000), Attempt(c[0], 2, whole, 0, true, 6_000),
            Attempt(c[0], 3, whole, 0, true, 6_000), Attempt(c[0], 3, whole, 0, true, 7_000),
            // Fractions: tested but shaky (different, unsure mistakes → no fix step, but low mastery).
            Attempt(c[1], 1, frac, 0, false, 40_000), Attempt(c[1], 1, frac, 1, false, 30_000), Attempt(c[1], 1, frac, 3, false, 25_000),
            Attempt(c[2], 1, ratio, 0, true, 9_000), Attempt(c[2], 2, ratio, 0, true, 12_000), Attempt(c[2], 2, ratio, 1, false, 30_000),
            // Percentages: one confident FORMULA_SWAP (→ fix step), then stuck below threshold after 4 attempts
            // (→ backtracking picks the weakest prerequisite, Fractions).
            Attempt(c[3], 1, pct, 2, false, 30_000), Attempt(c[3], 1, pct, 1, true, 20_000),
            Attempt(c[3], 1, pct, 3, false, 35_000), Attempt(c[3], 1, pct, 0, false, 45_000),
        )
        attempts.forEachIndexed { i, a ->
            val at = now - (attempts.size - i) * day / 3
            val correct = a.q.options[a.chosen].correct
            val tag = a.q.options[a.chosen].code
            val row = QuizQuestion(
                profileId = id, course = course, subtopic = steps.first { it.concept == a.concept }.subtopicList.first(),
                difficulty = a.level, stem = a.q.question, normalizedStem = QuestionDeduper.normalize(a.q.question) + " #$i",
                options = a.q.options.joinToString(QuizQuestion.OPTION_SEP.toString()) { it.text }, answerIndex = a.q.answer,
                explanation = a.q.explanation, source = "seed-demo", askedAt = at - a.ms, answeredAt = at,
                chosenIndex = a.chosen, correct = correct, concept = a.concept, optionMeta = a.q.metaJson(),
                confident = a.confident, responseMs = a.ms, misconception = if (correct) null else tag?.name,
            )
            brain.onAnswer(profile, row.copy(id = quiz.insert(row)))
        }
        return id
    }
}
