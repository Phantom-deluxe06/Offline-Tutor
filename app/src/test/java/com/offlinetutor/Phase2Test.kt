package com.offlinetutor

import com.offlinetutor.domain.ExpressionVerifier
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

    @Test fun cleansLlmText() {
        val t = Prompts.cleanTemplateText("Word problem: \"An auto covers 120 km in 1.5 hours.\"\nAnswer: 80 km/h", "the speed in km/h")
        assertNotNull(t)
        assertTrue(t!!.startsWith("An auto covers 120 km"))
        assertTrue(t.endsWith("What is the speed in km/h?"))
        assertFalse(t.contains("80"))
        assertNull(Prompts.cleanTemplateText("Sure!", "x"))
        // On-device output: a preamble line that names the grade must not count as an extra number.
        val ohm = Prompts.cleanTemplateText("Here is a word problem for a Grade 8 student in India:\n\nA torch bulb is connected " +
            "to a 12.5 V power source. If the resistance of the bulb is 5 ohms, how many amperes of current will it draw?", "the current in amperes")!!
        assertTrue(ohm.startsWith("A torch bulb"))
        assertTrue(ProblemTemplates.noExtraNumbers(ohm, listOf("12.5 V", "5 ohm")))
        assertTrue(ProblemTemplates.textKeepsNumbers(ohm, listOf("12.5 V", "5 ohm")))
    }
}
