package com.offlinetutor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.offlinetutor.ui.AppNav
import com.offlinetutor.ui.design.PaperBackground
import com.offlinetutor.ui.theme.OfflineTutorTheme

/** AppCompatActivity so per-app UI language (values-ta / values-bn) works below Android 13 too. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfflineTutorTheme {
                PaperBackground { AppNav() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Model files may have arrived while we were away (adb push, file manager), so rescan.
        (application as TutorApp).container.engine.refreshAvailability()
    }
}
