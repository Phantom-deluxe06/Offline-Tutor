package com.offlinetutor.data

import android.content.Context
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.ExplainLanguage
import com.offlinetutor.domain.PinHasher
import com.offlinetutor.domain.Prompts
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.RoadmapParser
import com.offlinetutor.domain.RoadmapTemplates
import com.offlinetutor.domain.SeedBank
import com.offlinetutor.domain.WeakSpots
import com.offlinetutor.llm.GenRequest
import com.offlinetutor.llm.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Which local profile is active. Stored in SharedPreferences (excluded from backup). */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _active = MutableStateFlow(prefs.getLong(KEY, -1L).takeIf { it > 0 })
    val activeProfileId: StateFlow<Long?> = _active.asStateFlow()

    fun setActive(id: Long?) {
        prefs.edit().putLong(KEY, id ?: -1L).apply()
        _active.value = id
    }

    private companion object { const val KEY = "active_profile" }
}

class ProfileRepository(private val dao: ProfileDao, val session: SessionStore) {
    fun observeAll(): Flow<List<Profile>> = dao.observeAll()
    fun observe(id: Long): Flow<Profile?> = dao.observe(id)
    suspend fun get(id: Long) = dao.get(id)

    suspend fun create(name: String, grade: Int, courses: List<String>, language: String, pin: String?): Long {
        val salt = pin?.let { PinHasher.newSalt() }
        val id = dao.insert(
            Profile(
                name = name.trim(), grade = grade, courses = courses.joinToString("|"), language = language,
                pinSalt = salt, pinHash = if (pin != null && salt != null) PinHasher.hash(pin, salt) else null,
            ),
        )
        session.setActive(id)
        return id
    }

    suspend fun update(p: Profile) = dao.update(p)

    suspend fun setPin(p: Profile, pin: String?) {
        if (pin == null) {
            dao.update(p.copy(pinHash = null, pinSalt = null))
        } else {
            val salt = PinHasher.newSalt()
            dao.update(p.copy(pinSalt = salt, pinHash = PinHasher.hash(pin, salt)))
        }
    }

    fun checkPin(p: Profile, pin: String): Boolean {
        val salt = p.pinSalt ?: return true
        val hash = p.pinHash ?: return true
        return PinHasher.verify(pin, salt, hash)
    }

    suspend fun delete(p: Profile) {
        dao.delete(p)
        if (session.activeProfileId.value == p.id) session.setActive(null)
    }
}

class RoadmapRepository(private val dao: RoadmapDao, private val ai: TutorAi) {
    fun observe(profileId: Long) = dao.observe(profileId)
    suspend fun forCourse(profileId: Long, course: String) = dao.forCourse(profileId, course)
    suspend fun setDone(id: Long, done: Boolean) = dao.setDone(id, done)

    data class Outcome(val source: String, val attempts: Int)

    /** Generate → parse; retry once; then fall back to a built-in template. */
    suspend fun generate(profile: Profile, course: String, onPartial: (String) -> Unit): Outcome {
        val prompt = Prompts.roadmap(profile.grade, course)
        repeat(2) { attempt ->
            var drafts: List<com.offlinetutor.domain.RoadmapDraft>? = null
            ai.run(
                GenRequest(TaskType.ROADMAP, prompt, assistantPrefix = Prompts.ROADMAP_PREFIX, stopWhen = Prompts::roadmapDone),
                validate = { r -> drafts = RoadmapParser.parse(r.text); (drafts != null) to null },
                onPartial = onPartial,
            )
            drafts?.let { d ->
                dao.replaceCourse(profile.id, course, d.mapIndexed { i, s ->
                    RoadmapStep(profileId = profile.id, course = course, stepIndex = i, title = s.title, goal = s.goal,
                        subtopics = s.subtopics.joinToString("|"), source = "llm")
                })
                return Outcome("llm", attempt + 1)
            }
        }
        val t = RoadmapTemplates.forCourse(course)
        dao.replaceCourse(profile.id, course, t.mapIndexed { i, s ->
            RoadmapStep(profileId = profile.id, course = course, stepIndex = i, title = s.title, goal = s.goal,
                subtopics = s.subtopics.joinToString("|"), source = "template")
        })
        return Outcome("template", 2)
    }
}

