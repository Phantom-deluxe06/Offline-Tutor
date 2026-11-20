package com.offlinetutor

import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.JsonRepair
import com.offlinetutor.domain.McqParse
import com.offlinetutor.domain.McqParser
import com.offlinetutor.domain.Mcq
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.RoadmapParser
import com.offlinetutor.domain.WeakSpots
import com.offlinetutor.llm.JsonCompletionDetector
import com.offlinetutor.llm.Llama3Prompt
import com.offlinetutor.llm.RepetitionGuard
import com.offlinetutor.llm.SpecialTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RepetitionGuardTest {
    private fun feedWords(g: RepetitionGuard, text: String): Boolean =
        text.split(Regex("(?<= )")).any { g.feed(it) }

    @Test fun normalTextPasses() {
        val g = RepetitionGuard()
        assertFalse(feedWords(g, "Photosynthesis is how plants make food. They use sunlight, water and carbon dioxide. Oxygen is released as a by-product. "))
    }

    @Test fun tailLoopStops() {
        val g = RepetitionGuard()
        assertTrue(feedWords(g, "The answer is " + "very good and ".repeat(5)))
        assertNotNull(g.reason)
    }

    @Test fun jsonArrayLoopWithoutSpacesStops() {
        // Seen on-device: the 1B model looped on MCQ options with no whitespace.
        val g = RepetitionGuard()
        val stream = "{\"question\":\"Which angle is acute?\",\"options\":[\"30\",\"45\"," +
            "\"60\",\"90\",\"30\",\"45\",".repeat(6)
        val stopped = stream.chunked(3).any { g.feed(it) }
        assertTrue(stopped)
    }

    @Test fun normalJsonMcqPasses() {
        val g = RepetitionGuard()
        val json = "{\"question\":\"What is 7 x 8?\",\"options\":[\"54\",\"56\",\"63\",\"48\"],\"answer\":1," +
            "\"explanation\":\"Seven groups of eight make fifty-six.\"}"
        assertFalse(json.chunked(3).any { g.feed(it) })
    }

    @Test fun repeatedLineStops() {
        val g = RepetitionGuard()
        val stopped = (1..4).any { g.feed("Step: add the two numbers together\n") }
        assertTrue(stopped)
    }

    @Test fun charRunStops() {
        assertTrue(RepetitionGuard().feed("!".repeat(50)))
    }

    @Test fun blankOutputStops() {
        val g = RepetitionGuard(blankTokenLimit = 10)
        assertTrue((1..12).any { g.feed(" ") })
    }
}

class JsonTest {
    @Test fun detectorClosesOnBalancedObject() {
        val d = JsonCompletionDetector()
        assertFalse(d.feed("{\"a\":\"}\","))
        assertFalse(d.feed("\"b\":[1,2]"))
        assertTrue(d.feed("} trailing"))
    }

    @Test fun repairTruncatedOutput() {
        val raw = "Sure! {\"question\":\"What is 2+2?\",\"options\":[\"3\",\"4\",\"5\",\"6\"],\"answer\":1,\"explanation\":\"Two plus two"
        val fixed = JsonRepair.extractObject(raw)!!
        val p = McqParser.parse(fixed)
        assertTrue(p is McqParse.Ok)
        assertEquals(1, (p as McqParse.Ok).mcq.answer)
    }

    @Test fun parserAcceptsLetterAnswersAndPrefixes() {
        val raw = "{\"question\":\"Capital of India?\",\"options\":[\"A) Mumbai\",\"B) New Delhi\",\"C) Kolkata\",\"D) Chennai\"],\"answer\":\"B\",\"explanation\":\"New Delhi.\",}"
        val p = McqParser.parse(raw) as McqParse.Ok
        assertEquals("New Delhi", p.mcq.options[1])
        assertEquals(1, p.mcq.answer)
    }

