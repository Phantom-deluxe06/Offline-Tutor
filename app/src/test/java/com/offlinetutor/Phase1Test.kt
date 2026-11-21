package com.offlinetutor

import androidx.sqlite.db.SupportSQLiteDatabase
import com.offlinetutor.data.Migrations
import com.offlinetutor.data.RoadmapRepository
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.AttemptClass
import com.offlinetutor.domain.Backtrack
import com.offlinetutor.domain.BacktrackPlanner
import com.offlinetutor.domain.Confidence
import com.offlinetutor.domain.DiagParse
import com.offlinetutor.domain.DiagnosticParser
import com.offlinetutor.domain.HealingPolicy
import com.offlinetutor.domain.MasteryModel
import com.offlinetutor.domain.Misconception
import com.offlinetutor.domain.MisconceptionTaxonomy
import com.offlinetutor.domain.NodeState
import com.offlinetutor.domain.OptionMeta
import com.offlinetutor.domain.PrereqGraph
import com.offlinetutor.domain.Prompts
import com.offlinetutor.domain.RoadmapParser
import com.offlinetutor.domain.SkillTreeRules
import com.offlinetutor.llm.TokenEstimator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.random.Random

class TaxonomyTest {
    @Test fun assetHasAllEightCodes() {
        val json = File("src/main/assets/misconceptions.json").readText()
        val items = MisconceptionTaxonomy.parse(json)
        assertEquals(Misconception.entries.toSet(), items.map { it.code }.toSet())
    }

    @Test fun lenientCodeLookup() {
        assertEquals(Misconception.FORMULA_SWAP, Misconception.of("formula swap"))
        assertEquals(Misconception.SIGN_ERROR, Misconception.of(" Sign-Error "))
        assertEquals(null, Misconception.of("MADE_UP_CODE"))
    }

    @Test fun confidenceClasses() {
        assertEquals(AttemptClass.DEEP_MISCONCEPTION, Confidence.classify(false, true))
        assertEquals(AttemptClass.FRAGILE, Confidence.classify(true, false))
        assertEquals(AttemptClass.MASTERY, Confidence.classify(true, true))
        assertEquals(AttemptClass.GAP, Confidence.classify(false, false))
        assertEquals(AttemptClass.UNRATED, Confidence.classify(true, null))
    }
}

class DiagnosticParserTest {
    private val keyed = """{"q":"A car goes 120 km in 1.5 hours. What is its speed?","concept":"speed","difficulty":"medium",
        "A":{"t":"80 km/h","correct":true},
        "B":{"t":"180 km/h","misconception":"FORMULA_SWAP","why":"used d x t instead of d / t"},
        "C":{"t":"121.5 km/h","misconception":"OPERATION_SWAP","why":"added distance and time"},
        "D":{"t":"118.5 km/h","misconception":"OPERATION_SWAP","why":"subtracted time from distance"},
        "explanation":"Speed = 120 / 1.5 = 80 km/h.","expression":"120/1.5"}"""

    @Test fun keyedFormatParses() {
        val p = DiagnosticParser.parse(keyed) as DiagParse.Ok
        assertEquals(0, p.mcq.answer)
        assertEquals(Misconception.FORMULA_SWAP, p.mcq.options[1].code)
        assertEquals("120/1.5", p.mcq.expression)
        assertEquals(AdaptiveDifficulty.MEDIUM, p.mcq.difficulty)
    }

    @Test fun specArrayFormatParses() {
        val spec = """{"q":"What is 10% of 250?","concept":"percent","difficulty":"easy","explanation":"10/100 x 250 = 25.",
            "options":[{"t":"25","correct":true},{"t":"2500","misconception":"FORMULA_SWAP","why":"multiplied by 10"},
            {"t":"240","misconception":"OPERATION_SWAP","why":"subtracted 10"},{"t":"2.5","misconception":"UNIT_CONVERSION","why":"divided by 100 twice"}]}"""
        val p = DiagnosticParser.parse(spec) as DiagParse.Ok
        assertEquals("25", p.mcq.options[p.mcq.answer].text)
    }

