package com.offlinetutor

import com.offlinetutor.domain.KuttyScoring
import com.offlinetutor.domain.Textbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuttyTest {
    private val points = listOf(
        "Plants make their own food using sunlight",
        "Leaves contain chlorophyll which absorbs light",
        "Carbon dioxide and water are the raw materials",
        "Oxygen is released as a by-product",
    )

    @Test fun scoresCoveredAndMissedPoints() {
        val s = KuttyScoring.score("Plants use sunlight to make food. The green chlorophyll in leaves absorbs the light.", points)
        assertEquals(4, s.total)
        assertEquals(2, s.covered)
        assertFalse(s.points[3].covered)
        assertTrue("oxygen" in s.points[3].missing)
    }

    @Test fun parsesPointsAndQuestion() {
        val pts = KuttyScoring.parsePoints("1. Plants make food from light.\n2. Leaves have chlorophyll.\n3. They need water and carbon dioxide.\n4. Oxygen comes out.")
        assertEquals(4, pts!!.size)
        assertNull(KuttyScoring.parsePoints("1. Too few."))
        assertEquals("But why are leaves green?", KuttyScoring.parseQuestion("Hmm! But why are leaves green? I like trees."))
    }
}

class TextbookTest {
    @Test fun splitsPassagesAndBuildsQuery() {
        val para = "Photosynthesis is the process by which green plants make food. ".repeat(12)
        val parts = Textbook.passages("Chapter 1\n\n$para\n\nShort.")
        assertTrue(parts.isNotEmpty() && parts.all { it.length <= 500 })
        assertEquals("photosynthesi* OR green* OR plant*", Textbook.matchQuery("Photosynthesis in green plants"))
        assertNull(Textbook.matchQuery("in the of"))
    }
}
