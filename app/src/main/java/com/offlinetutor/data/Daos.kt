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
