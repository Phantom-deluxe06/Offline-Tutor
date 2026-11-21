package com.offlinetutor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every new feature can be switched off in Settings so it can be demoed and benchmarked on vs off. */
enum class Feature(val phase: Int) {
    DIAGNOSTIC_MCQ(1),
    CONFIDENCE(1),
    ELO_DIFFICULTY(1),
    SELF_HEALING(1),
    BACKTRACKING(1),
    SKILL_TREE(1),
    TEMPLATES(2),
    VERIFIER(2);

    companion object {
        /** Toggles for later phases stay hidden until that phase is built. */
        const val IMPLEMENTED_PHASE = 2
    }
}

class FeatureFlags(context: Context) {
    private val prefs = context.getSharedPreferences("features", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Map<Feature, Boolean>> = _state.asStateFlow()

    private fun read() = Feature.entries.associateWith { prefs.getBoolean(it.name, true) }

    fun isOn(f: Feature): Boolean = _state.value[f] ?: true

    fun set(f: Feature, on: Boolean) {
        prefs.edit().putBoolean(f.name, on).apply()
        _state.value = read()
    }
}
