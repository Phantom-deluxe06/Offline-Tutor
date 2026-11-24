package com.offlinetutor.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** What the learner is working towards (picked at intake, editable in Settings). */
enum class GoalType(val code: String, val promptText: String) {
    EXAM("exam", "exam preparation"),
    SKILL("skill", "skill development"),
    CAREER("career", "career guidance"),
    CERTIFICATION("certification", "a certification");

    companion object {
        fun of(code: String?): GoalType? = entries.firstOrNull { it.code == code }
    }
}

/** Self-rated current level per course. */
object CourseLevels {
    const val BEGINNER = 1
    const val SOME = 2
    const val CONFIDENT = 3

    /** "Mathematics=1|Science=3" -> map. Unknown or broken entries are skipped. */
    fun decode(s: String?): Map<String, Int> = s.orEmpty().split('|').mapNotNull { part ->
        val i = part.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val level = part.substring(i + 1).toIntOrNull()?.takeIf { it in BEGINNER..CONFIDENT } ?: return@mapNotNull null
        part.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { it to level }
    }.toMap()

    fun encode(levels: Map<String, Int>): String? =
        levels.entries.filter { it.value in BEGINNER..CONFIDENT }.joinToString("|") { "${it.key.replace("|", " ").replace("=", " ")}=${it.value}" }
            .ifEmpty { null }

    fun promptText(level: Int) = when (level) {
        BEGINNER -> "a beginner"
        CONFIDENT -> "already confident with the basics"
        else -> "knows some of the basics"
    }
}

/** Learner context added to the roadmap prompt. Empty when the learner gave no goal, date or level. */
object LearnerContext {
    fun forPrompt(goalType: GoalType?, goalText: String?, targetDay: Long?, todayDay: Long, level: Int?): String {
        val lines = ArrayList<String>()
        val text = goalText.orEmpty().replace(Regex("[\\r\\n\"]+"), " ").trim().take(120)
        when {
            goalType != null && text.isNotEmpty() -> lines += "The student's goal (${goalType.promptText}): $text."
            goalType != null -> lines += "The student's goal is ${goalType.promptText}."
            text.isNotEmpty() -> lines += "The student's goal: $text."
        }
        if (targetDay != null && targetDay >= todayDay) {
            val weeks = ((targetDay - todayDay) / 7).coerceAtLeast(0)
            lines += "Target date: ${StudyPlanner.format(targetDay, Locale.ENGLISH)} (" + (if (weeks == 0L) "less than a week" else "about $weeks weeks") + " from now)."
        }
        if (level != null) lines += "The student rates themselves as ${CourseLevels.promptText(level)} in this course."
        if (lines.isEmpty()) return ""
        lines += when (level) {
            CourseLevels.BEGINNER -> "Fit the steps to this goal and start from the very basics."
            CourseLevels.CONFIDENT -> "Fit the steps to this goal and skip the most basic topics."
            else -> "Fit the steps to this goal."
        }
        return lines.joinToString("\n")
    }
}

/**
 * The time-based study plan. Kotlin, not the model, decides what goes on each day: due micro-fixes and
 * reviews first, then the next roadmap steps (taking turns between courses), split into daily minute
 * budgets until everything is scheduled. The plan is always recomputed from today and from the quiz
 * history, so unfinished work rolls forward and every answered quiz question changes the plan.
 */
object StudyPlanner {
    const val DEFAULT_MINUTES = 20
    val MINUTE_CHOICES = listOf(10, 20, 30, 45, 60)
    const val FIX_MINUTES = 10
    const val REVIEW_MINUTES = 15
    /** Each correct answer on a step's concept takes this much off the time left for that step. */
    const val MINUTES_PER_CORRECT = 3
    const val MIN_LEFT = 5
    /** Do not start a new piece of work in fewer minutes than this; move it to the next day. */
    private const val MIN_SLOT = 5
    private const val MAX_DAYS = 730

    enum class Kind { FIX, REVIEW, STEP }

    data class Task(
        val stepId: Long, val course: String, val stepIndex: Int, val title: String, val kind: Kind,
        val concept: String, val done: Boolean, val subtopic: String,
    )

    data class Slot(
        val stepId: Long, val course: String, val title: String, val kind: Kind, val subtopic: String,
        val minutes: Int, val part: Int, val parts: Int,
    )

    data class Day(val epochDay: Long, val slots: List<Slot>) {
        val minutes: Int get() = slots.sumOf { it.minutes }
    }

