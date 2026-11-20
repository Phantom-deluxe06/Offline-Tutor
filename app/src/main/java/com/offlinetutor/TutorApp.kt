package com.offlinetutor

import android.app.Application
import android.content.ComponentCallbacks2
import com.offlinetutor.llm.EngineState
import kotlinx.coroutines.launch

class TutorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // On a 4 GB phone the model is by far the biggest allocation. When we are in the
        // background and nothing is generating, free it so the low-memory killer spares us.
        // It is mmap-loaded, so reloading on return takes only a few seconds.
        @Suppress("DEPRECATION")
        val backgrounded = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        val idle = container.engine.state.value is EngineState.Ready && !container.bench.state.value.running
        if (backgrounded && idle) {
            container.appScope.launch { container.engine.unload() }
        }
    }
}