    @Test fun twoCorrectIsInvalid() {
        val bad = keyed.replace("\"t\":\"180 km/h\",\"misconception\":\"FORMULA_SWAP\"", "\"t\":\"180 km/h\",\"correct\":true,\"misconception\":\"FORMULA_SWAP\"")
        assertTrue(DiagnosticParser.parse(bad) is DiagParse.Invalid)
    }

    @Test fun unknownCodeNeedsTagsThenRepair() {
        val untagged = keyed.replace("\"FORMULA_SWAP\"", "\"BAD_CODE\"")
        val p = DiagnosticParser.parse(untagged)
        assertTrue(p is DiagParse.NeedsTags)
        val repaired = DiagnosticParser.applyTags((p as DiagParse.NeedsTags).mcq,
            """{"B":{"misconception":"FORMULA_SWAP","why":"multiplied"},"C":{"misconception":"OPERATION_SWAP","why":"added"},"D":{"misconception":"OPERATION_SWAP","why":"subtracted"}}""")
        assertTrue(repaired is DiagParse.Ok)
    }

    @Test fun shuffleKeepsTagsWithTheirOptions() {
        val d = (DiagnosticParser.parse(keyed) as DiagParse.Ok).mcq
        repeat(10) {
            val s = d.shuffled(Random(it))
            assertEquals("80 km/h", s.options[s.answer].text)
            val meta = OptionMeta.parse(s.metaJson())
            val i = s.options.indexOfFirst { it.text == "180 km/h" }
            assertEquals(Misconception.FORMULA_SWAP, meta[i]!!.code)
            assertEquals(null, meta[s.answer])
        }
    }

    @Test fun promptsFitBudget() {
        val stems = List(10) { "A very long earlier question number $it about trains, rupees, monsoon rain and kirana shop prices" }
        val mcq = Prompts.mcq(10, "Mathematics", "Average speed", 3, stems, listOf("Ratios", "Fractions", "Percentages"),
            "Formula swap: used d x t instead of d / t")
        assertTrue(TokenEstimator.estimate(mcq) <= Prompts.PROMPT_BUDGET_TOKENS)
        val tags = Prompts.diagnosticTagLines("A car goes 120 km in 1.5 hours. What is its speed?", "80 km/h", listOf("180 km/h", "121.5 km/h", "118.5 km/h"))
        assertTrue(TokenEstimator.estimate(tags) <= Prompts.PROMPT_BUDGET_TOKENS)
    }

    @Test fun tagLinesParseAndBuildDiagnostic() {
        val raw = "B) FORMULA_SWAP - multiplied distance and time\nC) operation swap: added distance and time\nD) OPERATION_SWAP - subtracted.\n"
        val tags = DiagnosticParser.parseTagLines(raw)
        assertEquals(listOf(Misconception.FORMULA_SWAP, Misconception.OPERATION_SWAP, Misconception.OPERATION_SWAP), tags.map { it?.code })
        assertTrue(Prompts.tagLinesDone(raw))
        assertFalse(Prompts.tagLinesDone(raw.trimEnd()))
        val m = com.offlinetutor.domain.Mcq("A car goes 120 km in 1.5 hours. What is its speed?",
            listOf("180 km/h", "80 km/h", "121.5 km/h", "118.5 km/h"), 1, "120 / 1.5 = 80 km/h.")
        val d = DiagnosticParser.fromMcq(m, "speed", tags) as DiagParse.Ok
        assertEquals(Misconception.FORMULA_SWAP, d.mcq.options[0].code) // first wrong option gets tag B
        assertEquals(null, d.mcq.options[1].code)
    }

