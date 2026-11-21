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
    /** Explanation language: "en", "tanglish", "benglish", "ta" or "bn". */
    val language: String = "en",
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** v6 (4b): analogy world picked at intake: village, city, cricket, cinema, kitchen, farm, festivals. */
    val world: String? = null,
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
    // ---- v2 (adaptive brain). All nullable so v1 rows stay valid after the migration. ----
    /** Stable concept id; null on v1 rows → derived from the title. */
    val conceptId: String? = null,
    /** NORMAL (null), MICRO_FIX or REVIEW. */
    val kind: String? = null,
    /** Prerequisite concept ids joined with '|'. */
    val prereqs: String? = null,
    /** MICRO_FIX: the misconception code being fixed. */
    val misconception: String? = null,
    /** Badge / explanation, e.g. "Stuck on Percentages because Fractions is shaky". */
    val reason: String? = null,
    /** MICRO_FIX: generated explanation + analogy (filled lazily when first opened). */
    val body: String? = null,
    /** MICRO_FIX / REVIEW: the concept this step repairs. */
    val targetConcept: String? = null,
) {
    val subtopicList: List<String> get() = subtopics.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val concept: String get() = conceptId?.takeIf { it.isNotBlank() } ?: com.offlinetutor.domain.Concepts.slug(title)
    val stepKind: StepKind get() = StepKind.entries.firstOrNull { it.name == kind } ?: StepKind.NORMAL
    val prereqList: List<String> get() = prereqs.orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
}

enum class StepKind { NORMAL, MICRO_FIX, REVIEW }

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
    // ---- v2 (adaptive brain). All nullable so v1 rows stay valid after the migration. ----
    /** Concept id of the roadmap step this question tests. */
    val concept: String? = null,
    /** Per-option misconception tags, JSON array aligned with [options]: null for the correct one. */
    val optionMeta: String? = null,
    /** 1c: true = "Sure", false = "Not sure", null = not asked. */
    val confident: Boolean? = null,
    /** Time from the question appearing to the answer being submitted. */
    val responseMs: Long? = null,
    /** Misconception code of the wrong option the student picked. */
    val misconception: String? = null,
    /** QUIZ (null), PROBE or FIX:<stepId>. */
    val purpose: String? = null,
    // ---- v3 (symbolic verifier) ----
    /** VERIFIED / FIXED / UNVERIFIABLE (model questions) or TEMPLATE (Kotlin-computed); null on older rows. */
    val verdict: String? = null,
    // ---- v5 (3b ready pool) ----
    /** Perceived wait: from the tap that asked for this question to it being ready. */
    val waitMs: Long? = null,
    /** "pool", "live", "instant-template", "instant-seed" or "seed" (fallback after failed generation). */
    val servedFrom: String? = null,
    /** Wall time spent generating this question (MCQ + tags, or template story); 0 for instant/seed items. */
    val genMs: Long? = null,
) {
    val optionList: List<String> get() = options.split(OPTION_SEP)

    companion object { const val OPTION_SEP = '\u001F' }
}

/** 1d: per-concept Elo-style mastery and the current difficulty level for that concept. */
@Entity(
    tableName = "concept_mastery",
    primaryKeys = ["profileId", "conceptId"],
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
)
data class ConceptMastery(
    val profileId: Long,
    val conceptId: String,
    val course: String,
    val rating: Double,
    val level: Int,
    val attempts: Int,
    val correct: Int,
    val fastWrongStreak: Int,
    val updatedAt: Long,
)

/** 1e: every time a tagged wrong option is chosen. */
@Entity(
    tableName = "misconception_hits",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("profileId", "conceptId")],
)
data class MisconceptionHit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val course: String,
    val conceptId: String,
    val code: String,
    val why: String,
    val questionId: Long,
    val confident: Boolean?,
    val createdAt: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
)

/** 1f: an untested prerequisite gets probe questions before backtracking decides. */
@Entity(
    tableName = "pending_probes",
    primaryKeys = ["profileId", "conceptId"],
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
)
data class PendingProbe(
    val profileId: Long,
    val conceptId: String,
    val course: String,
    val remaining: Int,
    val stuckConcept: String,
)

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
    /** v3: verifier verdict for MCQ runs (VERIFIED / FIXED / DISCARD / UNVERIFIABLE) or TEMPLATE. */
    val verdict: String? = null,
    /** v4 (2-fix-3): why the output was rejected ("parse: …", "truncated: …", "rule: …", "duplicate", …). */
    val failReason: String? = null,
)

/**
 * 3b: a ready question (validated, tagged, deduped when made) waiting to be served, per profile × concept × level.
 * Served items are moved to quiz_questions; the pool never holds attempts.
 */
@Entity(
    tableName = "question_pool",
    foreignKeys = [ForeignKey(Profile::class, ["id"], ["profileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["profileId", "concept", "level"])],
)
data class PoolItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val course: String,
    val concept: String,
    val subtopic: String,
    val level: Int,
    val stem: String,
    val normalizedStem: String,
    /** Four options joined with [QuizQuestion.OPTION_SEP]. */
    val options: String,
    val answerIndex: Int,
    val explanation: String,
    val optionMeta: String?,
    /** "llm", "template" or "peer" (5b). */
    val source: String,
    val verdict: String?,
    /** Wall time spent generating this item (MCQ + tags, or template story). */
    val genMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
)
