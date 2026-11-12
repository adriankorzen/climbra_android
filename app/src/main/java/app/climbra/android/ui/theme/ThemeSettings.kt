package app.climbra.android.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Light / dark theme: like the system unless the rider picked one (spec 004, the web's `ThemeService`). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The theme choice, kept on the device as the web keeps it in the browser. */
interface ThemeSettings {
    val mode: Flow<ThemeMode>

    suspend fun setMode(mode: ThemeMode)
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreThemeSettings(context: Context) : ThemeSettings {

    private val store = context.applicationContext.settingsStore

    override val mode: Flow<ThemeMode> = store.data.map { preferences ->
        ThemeMode.entries.firstOrNull { it.name == preferences[KEY] } ?: ThemeMode.SYSTEM
    }

    override suspend fun setMode(mode: ThemeMode) {
        store.edit { it[KEY] = mode.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("theme")
    }
}
