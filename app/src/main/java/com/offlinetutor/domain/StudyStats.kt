package com.offlinetutor.domain

import java.util.Calendar

/** Day-based study stats (local time zone) for the streak, the daily goal and the weekly chart. */
object StudyStats {
    const val DAILY_GOAL = 5

    private fun dayIndex(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        // Days since epoch in local time: stable across DST because we use the calendar fields.
        return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun daysAgo(n: Int, now: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -n) }
        return dayIndex(c.timeInMillis)
    }

    fun todayCount(answeredAt: List<Long>, now: Long = System.currentTimeMillis()): Int {
        val today = dayIndex(now)
        return answeredAt.count { dayIndex(it) == today }
    }

    /** Consecutive days with at least one answer, ending today (or yesterday if nothing yet today). */
    fun streak(answeredAt: List<Long>, now: Long = System.currentTimeMillis()): Int {
        val days = answeredAt.map { dayIndex(it) }.toHashSet()
        var start = if (daysAgo(0, now) in days) 0 else 1
        var n = 0
        while (daysAgo(start + n, now) in days) n++
        return n
    }

    /** Answers per day for the last 7 days, oldest first (last = today). */
    fun last7(answeredAt: List<Long>, now: Long = System.currentTimeMillis()): List<Int> {
        val counts = answeredAt.groupingBy { dayIndex(it) }.eachCount()
        return (6 downTo 0).map { counts[daysAgo(it, now)] ?: 0 }
    }

    /** Short weekday labels for [last7], localised. */
    fun last7Labels(now: Long = System.currentTimeMillis()): List<String> {
        val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        return (6 downTo 0).map {
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -it) }
            fmt.format(c.time).take(3)
        }
    }
}
