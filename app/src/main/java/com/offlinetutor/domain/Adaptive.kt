package com.offlinetutor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * 2-up / 1-down staircase: two correct answers in a row at a level move the student up,
 * one wrong answer moves them down. It converges near ~70% accuracy, the zone where
 * questions are challenging but not discouraging.
 */
object AdaptiveDifficulty {
    const val EASY = 1
    const val MEDIUM = 2
    const val HARD = 3

    data class State(val level: Int = EASY, val streak: Int = 0)

    fun step(s: State, correct: Boolean): State =
        if (correct) {
            if (s.streak + 1 >= 2 && s.level < HARD) State(s.level + 1, 0) else State(s.level, min(s.streak + 1, 2))
        } else {
            State(max(EASY, s.level - 1), 0)
        }

    /** Replays answers (difficulty, correct) in order; each answer is taken at the level it was asked. */
    fun fromHistory(history: List<Pair<Int, Boolean>>): State =
        history.fold(State()) { s, (difficulty, correct) ->
            val at = if (difficulty == s.level) s else State(difficulty.coerceIn(EASY, HARD), 0)
            step(at, correct)
        }
}

data class SubtopicStat(val subtopic: String, val attempts: Int, val correct: Int) {
    val accuracy: Double get() = if (attempts == 0) 0.0 else correct.toDouble() / attempts
}

object WeakSpots {
    fun stats(answers: List<Pair<String, Boolean>>): List<SubtopicStat> =
        answers.groupBy({ it.first }, { it.second })
            .map { (k, v) -> SubtopicStat(k, v.size, v.count { it }) }

    /** Subtopics with >= 2 attempts and < 60% accuracy, weakest first. */
    fun weak(answers: List<Pair<String, Boolean>>, limit: Int = 3): List<String> =
        stats(answers).filter { it.attempts >= 2 && it.accuracy < 0.6 }
            .sortedBy { it.accuracy }.take(limit).map { it.subtopic }
}
