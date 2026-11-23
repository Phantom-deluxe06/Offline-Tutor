package com.offlinetutor.data

import android.content.Context
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.Concepts
import com.offlinetutor.domain.ExplainLanguage
import com.offlinetutor.domain.ProblemTemplates
import com.offlinetutor.domain.Verdict
import com.offlinetutor.domain.PinHasher
import com.offlinetutor.domain.PoolPolicy
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

    suspend fun create(name: String, grade: Int, courses: List<String>, language: String, pin: String?, world: String? = null): Long {
        val salt = pin?.let { PinHasher.newSalt() }
        val id = dao.insert(
            Profile(
                name = name.trim(), grade = grade, courses = courses.joinToString("|"), language = language,
                world = world,
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
    private val templates: TemplateGenerator,
    private val seeds: SeedBank,
    private val brain: AdaptiveBrain,
    private val probes: ProbeDao,
    private val flags: FeatureFlags,
    private val pool: PoolDao,
    private val scheduler: GenerationScheduler,
    private val thermal: com.offlinetutor.llm.ThermalMonitor,
    private val textbook: TextbookService? = null,
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

    /** A validated (and, for diagnostic/template items, tagged) question before it is stored or pooled. */
    data class Made(
        val stem: String, val options: List<String>, val answer: Int, val explanation: String, val meta: String?,
        val source: String, val verdict: String?, val genMs: Long, val attempts: Int,
        /** Template items: shareable parameters (5b). */
        val spec: String? = null,
    )

    /**
     * Adaptive next question. Target: a pending probe first, then a forced subtopic/concept, then the current
     * roadmap step (NORMAL or REVIEW). Level: per-concept Elo rules (toggle) or the 2-up/1-down staircase.
     *
     * 3b serving order (ready-pool toggle on): a ready pool item for (concept, level), deduped against history at
     * serve time (3f) → if the pool is empty, an instant Kotlin template (no LLM call) while the pool refills →
     * live generation (template / diagnostic / plain MCQ, validate → dedupe, retry once) → the seed bank.
     * Every served question records its perceived wait and where it came from.
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
        val t0 = android.os.SystemClock.elapsedRealtime()
        dao.dropUnanswered(profile.id)
        val answered = dao.answeredForCourse(profile.id, course)
        val weak = WeakSpots.weak(answered.map { it.subtopic to (it.correct == true) })
        val t = pickTarget(profile.id, course, forcedSubtopic, forcedConcept, answered, weak, purpose)
        val level = levelFor(profile.id, course, t.concept)
        val history = dao.allNormalizedStems(profile.id).toMutableSet()
        val usePool = flags.isOn(Feature.READY_POOL) && focus == null

        suspend fun serve(
            m: Made, from: String, subtopic: String = t.subtopic, difficulty: Int = level, concept: String = t.concept,
            note: String? = null,
        ): Next {
            val q = QuizQuestion(
                profileId = profile.id, course = course, subtopic = subtopic, difficulty = difficulty,
                stem = m.stem, normalizedStem = QuestionDeduper.normalize(m.stem),
                options = m.options.joinToString(QuizQuestion.OPTION_SEP.toString()),
                answerIndex = m.answer, explanation = m.explanation, source = m.source,
                concept = concept, optionMeta = m.meta, purpose = t.purpose, verdict = m.verdict,
                waitMs = android.os.SystemClock.elapsedRealtime() - t0, servedFrom = from, genMs = m.genMs,
            )
            val stored = q.copy(id = dao.insert(q))
            if (usePool) shadow(profile, course, t.concept, level)
            return Next(stored, m.attempts, note)
        }

        if (usePool) {
            takeFromPool(profile.id, t.concept, level, history)?.let { item ->
                return serve(Made(item.stem, item.options.split(QuizQuestion.OPTION_SEP), item.answerIndex, item.explanation,
                    item.optionMeta, item.source, item.verdict, item.genMs, 1), "pool", subtopic = item.subtopic)
            }
            // Pool empty: an instant Kotlin template (Kotlin's own wording, no LLM call) while the pool refills.
            instantTemplate(course, t, level, history)?.let { m -> return serve(m, "instant-template") }
        }

        val (made, lastReason) = make(profile, course, t, level, history, weak, focus, onPartial)
        if (made != null) return serve(made, "live")

        val seed = seeds.pick(course, level, history)
            ?: throw IllegalStateException("Could not create a question ($lastReason) and the seed bank is used up.")
        // A bank question is credited to its own topic, not to the step that was targeted.
        val seedConcept = Concepts.slug(seed.topic)
        val d = seed.toDiag(seedConcept).shuffled()
        return serve(Made(d.question, d.options.map { it.text }, d.answer, d.explanation,
            d.metaJson().takeIf { d.options.any { it.code != null } }, "seed", null, 0, 2), "seed",
            subtopic = seed.topic, difficulty = seed.difficulty, concept = seedConcept, note = lastReason)
    }

    private suspend fun levelFor(profileId: Long, course: String, concept: String): Int =
        if (flags.isOn(Feature.ELO_DIFFICULTY)) brain.masteryState(profileId, concept)?.level ?: AdaptiveDifficulty.EASY
        else state(profileId, course).level

    /**
     * One validated question for [t] at [level]: a Kotlin template when the topic is numeric (2-fix-2), else a
     * diagnostic (tagged) or plain LLM MCQ, retried once. Shared by live serving (P0) and pool refill (P1, via the
     * caller's GenPriority context). Returns the question or the last failure reason.
     */
    private suspend fun make(
        profile: Profile, course: String, t: Target, level: Int, history: Set<String>, weak: List<String>,
        focus: String?, onPartial: (String) -> Unit,
    ): Pair<Made?, String?> {
        val start = android.os.SystemClock.elapsedRealtime()
        fun ms() = android.os.SystemClock.elapsedRealtime() - start
        if (flags.isOn(Feature.TEMPLATES)) {
            ProblemTemplates.forTopic("${t.subtopic} ${t.conceptTitle}", course)?.let { type ->
                templates.build(profile.grade, type, level, t.concept, history, LogMeta(), onPartial)?.let { r ->
                    val s = r.mcq.shuffled()
                    return Made(s.question, s.options.map { it.text }, s.answer, s.explanation, s.metaJson(), "template",
                        Verdict.TEMPLATE.name, ms(), 1, spec = r.spec) to null
                }
            }
        }
        val recent = dao.recentStems(profile.id, course, 10).toMutableList()
        // 6b: the student's own textbook passages for this topic, when textbook mode has pages that match.
        val grounding = textbook?.grounding(profile.id, "${t.subtopic} ${t.conceptTitle}")
        var lastReason: String? = null
        for (attempt in 1..2) {
            if (flags.isOn(Feature.DIAGNOSTIC_MCQ)) {
                val o = diag.attempt(profile.grade, course, t.conceptTitle, t.concept, t.subtopic, level, recent, weak, history,
                    LogMeta(), focus, onPartial, grounding)
                o.mcq?.let { d ->
                    val s = d.shuffled()
                    return Made(s.question, s.options.map { it.text }, s.answer, s.explanation, s.metaJson(), "llm",
                        o.verdict?.name, ms(), attempt) to null
                }
                lastReason = o.reason
            } else {
                val o = generator.attempt(profile.grade, course, t.subtopic, level, recent, weak, history, LogMeta(), onPartial, focus, grounding = grounding)
                o.mcq?.let { mcq ->
                    val s = mcq.shuffled()
                    return Made(s.question, s.options, s.answer, s.explanation, null, "llm", o.verdict?.name, ms(), attempt) to null
                }
                lastReason = o.reason
                o.result.text.let { txt -> com.offlinetutor.domain.McqParser.parse(txt) }
                    .let { (it as? com.offlinetutor.domain.McqParse.Ok)?.mcq?.question }
                    ?.let { recent.add(0, it) }
            }
        }
        return null to lastReason
    }

    /** Kotlin template with Kotlin's own wording: no LLM call, so it is served instantly (3b). */
    private fun instantTemplate(course: String, t: Target, level: Int, history: Set<String>): Made? {
        if (!flags.isOn(Feature.TEMPLATES)) return null
        val type = ProblemTemplates.forTopic("${t.subtopic} ${t.conceptTitle}", course) ?: return null
        repeat(3) {
            val s = ProblemTemplates.generate(type, level).toDiag(t.concept).shuffled()
            if (!QuestionDeduper.isDuplicate(s.question, history)) {
                return Made(s.question, s.options.map { it.text }, s.answer, s.explanation, s.metaJson(), "template",
                    Verdict.TEMPLATE.name, 0, 1)
            }
        }
        return null
    }

    /** Oldest ready item for (concept, level); items that became duplicates since they were made are dropped (3f). */
    private suspend fun takeFromPool(profileId: Long, concept: String, level: Int, history: Set<String>): PoolItem? {
        while (true) {
            val item = pool.oldest(profileId, concept, level) ?: return null
            pool.delete(item.id)
            if (!QuestionDeduper.isDuplicate(item.stem, history)) return item
        }
    }

    /**
     * 3b shadow pre-generation, called when a question is served (it is on screen while this runs): refill one item
     * at the predicted next level (the current one); only when the device is cool and that level is already full,
     * one candidate a level up and one down.
     */
    private suspend fun shadow(profile: Profile, course: String, concept: String, level: Int) {
        refill(profile, course, concept, level, items = 1)
        if (flags.isOn(Feature.SHADOW_PREGEN) && pool.count(profile.id, concept, level) >= PoolPolicy.PER_LEVEL && cool()) {
            PoolPolicy.neighbours(level).forEach { refill(profile, course, concept, it, items = 1) }
        }
    }

    /**
     * Queues a background refill of (concept, level) up to [PoolPolicy.PER_LEVEL] ready items, generating at most
     * [items]. Respects the per-profile pool cap. P1 by default; the night shift uses P2.
     */
    fun refill(
        profile: Profile, course: String, concept: String, level: Int, items: Int = PoolPolicy.PER_LEVEL,
        priority: com.offlinetutor.llm.Priority = com.offlinetutor.llm.Priority.P1,
    ): Boolean {
        if (!flags.isOn(Feature.READY_POOL)) return false
        return scheduler.submit(GenerationScheduler.Job(priority, "pool:${profile.id}:$course:$concept:$level") {
            fillPool(profile, course, concept, level, items)
        })
    }

    /**
     * Generates up to [maxItems] ready items for (concept, level) in the caller's priority context, stopping at
     * [PoolPolicy.PER_LEVEL] for that level or [PoolPolicy.PROFILE_CAP] for the profile. Returns items added.
     */
    suspend fun fillPool(profile: Profile, course: String, concept: String, level: Int, maxItems: Int = PoolPolicy.PER_LEVEL): Int {
        var added = 0
        repeat(maxItems) {
            if (pool.count(profile.id, concept, level) >= PoolPolicy.PER_LEVEL) return added
            if (pool.countForProfile(profile.id) >= PoolPolicy.PROFILE_CAP) return added
            val answered = dao.answeredForCourse(profile.id, course)
            val weak = WeakSpots.weak(answered.map { it.subtopic to (it.correct == true) })
            val t = pickTarget(profile.id, course, null, concept, answered, weak, null)
            val history = (dao.allNormalizedStems(profile.id) + pool.stems(profile.id)).toSet()
            val m = make(profile, course, t, level, history, weak, null) {}.first ?: return@repeat
            pool.insert(PoolItem(
                profileId = profile.id, course = course, concept = concept, subtopic = t.subtopic, level = level,
                stem = m.stem, normalizedStem = QuestionDeduper.normalize(m.stem),
                options = m.options.joinToString(QuizQuestion.OPTION_SEP.toString()), answerIndex = m.answer,
                explanation = m.explanation, optionMeta = m.meta, source = m.source, verdict = m.verdict, genMs = m.genMs,
                spec = m.spec,
            ))
            added++
        }
        return added
    }

    /** 5a: a battle question with its key and the misconception behind each option (null for the key). */
    data class BattleItem(val stem: String, val options: List<String>, val answer: Int, val codes: List<String?>)

    /**
     * 5a: [n] questions for a class battle on [concept] (or the current step): ready pool items first, and only
     * what is missing is generated live. Pool items used here are removed from the pool.
     */
    suspend fun battleQuestions(profile: Profile, course: String, concept: String?, n: Int = 5, onPartial: (String) -> Unit = {}): List<BattleItem> {
        val answered = dao.answeredForCourse(profile.id, course)
        val t = pickTarget(profile.id, course, null, concept, answered, emptyList(), null)
        fun codes(meta: String?, size: Int) = com.offlinetutor.domain.OptionMeta.parse(meta).let { tags -> (0 until size).map { tags.getOrNull(it)?.code?.name } }
        val out = ArrayList<BattleItem>()
        for (item in pool.forConcept(profile.id, t.concept, n)) {
            pool.delete(item.id)
            val opts = item.options.split(QuizQuestion.OPTION_SEP)
            out.add(BattleItem(item.stem, opts, item.answerIndex, codes(item.optionMeta, opts.size)))
        }
        val history = (dao.allNormalizedStems(profile.id) + out.map { QuestionDeduper.normalize(it.stem) }).toMutableSet()
        var tries = 0
        while (out.size < n && tries++ < n * 2) {
            val m = make(profile, course, t, levelFor(profile.id, course, t.concept), history, emptyList(), null, onPartial).first ?: continue
            history.add(QuestionDeduper.normalize(m.stem))
            out.add(BattleItem(m.stem, m.options, m.answer, codes(m.meta, m.options.size)))
        }
        return out
    }

    /** Current level for a concept (Elo rules or staircase), for background planners. */
    suspend fun currentLevel(profileId: Long, course: String, concept: String): Int = levelFor(profileId, course, concept)

    /** 3b warm-up (app open, roadmap shown): start refilling the pool for the current step at the student's level. */
    suspend fun warmUp(profile: Profile, course: String) {
        if (!flags.isOn(Feature.READY_POOL)) return
        val answered = dao.answeredForCourse(profile.id, course)
        val t = pickTarget(profile.id, course, null, null, answered, emptyList(), null)
        refill(profile, course, t.concept, levelFor(profile.id, course, t.concept))
    }

    private fun cool(): Boolean = thermal.currentStatus() == 0

    /** For the benchmark screen: the last [n] served questions (wait + where they came from). */
    suspend fun recentServed(n: Int) = dao.recentServed(n)

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

class ChatRepository(
    private val dao: ChatDao,
    private val ai: TutorAi,
    private val analogies: com.offlinetutor.domain.AnalogyBank? = null,
    private val flags: FeatureFlags? = null,
) {
    /** 4b: analogy from the student's world for this text (toggle "Analogy worlds"). */
    private fun analogyFor(profile: Profile, text: String, course: String?): String? =
        if (flags?.isOn(Feature.ANALOGY_WORLDS) == false) null else analogies?.find(text, profile.world, course)

    fun observe(profileId: Long) = dao.observe(profileId)
    suspend fun clear(profileId: Long) = dao.clear(profileId)

    suspend fun send(profile: Profile, text: String, course: String?, onPartial: (String) -> Unit): ChatMessage {
        val lang = ExplainLanguage.of(profile.language)
        dao.insert(ChatMessage(profileId = profile.id, role = "user", text = text.trim(), language = lang.code))
        val history = dao.recent(profile.id, 8).reversed().map { Prompts.ChatTurn(it.role == "user", it.text) }
        val r = ai.run(GenRequest(TaskType.CHAT, Prompts.chat(profile.grade, lang, course, history, analogyFor(profile, text, course))), onPartial = onPartial)
        val reply = r.text.ifBlank { r.error?.let { "⚠ $it" } ?: "…" }
        val msg = ChatMessage(profileId = profile.id, role = "assistant", text = reply, language = lang.code)
        return msg.copy(id = dao.insert(msg))
    }

    suspend fun explain(profile: Profile, lang: ExplainLanguage, q: QuizQuestion, onPartial: (String) -> Unit): String {
        val analogy = analogyFor(profile, "${q.subtopic} ${q.stem}", q.course)
        val prompt = Prompts.explainAnswer(profile.grade, lang, q.stem, q.optionList.getOrElse(q.answerIndex) { "" }, q.explanation, analogy)
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
            "thermal_status", "thermal_policy", "stop_reason", "guard_reason", "valid_json", "duplicate", "error", "verdict", "fail_reason",
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
                    l.guardReason, l.validJson, l.duplicate, l.error, l.verdict, l.failReason, l.output, l.rawStats, "", "", "",
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
