package com.offlinetutor.data

import android.content.Context
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.Concepts
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
                dao.replaceCourse(profile.id, course, toSteps(profile.id, course, d, "llm"))
                return Outcome("llm", attempt + 1)
            }
        }
        dao.replaceCourse(profile.id, course, toSteps(profile.id, course, RoadmapTemplates.forCourse(course), "template"))
        return Outcome("template", 2)
    }

    companion object {
        /**
         * 1f: concept ids + prerequisite edges. The model's "Needs:" edges are validated as a DAG (unknown
         * steps, self-loops and cycle-closing edges are dropped); with no usable edge, a linear chain is used.
         */
        fun toSteps(profileId: Long, course: String, drafts: List<com.offlinetutor.domain.RoadmapDraft>, source: String): List<RoadmapStep> {
            val used = HashMap<String, Int>()
            val ids = drafts.map { d ->
                val base = Concepts.slug(d.title)
                val n = (used[base] ?: 0) + 1
                used[base] = n
                if (n == 1) base else "$base-$n"
            }
            val proposed = RoadmapParser.proposedEdges(drafts).mapNotNull { (step, pre) ->
                val a = ids.getOrNull(step - 1)
                val b = ids.getOrNull(pre - 1)
                if (a != null && b != null) a to b else null
            }
            val built = com.offlinetutor.domain.PrereqGraph.build(ids, proposed).graph
            val graph = if (built.edgeCount == 0) com.offlinetutor.domain.PrereqGraph.linear(ids) else built
            return drafts.mapIndexed { i, s ->
                RoadmapStep(profileId = profileId, course = course, stepIndex = i, title = s.title, goal = s.goal,
                    subtopics = s.subtopics.joinToString("|"), source = source,
                    conceptId = ids[i], prereqs = graph.prereqs(ids[i]).joinToString("|"))
            }
        }
    }
}