class QuizRepository(
    private val dao: QuizDao,
    private val roadmaps: RoadmapDao,
    private val generator: McqGenerator,
    private val seeds: SeedBank,
) {
    fun observeAnswered(profileId: Long) = dao.observeAnswered(profileId)

    suspend fun state(profileId: Long, course: String): AdaptiveDifficulty.State =
        AdaptiveDifficulty.fromHistory(dao.answeredForCourse(profileId, course).map { it.difficulty to (it.correct == true) })

    suspend fun weakSubtopics(profileId: Long, course: String): List<String> =
        WeakSpots.weak(dao.answeredForCourse(profileId, course).map { it.subtopic to (it.correct == true) })

    suspend fun pending(profileId: Long) = dao.pending(profileId)

    data class Next(val question: QuizQuestion, val attempts: Int, val note: String?)

    /**
     * Adaptive next question: difficulty from the 2-up/1-down staircase, subtopic from the
     * current roadmap step or a weak spot, then generate → validate → dedupe, retry once,
     * and finally fall back to the seed bank.
     */
    suspend fun next(profile: Profile, course: String, forcedSubtopic: String?, onPartial: (String) -> Unit): Next {
        dao.dropUnanswered(profile.id)
        val level = state(profile.id, course).level
        val answered = dao.answeredForCourse(profile.id, course)
        val weak = WeakSpots.weak(answered.map { it.subtopic to (it.correct == true) })
        val subtopic = forcedSubtopic ?: chooseSubtopic(roadmaps.forCourse(profile.id, course), weak, answered, course)
        val history = dao.allNormalizedStems(profile.id).toMutableSet()
        val recent = dao.recentStems(profile.id, course, 10).toMutableList()

        var lastReason: String? = null
        for (attempt in 1..2) {
            val o = generator.attempt(profile.grade, course, subtopic, level, recent, weak, history, LogMeta(), onPartial)
            val mcq = o.mcq
            if (mcq != null) {
                val shuffled = mcq.shuffled()
                val q = QuizQuestion(
                    profileId = profile.id, course = course, subtopic = subtopic, difficulty = level,
                    stem = shuffled.question, normalizedStem = QuestionDeduper.normalize(shuffled.question),
                    options = shuffled.options.joinToString(QuizQuestion.OPTION_SEP.toString()),
                    answerIndex = shuffled.answer, explanation = shuffled.explanation, source = "llm",
                )
                return Next(q.copy(id = dao.insert(q)), attempt, null)
            }
            lastReason = o.reason
            // On a duplicate, tell the model explicitly what not to ask again.
            o.result.text.let { t -> com.offlinetutor.domain.McqParser.parse(t) }
                .let { (it as? com.offlinetutor.domain.McqParse.Ok)?.mcq?.question }
                ?.let { recent.add(0, it) }
        }

        val seed = seeds.pick(course, level, history)
            ?: throw IllegalStateException("Could not create a question ($lastReason) and the seed bank is used up.")
        val m = seed.mcq.shuffled()
        val q = QuizQuestion(
            profileId = profile.id, course = course, subtopic = seed.topic, difficulty = seed.difficulty,
            stem = m.question, normalizedStem = QuestionDeduper.normalize(m.question),
            options = m.options.joinToString(QuizQuestion.OPTION_SEP.toString()),
            answerIndex = m.answer, explanation = m.explanation, source = "seed",
        )
        return Next(q.copy(id = dao.insert(q)), 2, lastReason)
    }

    private fun chooseSubtopic(steps: List<RoadmapStep>, weak: List<String>, answered: List<QuizQuestion>, course: String): String {
        if (weak.isNotEmpty() && Random.nextFloat() < 0.4f) return weak.random()
        val current = steps.firstOrNull { !it.done } ?: steps.lastOrNull()
        // No roadmap yet: use the built-in template's subtopics so the topic is concrete
        // (a bare "Mathematics" made the 1B model drift back to recent questions).
        val candidates = current?.subtopicList?.takeIf { it.isNotEmpty() }
            ?: RoadmapTemplates.forCourse(course).flatMap { it.subtopics }
        if (candidates.isEmpty()) return course
        val counts = answered.groupingBy { it.subtopic }.eachCount()
        val least = candidates.minOf { counts[it] ?: 0 }
        return candidates.filter { (counts[it] ?: 0) == least }.random()
    }

    suspend fun answer(q: QuizQuestion, chosen: Int): QuizQuestion {
        val correct = chosen == q.answerIndex
        val now = System.currentTimeMillis()
        dao.answer(q.id, chosen, correct, now)
        return q.copy(chosenIndex = chosen, correct = correct, answeredAt = now)
    }
}