    @Test fun blockShapeFromDeviceParses() {
        // Verbatim shape of on-device output (id 260/277): one block per option.
        val raw = "B)  RESPIRATION\nThe letter: B\nThe mistake type: SIGN_ERROR\nThe reason: Respiration releases energy, it does not make food.\n\n" +
            "C) DECOMPOSITION\nThe letter: C\nThe mistake type: UNIT_CONVERSION\nThe reason: Decomposition breaks substances down.\n\n" +
            "D) FERMENTATION\nMistake type: concept mixed up with a related idea\nReason: Fermentation happens without oxygen.\n"
        val tags = DiagnosticParser.parseTagLines(raw)
        assertEquals(listOf(Misconception.SIGN_ERROR, Misconception.UNIT_CONVERSION, Misconception.CONCEPT_CONFUSION), tags.map { it?.code })
        assertEquals("Respiration releases energy, it does not make food", tags[0]!!.why)
        assertTrue(Prompts.tagLinesDone(raw))
        // Word answers can't have arithmetic mistakes: those codes become CONCEPT_CONFUSION.
        val m = com.offlinetutor.domain.Mcq("How do plants make food from sunlight?", listOf("Photosynthesis", "Respiration", "Decomposition", "Fermentation"), 0, "Plants use photosynthesis.")
        val d = DiagnosticParser.fromMcq(m, "plants", tags) as DiagParse.Ok
        assertEquals(Misconception.CONCEPT_CONFUSION, d.mcq.options[1].code)
    }

    @Test fun echoedInstructionsAreNotTags() {
        // Verbatim shape of on-device output (id 271): the model repeated the list of mistake types.
        val raw = "B)  Mahatma Gandhi\nMistake types:\nSIGN_ERROR: a sign\nUNIT_CONVERSION: units\nFORMULA_SWAP: formula\nOPERATION_SWAP: op\n"
        assertEquals(null, DiagnosticParser.parseTagLines(raw)[0])
    }

    @Test fun promptedBlockShapeParses() {
        val raw = Prompts.tagPrefix("180 km/h") + "FORMULA_SWAP\nReason: multiplied distance by time\n\n" +
            "C) 121.5 km/h\nMistake type: OPERATION_SWAP\nReason: added distance and time\n\n" +
            "D) 118.5 km/h\nMistake type: OPERATION_SWAP\nReason: subtracted time from distance\n"
        val tags = DiagnosticParser.parseTagLines(raw)
        assertTrue(tags.all { it != null })
        assertEquals(Misconception.FORMULA_SWAP, tags[0]!!.code)
    }

    @Test fun copiedPlaceholderTagsAreRejected() {
        // Verbatim on-device failure mode: "CODE" copied instead of a real code.
        val tags = DiagnosticParser.parseTagLines("B) CODE - why\nC) CODE - why\nD) CODE - why\n")
        assertTrue(tags.all { it == null })
    }
}

class MasteryTest {
    private val words = 10 // fast limit = 6 s + 10 x 0.35 s = 9.5 s

    @Test fun fastRightStepsUpSlowRightStaysWrongStepsDown() {
        val s = MasteryModel.State(level = 2)
        assertEquals(3, MasteryModel.update(s, 2, true, true, 4_000, words).state.level)
        assertEquals(2, MasteryModel.update(s, 2, true, true, 60_000, words).state.level)
        assertEquals(1, MasteryModel.update(s, 2, false, true, 60_000, words).state.level)
    }

    @Test fun threeFastWrongIsFrustration() {
        var s = MasteryModel.State(level = 3)
        var frustrated = false
        repeat(3) {
            val o = MasteryModel.update(s, 3, false, false, 2_000, words)
            s = o.state
            frustrated = o.frustrated
        }
        assertTrue(frustrated)
        assertEquals(AdaptiveDifficulty.EASY, s.level)
        assertEquals(0, s.fastWrongStreak)
    }

    @Test fun confidentWrongCostsMoreThanUnsureWrong() {
        val s = MasteryModel.State()
        val sure = MasteryModel.update(s, 2, false, true, 30_000, words).delta
        val unsure = MasteryModel.update(s, 2, false, false, 30_000, words).delta
        assertTrue(sure < unsure)
    }

    @Test fun masteredAndStuckThresholds() {
        var s = MasteryModel.State()
        repeat(8) { s = MasteryModel.update(s, 3, true, true, 3_000, words).state }
        assertTrue(MasteryModel.isMastered(s))
        var w = MasteryModel.State()
        repeat(4) { w = MasteryModel.update(w, 1, false, true, 30_000, words).state }
        assertTrue(MasteryModel.isStuck(w))
    }
}

