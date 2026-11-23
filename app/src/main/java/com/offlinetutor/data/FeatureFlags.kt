package com.offlinetutor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every new feature can be switched off in Settings so it can be demoed and benchmarked on vs off. */
enum class Feature(val phase: Int, /** Spec: default ON; 3d prefill stays off until measured to help (spec: keep it only if it does). */ val defaultOn: Boolean = true) {
    DIAGNOSTIC_MCQ(1),
    CONFIDENCE(1),
    ELO_DIFFICULTY(1),
    SELF_HEALING(1),
    BACKTRACKING(1),
    SKILL_TREE(1),
    TEMPLATES(2),
    READY_POOL(3),
    SHADOW_PREGEN(3),
    NIGHT_SHIFT(3),
    PROMPT_PREFILL(3, defaultOn = false),
    THERMAL_GOVERNOR(3),
    ANALOGY_WORLDS(4),
    CLASSROOM_MESH(5),
    TEACH_KUTTY(6),
    TEXTBOOK_MODE(6);

    companion object {
        /** Toggles for later phases stay hidden until that phase is built. */
        const val IMPLEMENTED_PHASE = 6
    }
}

class FeatureFlags(context: Context) {
    private val prefs = context.getSharedPreferences("features", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Map<Feature, Boolean>> = _state.asStateFlow()

    private fun read() = Feature.entries.associateWith { prefs.getBoolean(it.name, it.defaultOn) }

    fun isOn(f: Feature): Boolean = _state.value[f] ?: f.defaultOn

    fun set(f: Feature, on: Boolean) {
        prefs.edit().putBoolean(f.name, on).apply()
        _state.value = read()
    }
}
