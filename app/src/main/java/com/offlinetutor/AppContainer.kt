package com.offlinetutor

import android.app.Application
import com.offlinetutor.data.AppDatabase
import com.offlinetutor.data.BenchmarkRunner
import com.offlinetutor.data.ChatRepository
import com.offlinetutor.data.McqGenerator
import com.offlinetutor.data.MetricsRepository
import com.offlinetutor.data.ProfileRepository
import com.offlinetutor.data.QuizRepository
import com.offlinetutor.data.RoadmapRepository
import com.offlinetutor.data.SessionStore
import com.offlinetutor.data.TutorAi
import com.offlinetutor.domain.SeedBank
import com.offlinetutor.llm.LlmEngine
import com.offlinetutor.llm.ModelStore
import com.offlinetutor.llm.ThermalMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import com.offlinetutor.data.Profile

/** Manual dependency container (no DI framework needed for a single-module app). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app)
    val session = SessionStore(app)
    val modelStore = ModelStore(app)
    val thermal = ThermalMonitor(app)
    val engine = LlmEngine(app, modelStore, thermal)
    val ai = TutorAi(engine, db.logs())
    val seeds = SeedBank(app)
    val flags = com.offlinetutor.data.FeatureFlags(app)
    val appearance = com.offlinetutor.data.AppearanceStore(app)
    val mcqGenerator = McqGenerator(ai, flags)
    val templateGenerator = com.offlinetutor.data.TemplateGenerator(ai)
    val taxonomy = com.offlinetutor.domain.MisconceptionTaxonomy.parse(
        app.assets.open("misconceptions.json").bufferedReader().use { it.readText() },
    )
    val diagGenerator = com.offlinetutor.data.DiagnosticGenerator(ai, mcqGenerator)
    val brain = com.offlinetutor.data.AdaptiveBrain(db.roadmaps(), db.quiz(), db.mastery(), db.misconceptions(), db.probes(), flags)

    val profiles = ProfileRepository(db.profiles(), session)
    val roadmaps = RoadmapRepository(db.roadmaps(), ai)
    // 6b: textbook pages (on-device OCR, FTS4).
    val textbook = com.offlinetutor.data.TextbookService(app, db.textbook(), flags)
    // 3a: background (P1/P2) generation queue in front of the single LLM worker.
    val scheduler = com.offlinetutor.data.GenerationScheduler(appScope) { p -> backgroundPaused(p) }
    val quiz = QuizRepository(db.quiz(), db.roadmaps(), mcqGenerator, diagGenerator, templateGenerator, seeds, brain, db.probes(), flags,
        db.pool(), scheduler, thermal, textbook)
    // 4b: hand-curated analogy bank (concept area x world).
    val analogies = com.offlinetutor.domain.AnalogyBank.parse(app.assets.open("analogies.json").bufferedReader().use { it.readText() })
    val fixes = com.offlinetutor.data.FixService(db.roadmaps(), quiz, ai, taxonomy, analogies, flags)
    val demo = com.offlinetutor.data.DemoSeeder(profiles, db.roadmaps(), db.quiz(), brain)
    val chat = ChatRepository(db.chat(), ai, analogies, flags)
    val metrics = MetricsRepository(db.logs())
    // 3c: Night Shift (WorkManager while charging + idle, or Settings → Simulate night).
    val night = com.offlinetutor.data.NightShift(app, db.profiles(), db.mastery(), db.roadmaps(), quiz, fixes, diagGenerator,
        templateGenerator, ai) { p -> backgroundPaused(p) }
    // Phase 5: classroom mesh (Nearby Connections) and the QR fallback.
    val nearby = com.offlinetutor.data.NearbyTransport(app)
    val battleHost = com.offlinetutor.data.BattleHost(nearby, appScope, ai)
    val battleClient = com.offlinetutor.data.BattleClient(nearby)
    val questionCache = com.offlinetutor.data.QuestionCache(db.pool(), db.quiz())
    val share = com.offlinetutor.data.QuestionShareSession(nearby, appScope, questionCache, ai) {
        // measured median time per fully tagged question on this phone (seconds)
        com.offlinetutor.domain.ServeStats.from(quiz.recentServed(200).map {
            com.offlinetutor.domain.ServeStats.Row(it.servedFrom, it.waitMs, it.genMs, it.source, !it.optionMeta.isNullOrBlank())
        }).medianTaggedGenMs?.let { it / 1000.0 }
    }
    val bench = BenchmarkRunner(app, appScope, engine, ai, mcqGenerator, diagGenerator, templateGenerator, flags, metrics)

    /** 3e: thermal governor (toggle) — hot phones pause background priorities; live requests are never paused. */
    private fun backgroundPaused(p: com.offlinetutor.llm.Priority): Boolean =
        flags.isOn(com.offlinetutor.data.Feature.THERMAL_GOVERNOR) && with(com.offlinetutor.llm.ThermalRules) { paused(p, thermal.currentStatus(), thermal.headroom()) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeProfile: StateFlow<Profile?> = session.activeProfileId
        .flatMapLatest { id -> if (id == null) flowOf(null) else profiles.observe(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        // 3c: keep the nightly job scheduled while the toggle is on.
        appScope.launch {
            flags.state.collect { f ->
                if (f[com.offlinetutor.data.Feature.NIGHT_SHIFT] != false) com.offlinetutor.data.NightShiftScheduler.schedule(app)
                else com.offlinetutor.data.NightShiftScheduler.cancel(app)
                engine.prefillEnabled = f[com.offlinetutor.data.Feature.PROMPT_PREFILL] == true
            }
        }
        // 3d: when the worker goes idle, prefill the last system prompt (P2) so a repeat task starts faster.
        appScope.launch {
            engine.state.collect { s ->
                if (s is com.offlinetutor.llm.EngineState.Ready && engine.prefillEnabled) runCatching { engine.prewarm() }
            }
        }
        // 3b warm-up on app open: when a profile becomes active, start filling the ready pool for its courses.
        appScope.launch {
            activeProfile.filterNotNull().distinctUntilChangedBy { it.id }.collect { p ->
                p.courseList.take(2).forEach { course -> runCatching { quiz.warmUp(p, course) } }
            }
        }
    }
}
