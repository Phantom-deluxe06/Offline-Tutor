package com.offlinetutor

import com.offlinetutor.domain.ExpressionVerifier
import com.offlinetutor.domain.FailReasons
import com.offlinetutor.domain.McqParser
import com.offlinetutor.domain.ProblemTemplates
import com.offlinetutor.domain.Prompts
import com.offlinetutor.domain.TemplateType
import com.offlinetutor.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VerifierTest {
    @Test fun evaluatesCommonNotations() {
        assertEquals(80.0, ExpressionVerifier.evaluate("120/1.5")!!, 1e-9)
        assertEquals(84.0, ExpressionVerifier.evaluate("12 x 7")!!, 1e-9)
        assertEquals(84.0, ExpressionVerifier.evaluate("12 × 7")!!, 1e-9)
        assertEquals(80.0, ExpressionVerifier.evaluate("120 ÷ 1.5")!!, 1e-9)
        assertEquals(60.0, ExpressionVerifier.evaluate("15% of 400")!!, 1e-9)
        assertEquals(1500.0, ExpressionVerifier.evaluate("1,200 + 300")!!, 1e-9)
        assertEquals(5.0, ExpressionVerifier.evaluate("sqrt(25)")!!, 1e-9)
    }

    @Test fun wordsOrGarbageAreNotEvaluated() {
        assertNull(ExpressionVerifier.evaluate("speed = distance / time"))
        assertNull(ExpressionVerifier.evaluate(""))
        assertNull(ExpressionVerifier.evaluate(null))
        assertNull(ExpressionVerifier.evaluate("5/0"))
    }

    @Test fun optionValues() {
        assertEquals(84.0, ExpressionVerifier.optionValue("84 loaves")!!, 1e-9)
        assertEquals(1200.0, ExpressionVerifier.optionValue("Rs 1,200")!!, 1e-9)
        assertEquals(60.0, ExpressionVerifier.optionValue("₹60")!!, 1e-9)
        assertEquals(0.75, ExpressionVerifier.optionValue("3/4")!!, 1e-9)
        assertNull(ExpressionVerifier.optionValue("Photosynthesis"))
    }

    @Test fun verifiedFixedDiscardUnverifiable() {
        val speed = listOf("80 km/h", "180 km/h", "121.5 km/h", "118.5 km/h")
        assertEquals(Verdict.VERIFIED, ExpressionVerifier.verify("120/1.5", speed, 0).verdict)
        // On-device bakery question: key on "12", the arithmetic says 84.
        val fixed = ExpressionVerifier.verify("12*7", listOf("36", "24", "84", "12"), 3)
        assertEquals(Verdict.FIXED, fixed.verdict)
        assertEquals(2, fixed.answer)
        assertEquals(Verdict.DISCARD, ExpressionVerifier.verify("12*7", listOf("36", "24", "8", "12"), 3).verdict)
        assertEquals(Verdict.UNVERIFIABLE, ExpressionVerifier.verify(null, speed, 0).verdict)
        assertEquals(Verdict.UNVERIFIABLE, ExpressionVerifier.verify("2+2", listOf("Delhi", "Mumbai", "Chennai", "Kolkata"), 0).verdict)
        // Two options equal to the result → ambiguous → discard.
        assertEquals(Verdict.DISCARD, ExpressionVerifier.verify("40*2", listOf("80", "80.0", "40", "120"), 0).verdict)
    }

    @Test fun roundedOptionsAreAccepted() {
        assertEquals(Verdict.VERIFIED, ExpressionVerifier.verify("400/15*100", listOf("2666.67", "60", "385", "6"), 0).verdict)
    }
}