class HealingTest {
    @Test fun confidentWrongOnceInsertsFix() {
        assertTrue(HealingPolicy.shouldInsertFix(emptyList(), HealingPolicy.Hit("FORMULA_SWAP", true, false), emptySet()))
    }

    @Test fun sameCodeTwiceInsertsFix() {
        val history = listOf(HealingPolicy.Hit("SIGN_ERROR", false, false))
        assertTrue(HealingPolicy.shouldInsertFix(history, HealingPolicy.Hit("SIGN_ERROR", false, false), emptySet()))
        assertFalse(HealingPolicy.shouldInsertFix(history, HealingPolicy.Hit("UNIT_CONVERSION", false, false), emptySet()))
    }

    @Test fun noDuplicateFixForOpenCode() {
        assertFalse(HealingPolicy.shouldInsertFix(emptyList(), HealingPolicy.Hit("FORMULA_SWAP", true, false), setOf("FORMULA_SWAP")))
    }
}

class PrereqGraphTest {
    private val nodes = listOf("whole", "fractions", "ratios", "percent", "profit")

    @Test fun dropsCyclesUnknownAndSelfLoops() {
        val built = PrereqGraph.build(nodes, listOf(
            "fractions" to "whole", "ratios" to "fractions", "percent" to "ratios",
            "whole" to "percent",      // closes a cycle
            "percent" to "algebra",    // unknown
            "profit" to "profit",      // self loop
        ))
        assertEquals(3, built.graph.edgeCount)
        assertEquals(setOf("creates a cycle", "unknown step", "self loop"), built.dropped.map { it.second }.toSet())
        assertEquals(listOf("ratios", "fractions", "whole"), built.graph.ancestors("percent"))
        assertEquals(3, built.graph.depth("percent"))
    }

    @Test fun linearFallback() {
        val g = PrereqGraph.linear(nodes)
        assertEquals(setOf("ratios"), g.prereqs("percent"))
        assertEquals(4, g.depth("profit"))
    }
}

class BacktrackTest {
    private val g = PrereqGraph.build(listOf("whole", "fractions", "ratios", "percent"),
        listOf("fractions" to "whole", "ratios" to "fractions", "percent" to "fractions", "percent" to "ratios")).graph

    @Test fun untestedPrereqIsProbedFirst() {
        val m = mapOf("percent" to MasteryModel.State(900.0, attempts = 4), "ratios" to MasteryModel.State(1100.0, attempts = 3))
        assertEquals(Backtrack.Probe("fractions"), BacktrackPlanner.decide("percent", g, m))
    }

    @Test fun weakestTestedPrereqIsReviewed() {
        val m = mapOf(
            "whole" to MasteryModel.State(1200.0, attempts = 5),
            "fractions" to MasteryModel.State(950.0, attempts = 3),
            "ratios" to MasteryModel.State(1100.0, attempts = 3),
        )
        assertEquals(Backtrack.Review("fractions"), BacktrackPlanner.decide("percent", g, m))
    }

    @Test fun allPrereqsMasteredMeansNoBacktrack() {
        val strong = MasteryModel.State(1250.0, attempts = 5)
        val m = mapOf("whole" to strong, "fractions" to strong, "ratios" to strong)
        assertEquals(Backtrack.None, BacktrackPlanner.decide("percent", g, m))
    }
}

class RoadmapPrereqTest {
    @Test fun needsFieldParsesAndBuildsDag() {
        val raw = "1. Whole numbers - Basics - Subtopics: place value - Needs: none\n" +
            "2. Fractions - Parts of a whole - Subtopics: equivalent fractions - Needs: 1\n" +
            "3. Ratios - Compare - Subtopics: simplify - Needs: 2\n" +
            "4. Percentages - Per hundred - Subtopics: percent of - Needs: 2, 3, 4\n" // 4 -> 4 is a self loop
        val drafts = RoadmapParser.parse(raw)!!
        assertEquals(listOf(2, 3, 4), drafts[3].needs)
        assertEquals(listOf("percent of"), drafts[3].subtopics)
        val steps = RoadmapRepository.toSteps(1, "Mathematics", drafts, "llm")
        assertEquals(setOf("fractions", "ratios"), steps[3].prereqList.toSet())
    }

