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
    val mcqGenerator = McqGenerator(ai, flags)
    val templateGenerator = com.offlinetutor.data.TemplateGenerator(ai)
    val taxonomy = com.offlinetutor.domain.MisconceptionTaxonomy.parse(
        app.assets.open("misconceptions.json").bufferedReader().use { it.readText() },
    )
    val diagGenerator = com.offlinetutor.data.DiagnosticGenerator(ai, mcqGenerator)
    val brain = com.offlinetutor.data.AdaptiveBrain(db.roadmaps(), db.quiz(), db.mastery(), db.misconceptions(), db.probes(), flags)

    val profiles = ProfileRepository(db.profiles(), session)
    val roadmaps = RoadmapRepository(db.roadmaps(), ai)
    // 3a: background (P1/P2) generation queue in front of the single LLM worker.
    val scheduler = com.offlinetutor.data.GenerationScheduler(appScope)
    val quiz = QuizRepository(db.quiz(), db.roadmaps(), mcqGenerator, diagGenerator, templateGenerator, seeds, brain, db.probes(), flags,
        db.pool(), scheduler, thermal)
    val fixes = com.offlinetutor.data.FixService(db.roadmaps(), quiz, ai, taxonomy)
    val demo = com.offlinetutor.data.DemoSeeder(profiles, db.roadmaps(), db.quiz(), brain)
    val chat = ChatRepository(db.chat(), ai)
    val metrics = MetricsRepository(db.logs())
    val bench = BenchmarkRunner(app, appScope, engine, ai, mcqGenerator, diagGenerator, templateGenerator, flags, metrics)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeProfile: StateFlow<Profile?> = session.activeProfileId
        .flatMapLatest { id -> if (id == null) flowOf(null) else profiles.observe(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        // 3b warm-up on app open: when a profile becomes active, start filling the ready pool for its courses.
        appScope.launch {
            activeProfile.filterNotNull().distinctUntilChangedBy { it.id }.collect { p ->
                p.courseList.take(2).forEach { course -> runCatching { quiz.warmUp(p, course) } }
            }
        }
    }
}