    @Test fun parserAcceptsLetterKeyFormat() {
        val raw = "{\"question\":\"What is 7 x 8?\",\"A\":\"54\",\"B\":\"56\",\"C\":\"63\",\"D\":\"48\",\"answer\":\"B\",\"explanation\":\"7 x 8 = 56.\"}"
        val p = McqParser.parse(raw) as McqParse.Ok
        assertEquals(listOf("54", "56", "63", "48"), p.mcq.options)
        assertEquals(1, p.mcq.answer)
    }

    @Test fun stemWithoutAnswerIsCompletedByFollowUp() {
        // Verbatim on-device failure: object closed right after option D.
        val raw = "{\"question\":\"What is the smallest unit of code that can be executed by a computer?\"," +
            "\"A\":\"program\",\"B\":\"algorithm\",\"C\":\"variable\",\"D\":\"function\"}"
        assertTrue(McqParser.parse(raw) is McqParse.Invalid)
        val stem = McqParser.parseStem(raw)!!
        val done = McqParser.completeWith(stem, "{\"answer\":\"D\",\"explanation\":\"A function is a callable unit.\"}")
        assertEquals(3, (done as McqParse.Ok).mcq.answer)
    }

    @Test fun repairsReopenedObjectAndDoubledQuote() {
        val raw = "{\"question\":\"Which festival is called the festival of colours?\"{\"A\":\"Holi\",\"B\":\"Diwali\"," +
            "\"C\":\"Navratri\",\"D\":\"Gudi Padwa\",\"answer\":\"A\",\"explanation\":\"Holi.\"\"}"
        assertTrue(McqParser.parse(raw) is McqParse.Ok)
    }

    @Test fun parserRejectsCopiedPlaceholders() {
        // Verbatim on-device output: explanation copied from the prompt template.
        val raw = "{\"question\":\"A bakery sells 12 loaves per day. How many in a week?\",\"A\":\"36\",\"B\":\"24\",\"C\":\"48\",\"D\":\"12\"," +
            "\"answer\":\"C\",\"explanation\":\"one or two sentences\"}"
        assertTrue(McqParser.parse(raw) is McqParse.Invalid)
        val dots = raw.replace("one or two sentences", "...")
        assertTrue(McqParser.parse(dots) is McqParse.Invalid)
    }

    @Test fun workedResultNotAmongOptionsIsRejected() {
        // Verbatim on-device: key "D) 12" but the explanation computes 84, which is no option.
        val raw = "{\"question\":\"A bakery sells 12 loaves of flour daily. How many does it sell in a week?\",\"A\":\"36\",\"B\":\"24\"," +
            "\"C\":\"8\",\"D\":\"12\",\"answer\":\"D\",\"explanation\":\"12 loaves * 7 days = 84 loaves\"}"
        assertTrue(McqParser.parse(raw) is McqParse.Invalid)
    }

    @Test fun workedResultFixesWrongKey() {
        val raw = "{\"question\":\"A bakery sells 12 loaves a day. How many in a week?\",\"A\":\"84\",\"B\":\"24\"," +
            "\"C\":\"19\",\"D\":\"12\",\"answer\":\"D\",\"explanation\":\"12 x 7 = 84.\"}"
        assertEquals(0, (McqParser.parse(raw) as McqParse.Ok).mcq.answer)
    }

    @Test fun nonNumericOptionsSkipResultCheck() {
        val raw = "{\"question\":\"Which gas do plants take in?\",\"A\":\"Oxygen\",\"B\":\"Carbon dioxide\",\"C\":\"Nitrogen\",\"D\":\"Helium\"," +
            "\"answer\":\"B\",\"explanation\":\"Plants use CO2; 6 CO2 + 6 H2O = 1 glucose.\"}"
        assertEquals(1, (McqParser.parse(raw) as McqParse.Ok).mcq.answer)
    }

    @Test fun singleLetterOptionsStillAllowedWhenReal() {
        // Real options can be single characters (e.g. "x", "5"); only exact placeholder copies are rejected.
        val raw = "{\"question\":\"Which number is even?\",\"A\":\"3\",\"B\":\"4\",\"C\":\"5\",\"D\":\"7\",\"answer\":\"B\",\"explanation\":\"4 is divisible by 2.\"}"
        assertTrue(McqParser.parse(raw) is McqParse.Ok)
    }

