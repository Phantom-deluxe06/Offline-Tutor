package com.offlinetutor

import com.offlinetutor.domain.CourseLevels
import com.offlinetutor.domain.GoalType
import com.offlinetutor.domain.LearnerContext
import com.offlinetutor.domain.Prompts
import com.offlinetutor.domain.StudyPlanner
import com.offlinetutor.domain.StudyPlanner.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudyPlanTest {
    private val today = LocalDate.of(2026, 11, 24).toEpochDay()

    private fun step(id: Long, course: String, index: Int, kind: Kind = Kind.STEP, done: Boolean = false) =
        StudyPlanner.Task(id, course, index, "T$id", kind, "c$id", done, "sub$id")

    private fun plan(tasks: List<StudyPlanner.Task>, minutes: Int = 20, correct: Map<String, Int> = emptyMap(),
                     levels: Map<String, Int> = emptyMap(), target: Long? = null, day: Long = today) =
        StudyPlanner.schedule(tasks, listOf("Maths", "Science"), levels, correct, day, minutes, target)

    @Test fun stepSplitsAcrossDaysAtTheDailyBudget() {
        val p = plan(listOf(step(1, "Maths", 0)))            // 30 min (no level = some basics) at 20 min/day
        assertEquals(listOf(20), p.days[0].slots.map { it.minutes })
        assertEquals(listOf(10), p.days[1].slots.map { it.minutes })
        assertEquals(1 to 2, p.days[0].slots[0].let { it.part to it.parts })
        assertEquals(today + 1, p.finishDay)
        assertEquals(30, p.totalMinutes)
    }

    @Test fun dueFixesAndReviewsComeFirst() {
        val tasks = listOf(step(1, "Maths", 0), step(2, "Maths", 1, Kind.REVIEW), step(3, "Maths", 2, Kind.FIX))
        val order = StudyPlanner.order(tasks, listOf("Maths"))
        assertEquals(listOf(3L, 2L, 1L), order.map { it.stepId })
    }

    @Test fun stepsTakeTurnsBetweenCoursesAndSkipDoneOnes() {
        val tasks = listOf(step(1, "Maths", 0, done = true), step(2, "Maths", 1), step(3, "Maths", 2), step(4, "Science", 0))
        assertEquals(listOf(2L, 4L, 3L), StudyPlanner.order(tasks, listOf("Maths", "Science")).map { it.stepId })
    }

    @Test fun correctAnswersShrinkTheStepSoThePlanChangesAfterAQuiz() {
        val tasks = listOf(step(1, "Maths", 0), step(2, "Maths", 1))
        val before = plan(tasks)
        val after = plan(tasks, correct = mapOf("c1" to 5))   // 30 - 5*3 = 15 min left
        assertEquals(60, before.totalMinutes)
        assertEquals(45, after.totalMinutes)
        assertEquals(StudyPlanner.MIN_LEFT, StudyPlanner.minutesLeft(step(1, "Maths", 0), null, mapOf("c1" to 50)))
    }

    @Test fun missedDaysRollForward() {
        val tasks = listOf(step(1, "Maths", 0), step(2, "Maths", 1))
        val yesterday = plan(tasks, day = today - 1)
        val todayPlan = plan(tasks, day = today)               // nothing was done yesterday
        assertEquals(yesterday.days[0].slots.map { it.stepId }, todayPlan.days[0].slots.map { it.stepId })
        assertEquals(today, todayPlan.days[0].epochDay)
    }

    @Test fun levelChangesStepTime() {
        assertEquals(45, StudyPlanner.minutesLeft(step(1, "Maths", 0), CourseLevels.BEGINNER, emptyMap()))
        assertEquals(20, StudyPlanner.minutesLeft(step(1, "Maths", 0), CourseLevels.CONFIDENT, emptyMap()))
        assertEquals(StudyPlanner.FIX_MINUTES, StudyPlanner.minutesLeft(step(1, "Maths", 0, Kind.FIX), CourseLevels.BEGINNER, emptyMap()))
    }

    @Test fun targetDateGivesOnTrackOrNeededMinutes() {
        val tasks = (1L..4L).map { step(it, "Maths", it.toInt()) }   // 4 x 30 = 120 min
        val relaxed = plan(tasks, target = today + 9)               // 120 / 10 days = 12 min/day needed
        assertTrue(relaxed.onTrack)
        assertEquals(12, relaxed.neededPerDay)
        val tight = plan(tasks, target = today + 2)                 // 120 / 3 = 40 min/day, but only 20 planned
        assertFalse(tight.onTrack)
        assertEquals(40, tight.neededPerDay)
        assertTrue(plan(tasks, target = today - 1).targetPassed)
    }

    @Test fun nothingLeftMeansAllDone() {
        val p = plan(listOf(step(1, "Maths", 0, done = true)))
        assertTrue(p.allDone)
        assertEquals(7, p.days.size)
        assertTrue(p.days.all { it.slots.isEmpty() })
    }

    @Test fun courseLevelsRoundTripAndIgnoreBrokenEntries() {
        val m = mapOf("Mathematics" to 1, "Social Science" to 3)
        assertEquals(m, CourseLevels.decode(CourseLevels.encode(m)))
        assertEquals(mapOf("Maths" to 2), CourseLevels.decode("Maths=2|bad|Science=9|=1"))
        assertEquals(null, CourseLevels.encode(emptyMap()))
    }

    @Test fun learnerContextGoesIntoTheRoadmapPrompt() {
        assertEquals("", LearnerContext.forPrompt(null, "  ", null, today, null))
        val ctx = LearnerContext.forPrompt(GoalType.EXAM, "Score above 90 in \"board\"\nexam", today + 70, today, CourseLevels.BEGINNER)
        assertTrue(ctx, ctx.contains("exam preparation"))
        assertTrue(ctx, ctx.contains("about 10 weeks"))
        assertTrue(ctx, ctx.contains("a beginner"))
        assertFalse(ctx.contains("\"") || ctx.contains("\nexam"))
        val prompt = Prompts.roadmap(8, "Mathematics", ctx)
        assertTrue(prompt.contains("learning Mathematics.\nThe student's goal (exam preparation)"))
        assertTrue(prompt.contains("\nOrder the steps from basics"))
        // No goal: the prompt is exactly the old one.
        assertEquals(Prompts.roadmap(8, "Mathematics"), Prompts.roadmap(8, "Mathematics", ""))
        assertFalse(Prompts.roadmap(8, "Mathematics").contains("    Order"))
    }
}