class FailReasonsTest {
    @Test fun classifiesEveryRejection() {
        assertNull(FailReasons.forMcq(null, "JSON_COMPLETE", null, false))
        assertEquals("duplicate", FailReasons.forMcq(null, "JSON_COMPLETE", null, true))
        // Truncation and the repetition guard explain a parse failure better than the parse error itself.
        assertEquals(FailReasons.TRUNCATED, FailReasons.forMcq("no JSON object", "MAX_TOKENS", null, false))
        assertEquals("repetition: repeated 6-gram", FailReasons.forMcq("bad JSON: x", "REPETITION", "repeated 6-gram", false))
        assertEquals("parse: no JSON object", FailReasons.forMcq("no JSON object", "END", null, false))
        assertEquals("rule: need 4 options, got 3", FailReasons.forMcq("need 4 options, got 3", "JSON_COMPLETE", null, false))
        assertEquals(McqParser.RULE_ARITHMETIC, FailReasons.forMcq(McqParser.RULE_ARITHMETIC, "JSON_COMPLETE", null, false))
        assertEquals("truncated", FailReasons.category(FailReasons.TRUNCATED))
    }
}

class McqPromptSwitchTest {
    @Test fun expressionFieldOnlyForTheAbEval() {
        val plain = Prompts.mcq(8, "Science", "Photosynthesis", 2, emptyList(), emptyList())
        assertFalse(plain.contains("expression"))
        val ab = Prompts.mcq(8, "Science", "Photosynthesis", 2, emptyList(), emptyList(), withExpression = true)
        assertTrue(ab.contains("\"expression\":\"\"}"))
    }

    @Test fun conceptualRuleAndArithmeticRejection() {
        val p = Prompts.mcq(8, "Mathematics", "Fractions", 2, emptyList(), emptyList(), conceptual = true)
        assertTrue(p.contains("Do not ask a calculation question"))
        // The generator rejects all-numeric option sets when templates own arithmetic.
        assertTrue(listOf("12", "Rs 40", "3/4", "0.5 km").all { ExpressionVerifier.optionValue(it) != null })
        assertFalse(listOf("12", "Rs 40", "a third", "0.5 km").all { ExpressionVerifier.optionValue(it) != null })
    }
}

class TemplatesTest {
    /** Every template's Kotlin answer is independently confirmed by exp4j, and distractors never collide. */
    @Test fun everyTemplateIsSelfConsistent() {
        for (type in TemplateType.entries) for (level in 1..3) for (seed in 0 until 25) {
            val p = ProblemTemplates.generate(type, level, Random(seed * 31 + level))
            val options = listOf(p.answer) + p.wrong.map { it.first }
            val where = "$type L$level seed $seed: $options"
            assertEquals(where, 4, options.toSet().size)
            assertEquals(where, Verdict.VERIFIED, ExpressionVerifier.verify(p.expression, options, 0).verdict)
            assertTrue(where, p.wrong.all { it.second.why.isNotBlank() })
            assertTrue(where, ProblemTemplates.textKeepsNumbers(p.defaultText, p.givens))
        }
    }

    @Test fun diagnosticFormKeepsTags() {
        val d = ProblemTemplates.generate(TemplateType.SPEED, 1, Random(1)).toDiag("speed")
        assertEquals(0, d.answer)
        assertTrue(d.options.drop(1).all { it.code != null })
    }

    @Test fun topicMatchingAvoidsFalsePositives() {
        assertEquals(TemplateType.SPEED, ProblemTemplates.forTopic("Speed, distance and time", "Mathematics"))
        assertEquals(TemplateType.AREA_PERIMETER, ProblemTemplates.forTopic("Area of a rectangle", "Mathematics"))
        assertEquals(TemplateType.OHMS_LAW, ProblemTemplates.forTopic("Ohm's law", "Physics"))
        assertEquals(TemplateType.OHMS_LAW, ProblemTemplates.forTopic("Electricity", "Science"))
        assertNull(ProblemTemplates.forTopic("Rural and urban area", "Social Science"))
        assertNull(ProblemTemplates.forTopic("Unity in diversity", "Mathematics"))
        assertNull(ProblemTemplates.forTopic("Current affairs", "Mathematics"))
        assertNull(ProblemTemplates.forTopic("Speed of a story's plot", "English"))
    }

