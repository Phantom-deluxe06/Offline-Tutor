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
    val mcqGenerator = McqGenerator(ai)

    val profiles = ProfileRepository(db.profiles(), session)
    val roadmaps = RoadmapRepository(db.roadmaps(), ai)
    val quiz = QuizRepository(db.quiz(), db.roadmaps(), mcqGenerator, seeds)
    val chat = ChatRepository(db.chat(), ai)
    val metrics = MetricsRepository(db.logs())
    val bench = BenchmarkRunner(app, appScope, engine, ai, mcqGenerator, metrics)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeProfile: StateFlow<Profile?> = session.activeProfileId
        .flatMapLatest { id -> if (id == null) flowOf(null) else profiles.observe(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)
}
