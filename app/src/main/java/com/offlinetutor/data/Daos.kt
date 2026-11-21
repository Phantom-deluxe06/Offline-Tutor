package com.offlinetutor.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles")
    suspend fun all(): List<Profile>

    @Query("SELECT * FROM profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: Long): Flow<Profile?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): Profile?

    @Insert
    suspend fun insert(p: Profile): Long

    @Update
    suspend fun update(p: Profile)

    @Delete
    suspend fun delete(p: Profile)
}

@Dao
interface RoadmapDao {
    @Query("SELECT * FROM roadmap_steps WHERE profileId = :profileId ORDER BY course, stepIndex")
    fun observe(profileId: Long): Flow<List<RoadmapStep>>

    @Query("SELECT * FROM roadmap_steps WHERE profileId = :profileId AND course = :course ORDER BY stepIndex")
    suspend fun forCourse(profileId: Long, course: String): List<RoadmapStep>

    @Query("DELETE FROM roadmap_steps WHERE profileId = :profileId AND course = :course")
    suspend fun clearCourse(profileId: Long, course: String)

    @Insert
    suspend fun insertAll(steps: List<RoadmapStep>)

    @Query("UPDATE roadmap_steps SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Transaction
    suspend fun replaceCourse(profileId: Long, course: String, steps: List<RoadmapStep>) {
        clearCourse(profileId, course)
        insertAll(steps)
    }

    @Query("SELECT * FROM roadmap_steps WHERE id = :id")
    suspend fun get(id: Long): RoadmapStep?

    @Insert
    suspend fun insert(step: RoadmapStep): Long

    @Update
    suspend fun update(step: RoadmapStep)

    @Query("UPDATE roadmap_steps SET stepIndex = stepIndex + 1 WHERE profileId = :profileId AND course = :course AND stepIndex >= :from")
    suspend fun shiftFrom(profileId: Long, course: String, from: Int)

    /** Inserts [step] at [index] in its course, moving later steps down by one. */
    @Transaction
    suspend fun insertAt(step: RoadmapStep, index: Int): Long {
        shiftFrom(step.profileId, step.course, index)
        return insert(step.copy(stepIndex = index))
    }

    @Query("SELECT * FROM roadmap_steps WHERE profileId = :profileId AND course = :course ORDER BY stepIndex")
    fun observeCourse(profileId: Long, course: String): Flow<List<RoadmapStep>>
}

@Dao
interface MasteryDao {
    @Query("SELECT * FROM concept_mastery WHERE profileId = :profileId AND conceptId = :conceptId")
    suspend fun get(profileId: Long, conceptId: String): ConceptMastery?

    @Query("SELECT * FROM concept_mastery WHERE profileId = :profileId")
    suspend fun all(profileId: Long): List<ConceptMastery>

    @Query("SELECT * FROM concept_mastery WHERE profileId = :profileId")
    fun observe(profileId: Long): Flow<List<ConceptMastery>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(m: ConceptMastery)
}

@Dao
interface MisconceptionDao {
    @Insert
    suspend fun insert(h: MisconceptionHit): Long

    @Query("SELECT * FROM misconception_hits WHERE profileId = :profileId AND conceptId = :conceptId ORDER BY createdAt")
    suspend fun forConcept(profileId: Long, conceptId: String): List<MisconceptionHit>

    @Query("SELECT * FROM misconception_hits WHERE profileId = :profileId ORDER BY createdAt")
    fun observe(profileId: Long): Flow<List<MisconceptionHit>>

    @Query("UPDATE misconception_hits SET resolved = 1 WHERE profileId = :profileId AND conceptId = :conceptId AND code = :code")
    suspend fun resolve(profileId: Long, conceptId: String, code: String)
}

@Dao
interface ProbeDao {
    @Query("SELECT * FROM pending_probes WHERE profileId = :profileId AND course = :course AND remaining > 0 LIMIT 1")
    suspend fun next(profileId: Long, course: String): PendingProbe?

    @Query("SELECT * FROM pending_probes WHERE profileId = :profileId AND conceptId = :conceptId")
    suspend fun get(profileId: Long, conceptId: String): PendingProbe?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PendingProbe)

    @Query("DELETE FROM pending_probes WHERE profileId = :profileId AND conceptId = :conceptId")
    suspend fun delete(profileId: Long, conceptId: String)
}

@Dao
interface QuizDao {
    @Insert
    suspend fun insert(q: QuizQuestion): Long

    @Query("SELECT * FROM quiz_questions WHERE id = :id")
    suspend fun get(id: Long): QuizQuestion?

    @Query("UPDATE quiz_questions SET chosenIndex = :chosen, correct = :correct, answeredAt = :at WHERE id = :id")
    suspend fun answer(id: Long, chosen: Int, correct: Boolean, at: Long)

