package com.etio.ot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.settings.AppSettings
import com.etio.ot.data.settings.ThemeMode
import com.etio.ot.di.CoreModule
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: AppSettings = CoreModule.settingsStore,
) : ViewModel() {

    val themeMode = settings.themeMode

    val splashDurationMs = settings.splashDurationMs

    fun setSplashDurationMs(ms: Long?) {
        viewModelScope.launch { settings.setSplashDurationMs(ms) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /**
     * Clearing the flag is all it takes — MainActivity watches it, so the tutorial
     * takes over the moment it flips. The sandbox replaces the current day, which is
     * why the caller confirms first.
     */
    fun replayTutorial() {
        viewModelScope.launch { settings.setTutorialCompleted(false) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel() as T
        }
    }
}
