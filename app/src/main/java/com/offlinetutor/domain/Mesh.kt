package com.offlinetutor.domain

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Phase 5 pure logic (JVM-tested): battle protocol, scoring, class misconception heatmap, question-share manifests and
 * the "Share my progress" QR payload. Transport (Nearby Connections) lives in data/Mesh*.kt.
 */
object BattleProtocol {
    const val VERSION = 1
    /** ConnectionsClient.MAX_BYTES_DATA_SIZE for BYTES payloads; every message stays well under it. */
    const val MAX_BYTES = 32 * 1024

    /** A battle question as sent to joiners: no answer flag, no misconception tags. */
    data class BQ(val stem: String, val options: List<String>)

    sealed interface Msg {
        data class Hello(val nickname: String) : Msg
        data class Quiz(val questions: List<BQ>) : Msg
        data class RoundStart(val i: Int, val deadlineMs: Long) : Msg
        data class Answer(val i: Int, val option: Int, val ms: Long, val confident: Boolean?) : Msg
        /** After a round: the key, how many picked each option, the misconception behind each wrong option, your points. */
        data class RoundResult(val i: Int, val correct: Int, val counts: List<Int>, val codes: List<String?>,
                               val yourPoints: Int, val yourCorrect: Boolean?) : Msg
        data class Leaderboard(val entries: List<Pair<String, Int>>) : Msg
        data object End : Msg
    }

    fun encode(m: Msg): ByteArray {
        val o = JSONObject().put("v", VERSION)
        when (m) {
            is Msg.Hello -> o.put("t", "HELLO").put("nickname", m.nickname)
            is Msg.Quiz -> o.put("t", "QUIZ").put("q", JSONArray(m.questions.map { q ->
                JSONObject().put("s", q.stem).put("o", JSONArray(q.options))
            }))
            is Msg.RoundStart -> o.put("t", "ROUND_START").put("i", m.i).put("deadlineMs", m.deadlineMs)
            is Msg.Answer -> o.put("t", "ANSWER").put("i", m.i).put("option", m.option).put("ms", m.ms)
                .put("confidence", m.confident ?: JSONObject.NULL)
            is Msg.RoundResult -> o.put("t", "ROUND_RESULT").put("i", m.i).put("correct", m.correct)
                .put("counts", JSONArray(m.counts)).put("codes", JSONArray(m.codes.map { it ?: JSONObject.NULL }))
                .put("points", m.yourPoints).put("yourCorrect", m.yourCorrect ?: JSONObject.NULL)
            is Msg.Leaderboard -> o.put("t", "LEADERBOARD").put("e", JSONArray(m.entries.map { (n, s) -> JSONObject().put("n", n).put("s", s) }))
            Msg.End -> o.put("t", "END")
        }
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size < MAX_BYTES) { "message too large: ${bytes.size} bytes" }
        return bytes
    }

    fun decode(bytes: ByteArray): Msg? = runCatching {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optInt("v") != VERSION) return null
        when (o.optString("t")) {
            "HELLO" -> Msg.Hello(o.getString("nickname").take(20))
            "QUIZ" -> o.getJSONArray("q").let { a ->
                Msg.Quiz((0 until a.length()).map { i ->
                    val q = a.getJSONObject(i)
                    val opts = q.getJSONArray("o")
                    BQ(q.getString("s"), (0 until opts.length()).map { opts.getString(it) })
                })
            }
            "ROUND_START" -> Msg.RoundStart(o.getInt("i"), o.getLong("deadlineMs"))
            "ANSWER" -> Msg.Answer(o.getInt("i"), o.getInt("option"), o.getLong("ms"),
                if (o.isNull("confidence")) null else o.getBoolean("confidence"))
            "ROUND_RESULT" -> {
                val c = o.getJSONArray("counts")
                val k = o.getJSONArray("codes")
                Msg.RoundResult(o.getInt("i"), o.getInt("correct"), (0 until c.length()).map { c.getInt(it) },
                    (0 until k.length()).map { if (k.isNull(it)) null else k.getString(it) }, o.optInt("points"),
                    if (o.isNull("yourCorrect")) null else o.getBoolean("yourCorrect"))
            }
            "LEADERBOARD" -> o.getJSONArray("e").let { a ->
                Msg.Leaderboard((0 until a.length()).map { a.getJSONObject(it).let { e -> e.getString("n") to e.getInt("s") } })
            }
            "END" -> Msg.End
            else -> null
        }
    }.getOrNull()
}

object BattleScoring {
    const val BASE = 1000
    const val SPEED_BONUS = 500

    /** Correctness plus a speed bonus that shrinks linearly to 0 at the deadline. */
    fun points(correct: Boolean, ms: Long, deadlineMs: Long): Int {
        if (!correct) return 0
        val frac = 1.0 - (ms.coerceIn(0, deadlineMs).toDouble() / deadlineMs.coerceAtLeast(1))
        return BASE + (SPEED_BONUS * frac).toInt()
    }
}

/** 5a end screen: share of the class that picked each misconception, per question. */
object ClassHeatmap {
    /** One answered question: what each student picked, and the code behind each option (null for the key). */
    data class Round(val picks: List<Int>, val codes: List<String?>)