    @Test fun llmTextMustKeepNumbersAndAddNone() {
        val givens = listOf("120 km", "1.5 hours")
        assertTrue(ProblemTemplates.textKeepsNumbers("An auto covers 120 km in 1.5 hours. What is its speed?", givens))
        assertFalse(ProblemTemplates.textKeepsNumbers("An auto covers 12 km in 1.5 hours. What is its speed?", givens))
        assertFalse(ProblemTemplates.textKeepsNumbers("An auto covers 120 km in 1.55 hours. What is its speed?", givens))
        assertTrue(ProblemTemplates.noExtraNumbers("An auto covers 120 km in 1.5 hours. What is its speed?", givens))
        assertFalse(ProblemTemplates.noExtraNumbers("2 friends share an auto for 120 km in 1.5 hours. Speed?", givens))
        // Leaking the answer counts as an extra number.
        assertFalse(ProblemTemplates.noExtraNumbers("An auto covers 120 km in 1.5 hours at 80 km/h. Speed?", givens))
    }

    @Test fun placeholderStoriesAreAcceptedOnlyWhenClean() {
        val ok = "Rohan rides an auto-rickshaw {A} to the station, and the ride takes {B}."
        assertEquals(ok, ProblemTemplates.acceptStory(ok, 2))
        // Opener on its own line (measured on-device) is dropped; Kotlin owns the question, so a model question is cut.
        assertEquals("A torch bulb is connected to a {A} supply through a {B} wire.",
            ProblemTemplates.acceptStory("Here is a short setting for a middle-school student:\n\nA torch bulb is connected to a {A} " +
                "supply through a {B} wire. What current flows?", 2))
        assertNull(ProblemTemplates.acceptStory("Rohan rides {A} to the station.", 2))                       // {B} missing
        assertNull(ProblemTemplates.acceptStory("Rohan rides {A}, then {A} again, in {B}.", 2))              // {A} twice
        assertNull(ProblemTemplates.acceptStory("Rohan rides {A} in {B} with {C} friends.", 2))              // unknown placeholder
        assertNull(ProblemTemplates.acceptStory("Rohan rides {A} in {B} with 2 friends.", 2))                // digit
        assertNull(ProblemTemplates.acceptStory("Rohan rides {A} in {B} with five friends.", 2))             // number word
        assertNull(ProblemTemplates.acceptStory("Rohan rides {A} in {B}, twice as fast as usual.", 2))       // number word
        assertNull(ProblemTemplates.acceptStory("Sure!", 1))
        assertEquals("contains the number word \"half\"", ProblemTemplates.storyProblem("He walks {A} in half the time.", 1))
    }

    @Test fun kotlinOwnsNumbersAndQuestion() {
        for (type in TemplateType.entries) for (level in 1..3) {
            val p = ProblemTemplates.generate(type, level, Random(level))
            // Kotlin's own story must pass the same acceptance rules as the model's.
            assertEquals("$type L$level", p.defaultStory, ProblemTemplates.acceptStory(p.defaultStory, p.slots.size))
            val text = p.textFor(p.defaultStory)
            assertTrue(text.endsWith(p.question))
            assertTrue(ProblemTemplates.textKeepsNumbers(text, p.givens))
            // The answer's number never appears unless it is also a given (e.g. equal by chance).
            val num = Regex("\\d+(?:\\.\\d+)?")
            val answerNum = num.find(p.answer)!!.value
            val allowed = (p.givens + p.question).flatMap { g -> num.findAll(g).map { it.value }.toList() }
            if (answerNum !in allowed) assertFalse("$type L$level leaks the answer: $text", num.findAll(text).any { it.value == answerNum })
        }
    }

    @Test fun storyPromptHasNoDigits() {
        for (type in TemplateType.entries) for (grade in listOf(6, 10)) {
            val prompt = Prompts.templateStory(grade, ProblemTemplates.generate(type, 2, Random(3)))
            assertFalse("$type prompt has digits: $prompt", prompt.any { it.isDigit() })
        }
    }
}