class QuizRepository(
    private val dao: QuizDao,
    private val roadmaps: RoadmapDao,
    private val generator: McqGenerator,
    private val diag: DiagnosticGenerator,
    private val seeds: SeedBank,
    private val brain: AdaptiveBrain,
    private val probes: ProbeDao,
    private val flags: FeatureFlags,
) {
    fun observeAnswered(profileId: Long) = dao.observeAnswered(profileId)

    suspend fun state(profileId: Long, course: String): AdaptiveDifficulty.State =
        AdaptiveDifficulty.fromHistory(dao.answeredForCourse(profileId, course).map { it.difficulty to (it.correct == true) })

    suspend fun weakSubtopics(profileId: Long, course: String): List<String> =
        WeakSpots.weak(dao.answeredForCourse(profileId, course).map { it.subtopic to (it.correct == true) })

    suspend fun pending(profileId: Long) = dao.pending(profileId)

    /** Level shown in the UI for a course: per-concept Elo level when enabled, else the staircase. */
    suspend fun displayLevel(profileId: Long, course: String): Int {
        if (!flags.isOn(Feature.ELO_DIFFICULTY)) return state(profileId, course).level
        val t = pickTarget(profileId, course, null, null, dao.answeredForCourse(profileId, course), emptyList(), null)
        return brain.masteryState(profileId, t.concept)?.level ?: AdaptiveDifficulty.EASY
    }

    data class Next(val question: QuizQuestion, val attempts: Int, val note: String?)

    /** What the next question is about. [purpose]: null = normal quiz, PROBE, or FIX:<stepId>. */
    data class Target(val subtopic: String, val concept: String, val conceptTitle: String, val purpose: String?)

    /**
     * Adaptive next question. Target: a pending probe first, then a forced subtopic/concept, then the current
     * roadmap step (NORMAL or REVIEW). Level: per-concept Elo rules (toggle) or the 2-up/1-down staircase.
     * Generation: diagnostic MCQ (toggle) or plain MCQ, validate → dedupe, retry once, then the seed bank.
     */
    suspend fun next(
        profile: Profile,
        course: String,
        forcedSubtopic: String?,
        onPartial: (String) -> Unit,
        forcedConcept: String? = null,
        purpose: String? = null,
        focus: String? = null,
    ): Next {
        dao.dropUnanswered(profile.id)
        val answered = dao.answeredForCourse(profile.id, course)
        val weak = WeakSpots.weak(answered.map { it.subtopic to (it.correct == true) })
        val t = pickTarget(profile.id, course, forcedSubtopic, forcedConcept, answered, weak, purpose)
        val level = if (flags.isOn(Feature.ELO_DIFFICULTY)) {
            brain.masteryState(profile.id, t.concept)?.level ?: AdaptiveDifficulty.EASY
        } else {
            state(profile.id, course).level
        }
        val history = dao.allNormalizedStems(profile.id).toMutableSet()
        val recent = dao.recentStems(profile.id, course, 10).toMutableList()

        fun store(
            stem: String, options: List<String>, answer: Int, explanation: String, source: String, meta: String?,
            subtopic: String = t.subtopic, difficulty: Int = level, concept: String = t.concept,
        ) = QuizQuestion(
            profileId = profile.id, course = course, subtopic = subtopic, difficulty = difficulty,
            stem = stem, normalizedStem = QuestionDeduper.normalize(stem),
            options = options.joinToString(QuizQuestion.OPTION_SEP.toString()),
            answerIndex = answer, explanation = explanation, source = source,
            concept = concept, optionMeta = meta, purpose = t.purpose,
        )

        var lastReason: String? = null
        for (attempt in 1..2) {
            if (flags.isOn(Feature.DIAGNOSTIC_MCQ)) {
                val o = diag.attempt(profile.grade, course, t.conceptTitle, t.concept, t.subtopic, level, recent, weak, history, LogMeta(), focus, onPartial)
                o.mcq?.let { d ->
                    val s = d.shuffled()
                    val q = store(s.question, s.options.map { it.text }, s.answer, s.explanation, "llm", s.metaJson())
                    return Next(q.copy(id = dao.insert(q)), attempt, null)
                }
                lastReason = o.reason
            } else {
                val o = generator.attempt(profile.grade, course, t.subtopic, level, recent, weak, history, LogMeta(), onPartial, focus)
                o.mcq?.let { mcq ->
                    val s = mcq.shuffled()
                    val q = store(s.question, s.options, s.answer, s.explanation, "llm", null)
                    return Next(q.copy(id = dao.insert(q)), attempt, null)
                }
                lastReason = o.reason
                o.result.text.let { txt -> com.offlinetutor.domain.McqParser.parse(txt) }
                    .let { (it as? com.offlinetutor.domain.McqParse.Ok)?.mcq?.question }
                    ?.let { recent.add(0, it) }
            }
        }

        val seed = seeds.pick(course, level, history)
            ?: throw IllegalStateException("Could not create a question ($lastReason) and the seed bank is used up.")
        // A bank question is credited to its own topic, not to the step that was targeted.
        val seedConcept = Concepts.slug(seed.topic)
        val d = seed.toDiag(seedConcept).shuffled()
        val q = store(d.question, d.options.map { it.text }, d.answer, d.explanation, "seed",
            d.metaJson().takeIf { d.options.any { it.code != null } }, subtopic = seed.topic, difficulty = seed.difficulty, concept = seedConcept)
        return Next(q.copy(id = dao.insert(q)), 2, lastReason)
    }

    private suspend fun pickTarget(
        profileId: Long,
        course: String,
        forcedSubtopic: String?,
        forcedConcept: String?,
        answered: List<QuizQuestion>,
        weak: List<String>,
        purpose: String?,
    ): Target {
        val steps = roadmaps.forCourse(profileId, course)
        fun stepFor(concept: String) = steps.firstOrNull { it.stepKind == StepKind.NORMAL && it.concept == concept }
        fun conceptOf(s: RoadmapStep) = if (s.stepKind == StepKind.REVIEW) s.targetConcept ?: s.concept else s.concept
        fun leastAsked(cands: List<String>, fallback: String): String {
            if (cands.isEmpty()) return fallback
            val counts = answered.groupingBy { it.subtopic }.eachCount()
            val least = cands.minOf { counts[it] ?: 0 }
            return cands.filter { (counts[it] ?: 0) == least }.random()
        }

        if (forcedConcept != null) {
            val s = stepFor(forcedConcept)
            return Target(leastAsked(s?.subtopicList.orEmpty(), forcedConcept), forcedConcept, s?.title ?: forcedConcept, purpose)
        }
        // 1f: an untested prerequisite gets its probe questions before anything else.
        if (purpose == null && forcedSubtopic == null && flags.isOn(Feature.BACKTRACKING)) {
            probes.next(profileId, course)?.let { p ->
                val s = stepFor(p.conceptId)
                return Target(leastAsked(s?.subtopicList.orEmpty(), p.conceptId), p.conceptId, s?.title ?: p.conceptId, "PROBE")
            }
        }
        if (forcedSubtopic != null) {
            val s = steps.firstOrNull { forcedSubtopic in it.subtopicList && it.stepKind != StepKind.MICRO_FIX }
            val c = s?.let { conceptOf(it) } ?: Concepts.slug(forcedSubtopic)
            return Target(forcedSubtopic, c, stepFor(c)?.title ?: s?.title ?: forcedSubtopic, purpose)
        }
        if (weak.isNotEmpty() && Random.nextFloat() < 0.4f) {
            val w = weak.random()
            val s = steps.firstOrNull { w in it.subtopicList && it.stepKind == StepKind.NORMAL }
            return Target(w, s?.concept ?: Concepts.slug(w), s?.title ?: w, purpose)
        }
        // Current step: fix steps are worked in the Fix screen, so the quiz follows NORMAL / REVIEW steps.
        val current = steps.firstOrNull { !it.done && it.stepKind != StepKind.MICRO_FIX }
            ?: steps.lastOrNull { it.stepKind == StepKind.NORMAL }
        if (current == null) {
            // No roadmap yet: concrete subtopics from the built-in template.
            val sub = leastAsked(RoadmapTemplates.forCourse(course).flatMap { it.subtopics }, course)
            return Target(sub, Concepts.slug(sub), sub, purpose)
        }
        val c = conceptOf(current)
        val title = stepFor(c)?.title ?: current.title
        return Target(leastAsked(current.subtopicList, title), c, title, purpose)
    }

    /** Stores the answer (with confidence and response time) and runs the adaptive brain. */
    suspend fun answer(profile: Profile, q: QuizQuestion, chosen: Int, confident: Boolean?, responseMs: Long?): Pair<QuizQuestion, BrainResult> {
        val correct = chosen == q.answerIndex
        val now = System.currentTimeMillis()
        val code = if (correct) null else com.offlinetutor.domain.OptionMeta.parse(q.optionMeta).getOrNull(chosen)?.code?.name
        dao.answerFull(q.id, chosen, correct, now, confident, responseMs, code)
        val a = q.copy(chosenIndex = chosen, correct = correct, answeredAt = now, confident = confident, responseMs = responseMs, misconception = code)
        return a to brain.onAnswer(profile, a)
    }

    suspend fun latestForPurpose(profileId: Long, purpose: String) = dao.latestForPurpose(profileId, purpose)
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
