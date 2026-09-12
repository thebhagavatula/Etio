package com.etio.ot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.etio.ot.data.settings.SettingsStore
import com.etio.ot.data.settings.ThemeMode
import com.etio.ot.di.CoreModule
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsStore = CoreModule.settingsStore,
) : ViewModel() {

    val themeMode = settings.themeMode

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel() as T
        }
    }
}
