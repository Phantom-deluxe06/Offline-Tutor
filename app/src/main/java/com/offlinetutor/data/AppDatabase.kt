package com.offlinetutor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Profile::class, RoadmapStep::class, QuizQuestion::class, ChatMessage::class, GenerationLog::class,
        ConceptMastery::class, MisconceptionHit::class, PendingProbe::class, PoolItem::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun roadmaps(): RoadmapDao
    abstract fun quiz(): QuizDao
    abstract fun chat(): ChatDao
    abstract fun logs(): LogDao
    abstract fun mastery(): MasteryDao
    abstract fun misconceptions(): MisconceptionDao
    abstract fun probes(): ProbeDao
    abstract fun pool(): PoolDao

    companion object {
        // Migrations only, never a destructive fallback: a student's history must survive app updates.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tutor.db")
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
