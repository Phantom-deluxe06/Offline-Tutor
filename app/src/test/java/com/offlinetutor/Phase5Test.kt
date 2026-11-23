package com.offlinetutor

import com.offlinetutor.domain.BattleProtocol
import com.offlinetutor.domain.BattleProtocol.Msg
import com.offlinetutor.domain.BattleScoring
import com.offlinetutor.domain.ClassHeatmap
import com.offlinetutor.domain.ProblemTemplates
import com.offlinetutor.domain.ProgressQr
import com.offlinetutor.domain.QuestionShare
import com.offlinetutor.domain.TemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleProtocolTest {
    @Test fun everyMessageRoundTripsAndStaysSmall() {
        val q = (1..5).map { i -> BattleProtocol.BQ("Question $i about fractions of a dosa shared by 4 friends?", listOf("1/4", "1/2", "4", "3/4")) }
        val msgs = listOf(
            Msg.Hello("Ravi"), Msg.Quiz(q), Msg.RoundStart(2, 20_000), Msg.Answer(2, 1, 4200, true), Msg.Answer(2, 3, 9000, null),
            Msg.RoundResult(2, 0, listOf(5, 2, 0, 1), listOf(null, "FORMULA_SWAP", "OPERATION_SWAP", "PARTIAL_STEP"), 1395, true),
            Msg.Leaderboard(listOf("Ravi" to 2500, "Meena" to 1900)), Msg.End,
        )
        for (m in msgs) {
            val bytes = BattleProtocol.encode(m)
            assertTrue(bytes.size < BattleProtocol.MAX_BYTES / 4)
            assertEquals(m, BattleProtocol.decode(bytes))
        }
        // The quiz sent to joiners carries no answer key.
        assertTrue(!String(BattleProtocol.encode(Msg.Quiz(q))).contains("answer"))
        assertNull(BattleProtocol.decode("{\"v\":99,\"t\":\"END\"}".toByteArray()))
    }

    @Test fun scoringRewardsCorrectAndFast() {
        assertEquals(0, BattleScoring.points(false, 1000, 20_000))
        assertEquals(1500, BattleScoring.points(true, 0, 20_000))
        assertEquals(1250, BattleScoring.points(true, 10_000, 20_000))
        assertEquals(1000, BattleScoring.points(true, 25_000, 20_000))
    }

    @Test fun heatmapHeadline() {
        val codes = listOf(null, "FORMULA_SWAP", "OPERATION_SWAP", "SIGN_ERROR")
        val rounds = listOf(
            ClassHeatmap.Round(listOf(0, 0, 0, 1, 2), codes),
            ClassHeatmap.Round(listOf(0, 1, 1, 1, 0), codes),
            ClassHeatmap.Round(listOf(1, 1, 1, 0, 3), codes),
        )
        assertEquals("60% picked FORMULA_SWAP on Q2", ClassHeatmap.headline(rounds))
        assertEquals(0.2, ClassHeatmap.cells(rounds)["OPERATION_SWAP" to 0]!!, 1e-9)
    }
}

class QuestionShareTest {
    @Test fun wantsOnlyMissingSameGradeBandCapped() {
        val theirs = listOf(
            QuestionShare.Entry("a", "speed", 8, 1), QuestionShare.Entry("b", "speed", 8, 2),
            QuestionShare.Entry("c", "speed", 11, 2), QuestionShare.Entry("d", "fractions", 7, 1), QuestionShare.Entry("e", "x", 9, 3),
        )
        assertEquals(listOf("b", "d"), QuestionShare.wanted(setOf("a"), theirs, 8, cap = 2))
        assertEquals(theirs, QuestionShare.parseManifest(QuestionShare.manifestJson(theirs)))
    }

    @Test fun templateItemsAreRebuiltFromParameters() {
        val (p, text) = QuestionShare.rebuildTemplate("SPEED", 2, 42L, "Ravi rides {A} to the fair in {B}.")!!
        val again = ProblemTemplates.generate(TemplateType.SPEED, 2, kotlin.random.Random(42L))
        assertEquals(again.answer, p.answer) // the receiver recomputes the same key from the parameters
        assertTrue(text.startsWith("Ravi rides") && text.endsWith(p.question))
        // A tampered story (digits) falls back to Kotlin's own wording.
        val (_, safe) = QuestionShare.rebuildTemplate("SPEED", 2, 42L, "Ravi rides 5 km {A} in {B}.")!!
        assertEquals(p.defaultText, safe)
        assertNull(QuestionShare.rebuildTemplate("NOPE", 1, 1L, null))
    }
}

class ProgressQrTest {
    @Test fun fitsAndRoundTrips() {
        val mastery = (1..80).map { Triple("concept-number-$it-with-a-long-name", 900 + it, it % 7) }
        val p = ProgressQr.Progress("Ravi", mastery, mapOf("FORMULA_SWAP" to 3, "SIGN_ERROR" to 1))
        val s = ProgressQr.encode(p)
        assertTrue("${s.length} chars", s.length <= ProgressQr.MAX_CHARS)
        val d = ProgressQr.decode(s)
        assertNotNull(d)
        assertEquals("Ravi", d!!.nickname)
        assertEquals(p.misconceptions, d.misconceptions)
        val agg = ProgressQr.aggregate(listOf(d, d.copy(nickname = "Meena", misconceptions = mapOf("FORMULA_SWAP" to 1))))
        assertEquals(2 to 4, agg["FORMULA_SWAP"])
        assertNull(ProgressQr.decode("garbage"))
    }
}