    @Test fun parserRejectsDuplicateOptions() {
        val raw = "{\"question\":\"Pick one number\",\"options\":[\"1\",\"1\",\"2\",\"3\"],\"answer\":0,\"explanation\":\"x\"}"
        assertTrue(McqParser.parse(raw) is McqParse.Invalid)
    }

    @Test fun parserRejectsMissingExplanation() {
        val raw = "{\"question\":\"Pick one number\",\"options\":[\"1\",\"2\",\"3\",\"4\"],\"answer\":0}"
        assertTrue(McqParser.parse(raw) is McqParse.Invalid)
    }

    @Test fun roadmapParses() {
        val raw = "{\"steps\":[{\"title\":\"Numbers\",\"goal\":\"g\",\"subtopics\":[\"a\",\"b\"]},{\"title\":\"Algebra\",\"goal\":\"g\",\"subtopics\":[\"c\"]},{\"title\":\"Geometry\",\"goal\":\"g\",\"subtopics\":[]}]}"
        val steps = RoadmapParser.parse(raw)!!
        assertEquals(3, steps.size)
        assertEquals(listOf("Geometry"), steps[2].subtopics)
    }

    @Test fun roadmapParsesCommaSubtopicsAndStripsNumbering() {
        val raw = "{\"steps\":[{\"title\":\"Ratios\",\"goal\":\"g\",\"subtopics\":\"1.1 Ratios in real life, Equivalent ratios\"}," +
            "{\"title\":\"Proportion\",\"goal\":\"g\",\"subtopics\":\"Direct proportion, Inverse proportion\"}," +
            "{\"title\":\"Percent\",\"goal\":\"g\",\"subtopics\":\"Percent change\"}]}"
        val steps = RoadmapParser.parse(raw)!!
        assertEquals(listOf("Ratios in real life", "Equivalent ratios"), steps[0].subtopics)
    }

    @Test fun roadmapParsesNumberedLines() {
        val raw = "1. Fractions - Learn what fractions mean - Subtopics: proper fractions, equivalent fractions\n" +
            "2. **Decimals** - Read and compare decimals - Subtopics: place value, rounding.\n" +
            "Step 3: Percentages - Convert between forms - Subtopics: percent of a number\n" +
            "4. Ratios - Compare quantities\n" +
            "6."
        val steps = RoadmapParser.parse(raw)!!
        assertEquals(4, steps.size)
        assertEquals("Decimals", steps[1].title)
        assertEquals(listOf("place value", "rounding"), steps[1].subtopics)
        assertEquals(listOf("proper fractions", "equivalent fractions"), steps[0].subtopics)
        val withAnd = RoadmapParser.parse("1. Trig - Learn ratios - Subtopics: sine, cosine, and tangent ratios\n2. Two - g\n3. Three - g")!!
        assertEquals(listOf("sine", "cosine", "tangent ratios"), withAnd[0].subtopics)
        assertEquals("Compare quantities", steps[3].goal)
        assertEquals(listOf("Ratios"), steps[3].subtopics)
    }

    @Test fun roadmapHandlesRealOnDeviceOutput() {
        // Verbatim shapes from the motorola edge 40 eval run (repeated numbers, missing "Subtopics:" label).
        val raw = "1. 1. Review the basics of numbers and operations - Numbers, Place value, Basic arithmetic operations\n" +
            "2. 2. Understand the concept of fractions - Fractions, Equivalent fractions, Simplifying fractions\n" +
            "3. 3. Learn to solve simple algebraic expressions - Learn linear equations - Subtopics: Linear equations, Simple algebra"
        val steps = RoadmapParser.parse(raw)!!
        assertEquals("Review the basics of numbers and operations", steps[0].title)
        assertEquals(listOf("Numbers", "Place value", "Basic arithmetic operations"), steps[0].subtopics)
        assertEquals("Learn linear equations", steps[2].goal)
        assertEquals(listOf("Linear equations", "Simple algebra"), steps[2].subtopics)
    }

