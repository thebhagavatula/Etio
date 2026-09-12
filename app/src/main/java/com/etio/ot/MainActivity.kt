package com.etio.ot

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etio.ot.data.settings.ThemeMode
import com.etio.ot.di.CoreModule
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.etio.ot.ui.EtioApp
import com.etio.ot.ui.splash.SplashScreen
import com.etio.ot.ui.splash.SplashTiming
import com.etio.ot.ui.tutorial.TutorialHost
import com.etio.ot.ui.theme.EtioTheme
import com.etio.ot.di.AiModule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The delay screen reflects the result; nothing else depends on it. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone sits on a podium during the pitch. It must not sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        micPermission.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            // Persisted choice, or the system's, resolved before anything draws.
            val mode by CoreModule.settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Null until DataStore answers; showing neither for that frame is better
            // than flashing the tutorial at someone who has already done it.
            val tutorialDone by CoreModule.settingsStore.tutorialCompleted
                .collectAsStateWithLifecycle(initialValue = null as Boolean?)

            val splashOverride by CoreModule.settingsStore.splashDurationMs
                .collectAsStateWithLifecycle(initialValue = null)
            var splashDone by remember { mutableStateOf(false) }

            EtioTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        // The splash is where the model loads. Warm-up is handed in
                        // rather than started here, so the splash owns when it runs
                        // and nothing animates over the top of it.
                        !splashDone -> SplashScreen(
                            onDone = { splashDone = true },
                            durationMs = SplashTiming.sanitise(splashOverride),
                            warmUp = { AiModule.llmEngine.warmUp() },
                        )
                        tutorialDone == null -> Unit
                        tutorialDone == false -> TutorialHost(onFinished = { })
                        else -> EtioApp()
                    }
                }
            }
        }
    }
}
