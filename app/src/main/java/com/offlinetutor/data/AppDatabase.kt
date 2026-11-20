package com.offlinetutor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Profile::class, RoadmapStep::class, QuizQuestion::class, ChatMessage::class, GenerationLog::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun roadmaps(): RoadmapDao
    abstract fun quiz(): QuizDao
    abstract fun chat(): ChatDao
    abstract fun logs(): LogDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tutor.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