    @Test fun roadmapStopsAfterFiveSteps() {
        assertFalse(com.offlinetutor.domain.Prompts.roadmapDone("1. a\n2. b\n3. c\n4. d\n5. e"))
        assertTrue(com.offlinetutor.domain.Prompts.roadmapDone("1. a\n2. b\n3. c\n4. d\n5. e\n6."))
    }

    @Test fun jsonGuardAllowsRepeatedPhrasingAcrossSteps() {
        val g = RepetitionGuard(ngram = 10, maxNgramHits = 4)
        val json = (1..5).joinToString(",") { "{\"title\":\"Topic $it\",\"goal\":\"To understand the concept of topic $it in daily life.\"}" }
        assertFalse(json.chunked(4).any { g.feed(it) })
    }

    @Test fun shuffleKeepsAnswer() {
        val m = Mcq("q?", listOf("right", "w1", "w2", "w3"), 0, "e")
        repeat(20) {
            val s = m.shuffled(Random(it))
            assertEquals("right", s.options[s.answer])
        }
    }
}

class DedupeTest {
    private val history = listOf(
        "What is the capital of India?",
        "Which gas do plants take in for photosynthesis?",
    ).map(QuestionDeduper::normalize)

    @Test fun exactAfterNormalisation() {
        assertTrue(QuestionDeduper.isDuplicate("Q1. What is the CAPITAL of India ?", history))
    }

    @Test fun fuzzyRephrase() {
        assertTrue(QuestionDeduper.isDuplicate("Which gas do plants take in during photosynthesis?", history))
    }

    @Test fun rewordedWordProblemWithSameNumbersIsDuplicate() {
        // Verbatim on-device pair that slipped through before.
        val h = listOf(QuestionDeduper.normalize("A bakery sells 12 loaves of bread per day. How many loaves of bread does it sell in a week?"))
        assertTrue(QuestionDeduper.isDuplicate("A bakery sells 12 loaves of flour daily. How many loaves of flour does it sell in a week?", h))
        // Same template, different numbers: a genuinely new question.
        assertFalse(QuestionDeduper.isDuplicate("A shop sells 15 pens per day. How many pens does it sell in 4 days?", h))
    }

    @Test fun differentQuestionPasses() {
        assertFalse(QuestionDeduper.isDuplicate("What is the boiling point of water at sea level?", history))
    }
}

class AdaptiveTest {
    @Test fun twoUpOneDown() {
        var s = AdaptiveDifficulty.State()
        s = AdaptiveDifficulty.step(s, true); assertEquals(1, s.level)
        s = AdaptiveDifficulty.step(s, true); assertEquals(2, s.level)
        s = AdaptiveDifficulty.step(s, false); assertEquals(1, s.level)
    }

    @Test fun capsAtHard() {
        val s = AdaptiveDifficulty.fromHistory(List(10) { 3 to true })
        assertEquals(3, s.level)
    }

    @Test fun weakSpots() {
        val w = WeakSpots.weak(listOf("a" to false, "a" to false, "b" to true, "b" to true, "c" to false))
        assertEquals(listOf("a"), w)
    }
}

class PromptTest {
    @Test fun llama3Template() {
        val p = Llama3Prompt.single("sys", "hi", "{\"")
        assertTrue(p.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nsys<|eot_id|>"))
        assertTrue(p.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n{\""))
    }

    @Test fun stemKeywordsDropFillerWords() {
        assertEquals("paise rupee", com.offlinetutor.domain.Prompts.keywords("How many paise make one rupee?"))
        assertEquals("3/4 percentage", com.offlinetutor.domain.Prompts.keywords("What is 3/4 written as a percentage?"))
    }

    @Test fun specialTokensStripped() {
        assertEquals("done", SpecialTokens.clean("done<|eot_id|>"))
        assertTrue(SpecialTokens.isEnd("<|eot_id|>"))
    }
}
