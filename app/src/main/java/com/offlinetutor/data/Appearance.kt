package com.offlinetutor.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app appearance: theme (follow phone / light / dark) and background (doodle art / ruled paper / plain). */
class AppearanceStore(context: Context) {
    enum class ThemeMode { SYSTEM, LIGHT, DARK }
    enum class Background { DOODLES, PAPER, PLAIN }

    data class State(val theme: ThemeMode = ThemeMode.SYSTEM, val background: Background = Background.DOODLES)

    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun read() = State(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        runCatching { Background.valueOf(prefs.getString("background", null) ?: "DOODLES") }.getOrDefault(Background.DOODLES),
    )

    fun setTheme(t: ThemeMode) { prefs.edit().putString("theme", t.name).apply(); _state.value = read() }
    fun setBackground(b: Background) { prefs.edit().putString("background", b.name).apply(); _state.value = read() }
}
