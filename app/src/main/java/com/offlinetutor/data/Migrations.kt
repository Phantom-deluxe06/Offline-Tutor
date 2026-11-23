package com.offlinetutor.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Additive migrations only (no destructive fallback). SQL is copied from Room's exported
 * schemas (app/schemas/.../N.json) so the migrated database matches what Room validates.
 */
object Migrations {
    /** Phase 1 (adaptive brain): nullable columns on two tables + three new tables. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (c in listOf("conceptId", "kind", "prereqs", "misconception", "reason", "body", "targetConcept")) {
                db.execSQL("ALTER TABLE `roadmap_steps` ADD COLUMN `$c` TEXT")
            }
            for ((c, type) in listOf(
                "concept" to "TEXT", "optionMeta" to "TEXT", "confident" to "INTEGER",
                "responseMs" to "INTEGER", "misconception" to "TEXT", "purpose" to "TEXT",
            )) {
                db.execSQL("ALTER TABLE `quiz_questions` ADD COLUMN `$c` $type")
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `concept_mastery` (`profileId` INTEGER NOT NULL, `conceptId` TEXT NOT NULL, " +
                    "`course` TEXT NOT NULL, `rating` REAL NOT NULL, `level` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
                    "`correct` INTEGER NOT NULL, `fastWrongStreak` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`profileId`, `conceptId`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `misconception_hits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profileId` INTEGER NOT NULL, `course` TEXT NOT NULL, `conceptId` TEXT NOT NULL, `code` TEXT NOT NULL, " +
                    "`why` TEXT NOT NULL, `questionId` INTEGER NOT NULL, `confident` INTEGER, `createdAt` INTEGER NOT NULL, " +
                    "`resolved` INTEGER NOT NULL, FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_misconception_hits_profileId_conceptId` " +
                    "ON `misconception_hits` (`profileId`, `conceptId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_probes` (`profileId` INTEGER NOT NULL, `conceptId` TEXT NOT NULL, " +
                    "`course` TEXT NOT NULL, `remaining` INTEGER NOT NULL, `stuckConcept` TEXT NOT NULL, " +
                    "PRIMARY KEY(`profileId`, `conceptId`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

    /** Phase 2 (symbolic verifier): verdict columns on questions and generation logs. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `quiz_questions` ADD COLUMN `verdict` TEXT")
            db.execSQL("ALTER TABLE `generation_logs` ADD COLUMN `verdict` TEXT")
        }
    }

    // 2-fix-3: failure reason per generation log row.
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `generation_logs` ADD COLUMN `failReason` TEXT")
        }
    }

    // 3b: ready-question pool + perceived-wait columns. SQL copied from the exported schema 5.json.
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `quiz_questions` ADD COLUMN `waitMs` INTEGER")
            db.execSQL("ALTER TABLE `quiz_questions` ADD COLUMN `servedFrom` TEXT")
            db.execSQL("ALTER TABLE `quiz_questions` ADD COLUMN `genMs` INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `question_pool` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`profileId` INTEGER NOT NULL, `course` TEXT NOT NULL, `concept` TEXT NOT NULL, `subtopic` TEXT NOT NULL, " +
                    "`level` INTEGER NOT NULL, `stem` TEXT NOT NULL, `normalizedStem` TEXT NOT NULL, `options` TEXT NOT NULL, " +
                    "`answerIndex` INTEGER NOT NULL, `explanation` TEXT NOT NULL, `optionMeta` TEXT, `source` TEXT NOT NULL, " +
                    "`verdict` TEXT, `genMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_question_pool_profileId_concept_level` ON `question_pool` (`profileId`, `concept`, `level`)")
        }
    }

    // 4b: analogy world per profile.
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `world` TEXT")
        }
    }

    // 5b: shareable template parameters on pool items.
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `question_pool` ADD COLUMN `spec` TEXT")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
