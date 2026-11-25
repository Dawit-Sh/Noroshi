package dev.dawitsh.noroshi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dawitsh.noroshi.core.model.Settings
import dev.dawitsh.noroshi.core.work.UsageNotifier
import dev.dawitsh.noroshi.ui.NoroshiApp
import dev.dawitsh.noroshi.ui.theme.NoroshiTheme
import dev.dawitsh.noroshi.ui.theme.isDark

class MainActivity : ComponentActivity() {
    /** Bumped when a data alert is tapped, so the app opens on the Data screen. */
    private var openData by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) noteOpen(intent)
        enableEdgeToEdge()
        val container = (application as NoroshiApplication).container

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(Settings())
            val dark = settings.theme.isDark()

            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            NoroshiTheme(darkTheme = dark) {
                NoroshiApp(container.notices, openData = openData)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        noteOpen(intent)
    }

    private fun noteOpen(intent: Intent?) {
        if (intent?.getStringExtra(UsageNotifier.EXTRA_OPEN) == UsageNotifier.OPEN_DATA) openData++
    }
}