    @Query("SELECT normalizedStem FROM quiz_questions WHERE profileId = :profileId")
    suspend fun allNormalizedStems(profileId: Long): List<String>

    @Query("SELECT stem FROM quiz_questions WHERE profileId = :profileId AND course = :course ORDER BY askedAt DESC LIMIT :limit")
    suspend fun recentStems(profileId: Long, course: String, limit: Int): List<String>

    @Query("SELECT * FROM quiz_questions WHERE profileId = :profileId AND course = :course AND correct IS NOT NULL ORDER BY answeredAt")
    suspend fun answeredForCourse(profileId: Long, course: String): List<QuizQuestion>

    @Query("SELECT * FROM quiz_questions WHERE profileId = :profileId AND correct IS NOT NULL ORDER BY answeredAt")
    fun observeAnswered(profileId: Long): Flow<List<QuizQuestion>>

    @Query("SELECT * FROM quiz_questions WHERE profileId = :profileId AND correct IS NULL ORDER BY askedAt DESC LIMIT 1")
    suspend fun pending(profileId: Long): QuizQuestion?

    @Query("DELETE FROM quiz_questions WHERE profileId = :profileId AND correct IS NULL")
    suspend fun dropUnanswered(profileId: Long)

    @Query(
        "UPDATE quiz_questions SET chosenIndex = :chosen, correct = :correct, answeredAt = :at, confident = :confident, " +
            "responseMs = :responseMs, misconception = :misconception WHERE id = :id",
    )
    suspend fun answerFull(id: Long, chosen: Int, correct: Boolean, at: Long, confident: Boolean?, responseMs: Long?, misconception: String?)

    @Query("SELECT * FROM quiz_questions WHERE profileId = :profileId AND concept = :concept AND correct IS NOT NULL ORDER BY answeredAt")
    suspend fun answeredForConcept(profileId: Long, concept: String): List<QuizQuestion>

    @Query("SELECT * FROM quiz_questions WHERE profileId = :profileId AND purpose = :purpose ORDER BY askedAt DESC LIMIT 1")
    suspend fun latestForPurpose(profileId: Long, purpose: String): QuizQuestion?

    @Query("SELECT * FROM quiz_questions WHERE profileId = :profileId AND correct IS NOT NULL ORDER BY answeredAt")
    suspend fun answered(profileId: Long): List<QuizQuestion>

    /** 3b metrics: the most recently served questions with their perceived wait. */
    @Query("SELECT * FROM quiz_questions WHERE servedFrom IS NOT NULL ORDER BY askedAt DESC LIMIT :n")
    suspend fun recentServed(n: Int): List<QuizQuestion>
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE profileId = :profileId ORDER BY createdAt, id")
    fun observe(profileId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE profileId = :profileId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(profileId: Long, limit: Int): List<ChatMessage>

    @Insert
    suspend fun insert(m: ChatMessage): Long

    @Query("DELETE FROM chat_messages WHERE profileId = :profileId")
    suspend fun clear(profileId: Long)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(l: GenerationLog): Long

    @Query("SELECT * FROM generation_logs ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<GenerationLog>>

    @Query("SELECT * FROM generation_logs ORDER BY createdAt")
    suspend fun all(): List<GenerationLog>

    @Query("SELECT * FROM generation_logs WHERE evalRun = :run ORDER BY createdAt")
    suspend fun forRun(run: String): List<GenerationLog>

    @Query("DELETE FROM generation_logs")
    suspend fun clear()
}

/** 3b: ready-question pool. */
@Dao
interface PoolDao {
    @Insert
    suspend fun insert(item: PoolItem): Long

    @Query("SELECT COUNT(*) FROM question_pool WHERE profileId = :profileId AND concept = :concept AND level = :level")
    suspend fun count(profileId: Long, concept: String, level: Int): Int

    @Query("SELECT COUNT(*) FROM question_pool WHERE profileId = :profileId")
    suspend fun countForProfile(profileId: Long): Int

    @Query("SELECT COUNT(*) FROM question_pool")
    fun observeTotal(): Flow<Int>

    @Query("SELECT * FROM question_pool WHERE profileId = :profileId AND concept = :concept AND level = :level ORDER BY createdAt LIMIT 1")
    suspend fun oldest(profileId: Long, concept: String, level: Int): PoolItem?

    @Query("SELECT normalizedStem FROM question_pool WHERE profileId = :profileId")
    suspend fun stems(profileId: Long): List<String>

    @Query("DELETE FROM question_pool WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM question_pool WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)

    @Query("SELECT * FROM question_pool ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PoolItem>
}
