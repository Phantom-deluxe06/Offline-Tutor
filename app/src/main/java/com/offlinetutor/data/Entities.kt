package com.offlinetutor.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only student profile. The optional PIN is stored as a salted PBKDF2 hash. */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val grade: Int,
    /** Courses joined with '|'. */
    val courses: String,
    /** Explanation language: "en", "ta" or "bn". */
    val language: String = "en",
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val courseList: List<String> get() = courses.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val hasPin: Boolean get() = pinHash != null
}

@Entity(
    tableName = "roadmap_steps",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId", "course")],
)
data class RoadmapStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val course: String,
    val stepIndex: Int,
    val title: String,
    val goal: String,
    /** Subtopics joined with '|'. */
    val subtopics: String,
    val done: Boolean = false,
    /** "llm" or "template" (fallback when the model output could not be parsed). */
    val source: String = "llm",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val subtopicList: List<String> get() = subtopics.split('|').map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(
    tableName = "quiz_questions",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId"), Index("profileId", "course"), Index("normalizedStem")],
)
data class QuizQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val course: String,
    val subtopic: String,
    /** 1 = easy, 2 = medium, 3 = hard. */
    val difficulty: Int,
    val stem: String,
    val normalizedStem: String,
    /** Four options joined with '\u001F'. */
    val options: String,
    val answerIndex: Int,
    val explanation: String,
    /** "llm" or "seed". */
    val source: String,
    val chosenIndex: Int? = null,
    val correct: Boolean? = null,
    val askedAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null,
) {
    val optionList: List<String> get() = options.split(OPTION_SEP)

    companion object { const val OPTION_SEP = '\u001F' }
}

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId")],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val language: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One row per model run (load, roadmap, MCQ, chat, benchmark, eval). Exported as CSV for the report. */
@Entity(tableName = "generation_logs", indices = [Index("evalRun")])
data class GenerationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val task: String,
    val model: String,
    val evalRun: String? = null,
    val evalSubject: String? = null,
    val evalGrade: Int? = null,
    val difficulty: Int? = null,
    val promptChars: Int = 0,
    val promptTokensEst: Int = 0,
    val promptTokens: Int? = null,
    val generatedTokens: Int = 0,
    val maxNewTokens: Int = 0,
    val temperature: Float = 0f,
    val ttftMs: Long? = null,
    val wallMs: Long = 0,
    val decodeTokPerSec: Double? = null,
    val prefillTokPerSec: Double? = null,
    val peakRssKb: Long = -1,
    val rssKb: Long = -1,
    val deviceAvailKb: Long = -1,
    val thermalStatus: Int = 0,
    val thermalPolicy: String = "normal",
    val stopReason: String = "",
    val guardReason: String? = null,
    val validJson: Boolean? = null,
    val duplicate: Boolean? = null,
    val error: String? = null,
    val output: String = "",
    val rawStats: String? = null,
)