    data class Plan(
        val days: List<Day>,
        val totalMinutes: Int,
        /** Last day with planned work at this pace; null when nothing is left. */
        val finishDay: Long?,
        val targetDay: Long?,
        /** Minutes a day needed to finish by the target date; null without a (future) target. */
        val neededPerDay: Int?,
    ) {
        val allDone: Boolean get() = totalMinutes == 0
        val targetPassed: Boolean get() = targetDay != null && days.isNotEmpty() && targetDay < days.first().epochDay
        val onTrack: Boolean get() = targetDay == null || finishDay == null || finishDay <= targetDay
    }

    fun stepMinutes(level: Int?): Int = when (level) {
        CourseLevels.BEGINNER -> 45
        CourseLevels.CONFIDENT -> 20
        else -> 30
    }

    /** Minutes still needed for a task, after the correct answers already given on its concept. */
    fun minutesLeft(task: Task, level: Int?, correctByConcept: Map<String, Int>): Int {
        val base = when (task.kind) {
            Kind.FIX -> FIX_MINUTES
            Kind.REVIEW -> REVIEW_MINUTES
            Kind.STEP -> stepMinutes(level)
        }
        if (task.kind != Kind.STEP) return base
        val earned = (correctByConcept[task.concept] ?: 0) * MINUTES_PER_CORRECT
        return (base - earned).coerceAtLeast(MIN_LEFT)
    }

    /** Due fixes and reviews first (roadmap order), then normal steps taking turns between courses. */
    fun order(tasks: List<Task>, courseOrder: List<String>): List<Task> {
        val open = tasks.filter { !it.done }
        val rank = courseOrder.withIndex().associate { it.value to it.index }
        val repairs = open.filter { it.kind != Kind.STEP }
            .sortedWith(compareBy({ if (it.kind == Kind.FIX) 0 else 1 }, { rank[it.course] ?: Int.MAX_VALUE }, { it.stepIndex }))
        val perCourse = open.filter { it.kind == Kind.STEP }.groupBy { it.course }
            .toSortedMap(compareBy<String> { rank[it] ?: Int.MAX_VALUE }.thenBy { it })
            .values.map { it.sortedBy { t -> t.stepIndex }.toMutableList() }
        val steps = ArrayList<Task>()
        while (perCourse.any { it.isNotEmpty() }) perCourse.forEach { q -> if (q.isNotEmpty()) steps += q.removeAt(0) }
        return repairs + steps
    }

    fun schedule(
        tasks: List<Task>,
        courseOrder: List<String>,
        levels: Map<String, Int>,
        correctByConcept: Map<String, Int>,
        today: Long,
        minutesPerDay: Int,
        targetDay: Long?,
        showDays: Int = 7,
    ): Plan {
        val perDay = minutesPerDay.coerceIn(5, 600)
        val queue = order(tasks, courseOrder).map { it to minutesLeft(it, levels[it.course], correctByConcept) }
        val total = queue.sumOf { it.second }

        // Fill day by day; a task that does not fit continues on the next day.
        val raw = ArrayList<Pair<Long, Pair<Task, Int>>>()
        var day = today
        var room = perDay
        for ((task, minutes) in queue) {
            var left = minutes
            while (left > 0) {
                if (room < MIN_SLOT && room < left) { day++; room = perDay }
                if (day - today > MAX_DAYS) break
                val take = minOf(left, room)
                raw += day to (task to take)
                left -= take
                room -= take
                if (room == 0) { day++; room = perDay }
            }
        }
        val parts = raw.groupingBy { it.second.first.stepId }.eachCount()
        val seen = HashMap<Long, Int>()
        val slots = raw.map { (d, tm) ->
            val (t, m) = tm
            val n = (seen[t.stepId] ?: 0) + 1
            seen[t.stepId] = n
            d to Slot(t.stepId, t.course, t.title, t.kind, t.subtopic, m, n, parts[t.stepId] ?: 1)
        }
        val byDay = slots.groupBy({ it.first }, { it.second })
        val days = (0 until showDays).map { i -> Day(today + i, byDay[today + i].orEmpty()) }
        val finish = slots.maxOfOrNull { it.first }
        val needed = targetDay?.takeIf { it >= today && total > 0 }?.let { t ->
            ceil(total.toDouble() / (t - today + 1)).toInt()
        }
        return Plan(days, total, finish, targetDay, needed)
    }

    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun format(epochDay: Long, locale: Locale = Locale.getDefault()): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))

    fun formatShort(epochDay: Long, locale: Locale = Locale.getDefault()): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d", locale))
}
