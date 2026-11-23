package com.offlinetutor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetutor.data.AppearanceStore
import com.offlinetutor.ui.AppNav
import com.offlinetutor.ui.design.AppBackground
import com.offlinetutor.ui.theme.OfflineTutorTheme

/** AppCompatActivity so per-app UI language (values-ta / values-bn) works below Android 13 too. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appearance = (application as TutorApp).container.appearance
        setContent {
            val look by appearance.state.collectAsStateWithLifecycle()
            val dark = when (look.theme) {
                AppearanceStore.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceStore.ThemeMode.LIGHT -> false
                AppearanceStore.ThemeMode.DARK -> true
            }
            // Status / navigation bar icons follow the in-app theme, not only the phone's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            OfflineTutorTheme(darkOverride = dark) {
                AppBackground(look.background) { AppNav() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Model files may have arrived while we were away (adb push, file manager), so rescan.
        (application as TutorApp).container.engine.refreshAvailability()
    }
}
