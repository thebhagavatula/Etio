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
import com.etio.ot.ui.theme.EtioTheme

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

            EtioTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    EtioApp()
                }
            }
        }
    }
}