    /** (code, question index) → share of answers that chose an option with that code. */
    fun cells(rounds: List<Round>): Map<Pair<String, Int>, Double> = buildMap {
        rounds.forEachIndexed { qi, r ->
            if (r.picks.isEmpty()) return@forEachIndexed
            r.picks.mapNotNull { r.codes.getOrNull(it) }.groupingBy { it }.eachCount().forEach { (code, n) ->
                put(code to qi, n.toDouble() / r.picks.size)
            }
        }
    }

    /** Strongest cell as a sentence, e.g. "60% picked FORMULA_SWAP on Q3". */
    fun headline(rounds: List<Round>): String? = cells(rounds).maxByOrNull { it.value }?.let { (k, v) ->
        "${Math.round(v * 100)}% picked ${k.first} on Q${k.second + 1}"
    }
}

/** 5b: what a phone offers when sharing questions; only pool items, never attempts or profiles. */
object QuestionShare {
    data class Entry(val hash: String, val concept: String, val grade: Int, val difficulty: Int)

    fun hash(normalizedStem: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalizedStem.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    /** Hashes to request from a peer: not held locally, same grade band (±1), at most [cap]. */
    fun wanted(mine: Set<String>, theirs: List<Entry>, myGrade: Int, cap: Int): List<String> =
        theirs.filter { it.hash !in mine && kotlin.math.abs(it.grade - myGrade) <= 1 }.map { it.hash }.distinct().take(cap)

    fun manifestJson(entries: List<Entry>): String = JSONArray(entries.map {
        JSONObject().put("h", it.hash).put("c", it.concept).put("g", it.grade).put("d", it.difficulty)
    }).toString()

    fun parseManifest(json: String): List<Entry> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getString("h"), o.getString("c"), o.getInt("g"), o.getInt("d")) } }
    }.getOrDefault(emptyList())

    /**
     * A shared template item is (type, level, seed, story); the receiver regenerates the numbers and recomputes the
     * answer from the parameters, and re-checks the story, so a peer can never send a wrong key.
     */
    fun rebuildTemplate(type: String, level: Int, seed: Long, story: String?): Pair<TemplateProblem, String>? {
        val t = TemplateType.entries.firstOrNull { it.name == type } ?: return null
        val p = ProblemTemplates.generate(t, level, kotlin.random.Random(seed))
        val accepted = story?.takeIf { s -> ProblemTemplates.storyProblem(s, p.slots.size) == null && ProblemTemplates.unitClash(s, p.slots) == null }
        return p to (accepted?.let { p.textFor(it) } ?: p.defaultText)
    }
}

/** 5c: "Share my progress" QR payload: compressed mastery + misconception counts, kept under ~2.5 KB of text. */
object ProgressQr {
    const val MAX_CHARS = 2500
    data class Progress(val nickname: String, val mastery: List<Triple<String, Int, Int>>, val misconceptions: Map<String, Int>)

    /** mastery = (concept, rating, attempts). Drops the least-attempted concepts until it fits. */
    fun encode(p: Progress): String {
        var mastery = p.mastery.sortedByDescending { it.third }
        while (true) {
            val json = JSONObject().put("v", 1).put("n", p.nickname.take(20))
                .put("m", JSONArray(mastery.map { (c, r, a) -> JSONArray().put(c.take(40)).put(r).put(a) }))
                .put("x", JSONObject(p.misconceptions as Map<*, *>)).toString()
            val d = Deflater(Deflater.BEST_COMPRESSION)
            d.setInput(json.toByteArray(Charsets.UTF_8)); d.finish()
            val buf = ByteArray(8192); val n = d.deflate(buf); d.end()
            val s = "GP1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.copyOf(n))
            if (s.length <= MAX_CHARS || mastery.isEmpty()) return s
            mastery = mastery.dropLast(maxOf(1, mastery.size / 5))
        }
    }

    fun decode(s: String): Progress? = runCatching {
        require(s.startsWith("GP1:"))
        val raw = Base64.getUrlDecoder().decode(s.removePrefix("GP1:"))
        val inf = Inflater(); inf.setInput(raw)
        val out = ByteArray(64 * 1024); val n = inf.inflate(out); inf.end()
        val o = JSONObject(String(out, 0, n, Charsets.UTF_8))
        val m = o.getJSONArray("m")
        val x = o.getJSONObject("x")
        Progress(o.getString("n"), (0 until m.length()).map { m.getJSONArray(it).let { e -> Triple(e.getString(0), e.getInt(1), e.getInt(2)) } },
            x.keys().asSequence().associateWith { x.getInt(it) })
    }.getOrNull()

    /** Teacher scan mode: misconception counts summed over scanned students, as heatmap rows (code → students, total). */
    fun aggregate(scans: List<Progress>): Map<String, Pair<Int, Int>> = scans.flatMap { it.misconceptions.entries }
        .groupBy { it.key }.mapValues { (_, v) -> v.count { it.value > 0 } to v.sumOf { it.value } }
}
