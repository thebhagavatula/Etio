package com.etio.ot.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.etioDataStore by preferencesDataStore(name = "etio_settings")

/** System-follow, or an explicit choice that outlives the process. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The two pieces of state that belong to the person rather than to the day: which
 * theme they picked, and whether they have been through the tutorial.
 *
 * Deliberately separate from Room. The demo reset wipes the database; neither of
 * these should come back with it — least of all the tutorial flag, which must never
 * re-fire on stage.
 */
class SettingsStore(private val context: Context) {

    val themeMode: Flow<ThemeMode> = context.etioDataStore.data.map { prefs ->
        prefs[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.etioDataStore.edit { it[KEY_THEME] = mode.name }
    }

    val tutorialCompleted: Flow<Boolean> =
        context.etioDataStore.data.map { it[KEY_TUTORIAL] ?: false }

    suspend fun setTutorialCompleted(completed: Boolean) {
        context.etioDataStore.edit { it[KEY_TUTORIAL] = completed }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_TUTORIAL = booleanPreferencesKey("tutorial_completed")
    }
}
