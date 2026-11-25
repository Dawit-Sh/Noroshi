package dev.dawitsh.noroshi.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.dawitsh.noroshi.core.model.RouterType
import dev.dawitsh.noroshi.core.model.Settings
import dev.dawitsh.noroshi.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    private val type = stringPreferencesKey("router_type")
    private val router = stringPreferencesKey("router")
    private val username = stringPreferencesKey("username")
    private val password = stringPreferencesKey("password")
    private val theme = stringPreferencesKey("theme")
    private val lastRecipientKey = stringPreferencesKey("last_recipient")

    val settings: Flow<Settings> = store.data.map { prefs ->
        val defaults = Settings()
        Settings(
            type = RouterType.fromName(prefs[type]) ?: defaults.type,
            router = prefs[router] ?: defaults.router,
            username = prefs[username] ?: defaults.username,
            password = prefs[password] ?: defaults.password,
            theme = prefs[theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: defaults.theme,
        )
    }

    /** False until a router type was saved or detected; until then the app tries to find the router itself. */
    val typeKnown: Flow<Boolean> = store.data.map { RouterType.fromName(it[type]) != null }

    val lastRecipient: Flow<String> = store.data.map { it[lastRecipientKey] ?: "" }

    suspend fun save(updated: Settings) {
        store.edit {
            it[type] = updated.type.name
            it[router] = updated.router
            it[username] = updated.username
            it[password] = updated.password
            it[theme] = updated.theme.name
        }
    }

    /** Saves a detected router without touching the login or the theme. */
    suspend fun saveDetected(detectedType: RouterType, host: String) {
        store.edit {
            it[type] = detectedType.name
            it[router] = host
        }
    }

    suspend fun rememberRecipient(number: String) {
        store.edit { it[lastRecipientKey] = number }
    }
}