class ChatRepository(private val dao: ChatDao, private val ai: TutorAi) {
    fun observe(profileId: Long) = dao.observe(profileId)
    suspend fun clear(profileId: Long) = dao.clear(profileId)

    suspend fun send(profile: Profile, text: String, course: String?, onPartial: (String) -> Unit): ChatMessage {
        val lang = ExplainLanguage.of(profile.language)
        dao.insert(ChatMessage(profileId = profile.id, role = "user", text = text.trim(), language = lang.code))
        val history = dao.recent(profile.id, 8).reversed().map { Prompts.ChatTurn(it.role == "user", it.text) }
        val r = ai.run(GenRequest(TaskType.CHAT, Prompts.chat(profile.grade, lang, course, history)), onPartial = onPartial)
        val reply = r.text.ifBlank { r.error?.let { "⚠ $it" } ?: "…" }
        val msg = ChatMessage(profileId = profile.id, role = "assistant", text = reply, language = lang.code)
        return msg.copy(id = dao.insert(msg))
    }

    suspend fun explain(profile: Profile, lang: ExplainLanguage, q: QuizQuestion, onPartial: (String) -> Unit): String {
        val prompt = Prompts.explainAnswer(profile.grade, lang, q.stem, q.optionList.getOrElse(q.answerIndex) { "" }, q.explanation)
        return ai.run(GenRequest(TaskType.EXPLAIN, prompt), onPartial = onPartial).text
    }
}

class MetricsRepository(private val dao: LogDao) {
    fun observeRecent(limit: Int = 50) = dao.observeRecent(limit)
    suspend fun forRun(run: String) = dao.forRun(run)
    suspend fun clear() = dao.clear()

    /** Writes every logged run as CSV (one row per generation) for the report. */
    suspend fun exportCsv(out: OutputStream): Int {
        val rows = dao.all()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val header = listOf(
            "id", "time", "task", "model", "eval_run", "eval_subject", "eval_grade", "difficulty",
            "prompt_chars", "prompt_tokens_est", "prompt_tokens", "generated_tokens", "max_new_tokens", "temperature",
            "ttft_ms", "wall_ms", "decode_tok_s", "prefill_tok_s", "peak_rss_mb", "rss_mb", "device_avail_mb",
            "thermal_status", "thermal_policy", "stop_reason", "guard_reason", "valid_json", "duplicate", "error",
            "output", "raw_stats", "review_answer_correct", "review_difficulty_match", "review_roadmap_score_1to5",
        )
        out.bufferedWriter().use { w ->
            w.write(header.joinToString(",")); w.write("\n")
            for (l in rows) {
                val cells = listOf(
                    l.id, fmt.format(Date(l.createdAt)), l.task, l.model, l.evalRun, l.evalSubject, l.evalGrade, l.difficulty,
                    l.promptChars, l.promptTokensEst, l.promptTokens, l.generatedTokens, l.maxNewTokens, l.temperature,
                    l.ttftMs, l.wallMs, l.decodeTokPerSec?.let { "%.2f".format(Locale.US, it) },
                    l.prefillTokPerSec?.let { "%.2f".format(Locale.US, it) },
                    mb(l.peakRssKb), mb(l.rssKb), mb(l.deviceAvailKb), l.thermalStatus, l.thermalPolicy, l.stopReason,
                    l.guardReason, l.validJson, l.duplicate, l.error, l.output, l.rawStats, "", "", "",
                )
                w.write(cells.joinToString(",") { csv(it) }); w.write("\n")
            }
        }
        return rows.size
    }

    private fun mb(kb: Long) = if (kb < 0) null else "%.1f".format(Locale.US, kb / 1024.0)

    private fun csv(v: Any?): String {
        val s = v?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