    @Test fun noNeedsFallsBackToLinearChain() {
        val raw = "1. Alpha - g - Subtopics: a\n2. Beta - g - Subtopics: b\n3. Gamma - g - Subtopics: c"
        val steps = RoadmapRepository.toSteps(1, "X", RoadmapParser.parse(raw)!!, "llm")
        assertEquals(listOf("beta"), steps[2].prereqList)
        assertEquals(emptyList<String>(), steps[0].prereqList)
    }

    @Test fun skillTreeStates() {
        assertEquals(NodeState.FIX, SkillTreeRules.state("MICRO_FIX", false, false, true))
        assertEquals(NodeState.MASTERED, SkillTreeRules.state(null, false, true, false))
        assertEquals(NodeState.ACTIVE, SkillTreeRules.state(null, false, false, true))
        assertEquals(NodeState.LOCKED, SkillTreeRules.state(null, false, false, false))
    }
}

class MigrationTest {
    @Test fun migration1to2MatchesExportedSchema() = checkMigration(Migrations.MIGRATION_1_2)

    @Test fun migration2to3MatchesExportedSchema() = checkMigration(Migrations.MIGRATION_2_3)

    @Test fun migration3to4MatchesExportedSchema() = checkMigration(Migrations.MIGRATION_3_4)

    @Test fun migration4to5MatchesExportedSchema() = checkMigration(Migrations.MIGRATION_4_5)

    @Test fun migration5to6MatchesExportedSchema() = checkMigration(Migrations.MIGRATION_5_6)

    @Test fun migrationsCoverEveryVersion() {
        val versions = Migrations.ALL.map { it.startVersion to it.endVersion }
        val latest = File("schemas/com.offlinetutor.data.AppDatabase").listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals((1 until latest).map { it to it + 1 }, versions)
    }

    /** Records every SQL statement a migration executes and checks it against Room's exported schemas. */
    private fun checkMigration(migration: androidx.room.migration.Migration) {
        val sql = ArrayList<String>()
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, args ->
            if (m.name == "execSQL") sql.add(args[0] as String)
            null
        } as SupportSQLiteDatabase
        migration.migrate(db)

        val dir = File("schemas/com.offlinetutor.data.AppDatabase")
        fun entities(v: Int) = JSONObject(File(dir, "$v.json").readText()).getJSONObject("database").getJSONArray("entities")
        val v1 = entities(migration.startVersion)
        val v2 = entities(migration.endVersion)
        val v1Tables = (0 until v1.length()).associate { i -> v1.getJSONObject(i).let { it.getString("tableName") to it } }
        for (i in 0 until v2.length()) {
            val e = v2.getJSONObject(i)
            val table = e.getString("tableName")
            val old = v1Tables[table]
            if (old == null) {
                val create = e.getString("createSql").replace("\${TABLE_NAME}", table)
                assertTrue("missing CREATE for $table", sql.contains(create))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (k in 0 until indices.length()) {
                    assertTrue(sql.contains(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table)))
                }
            } else {
                val oldCols = old.getJSONArray("fields").let { f -> (0 until f.length()).map { f.getJSONObject(it).getString("columnName") }.toSet() }
                val fields = e.getJSONArray("fields")
                for (k in 0 until fields.length()) {
                    val f = fields.getJSONObject(k)
                    val col = f.getString("columnName")
                    if (col in oldCols) continue
                    assertFalse("new column $table.$col must be nullable", f.optBoolean("notNull"))
                    val alter = "ALTER TABLE `$table` ADD COLUMN `$col` ${f.getString("affinity")}"
                    assertTrue("missing $alter", sql.contains(alter))
                }
            }
        }
        assertNotNull(sql.firstOrNull())
    }
}
