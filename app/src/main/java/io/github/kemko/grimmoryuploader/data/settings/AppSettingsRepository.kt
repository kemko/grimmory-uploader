package io.github.kemko.grimmoryuploader.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AuthMode { AUTO, LOCAL, OIDC }

data class AppSettings(
    val serverUrl: String? = null,
    val libraryId: Int = 1,
    val pathId: Int = 1,
    val recompressEpub: Boolean = true,
    val httpConfirmed: Boolean = false,
    val authMode: AuthMode = AuthMode.AUTO,
)

class AppSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val onServerChanged: suspend () -> Unit = {},
) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val libraryId = intPreferencesKey("library_id")
        val pathId = intPreferencesKey("path_id")
        val recompressEpub = booleanPreferencesKey("recompress_epub")
        val httpConfirmed = booleanPreferencesKey("http_confirmed")
        val authMode = stringPreferencesKey("auth_mode")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            serverUrl = preferences[Keys.serverUrl],
            libraryId = preferences[Keys.libraryId] ?: 1,
            pathId = preferences[Keys.pathId] ?: 1,
            recompressEpub = preferences[Keys.recompressEpub] ?: true,
            httpConfirmed = preferences[Keys.httpConfirmed] ?: false,
            authMode = preferences[Keys.authMode]
                ?.let { value -> runCatching { AuthMode.valueOf(value) }.getOrDefault(AuthMode.AUTO) }
                ?: AuthMode.AUTO,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setServerUrl(raw: String) {
        val normalized = ServerUrl.parse(raw).normalized
        val previous = current().serverUrl
        if (previous != null && previous != normalized) onServerChanged()
        dataStore.edit { preferences ->
            preferences[Keys.serverUrl] = normalized
            preferences[Keys.httpConfirmed] = false
        }
    }

    suspend fun setLibraryId(value: Int) {
        require(value > 0) { "libraryId must be positive" }
        dataStore.edit { it[Keys.libraryId] = value }
    }

    suspend fun setPathId(value: Int) {
        require(value > 0) { "pathId must be positive" }
        dataStore.edit { it[Keys.pathId] = value }
    }

    suspend fun setRecompressEpub(enabled: Boolean) {
        dataStore.edit { it[Keys.recompressEpub] = enabled }
    }

    suspend fun setHttpConfirmed(confirmed: Boolean) {
        dataStore.edit { it[Keys.httpConfirmed] = confirmed }
    }

    suspend fun setAuthMode(mode: AuthMode) {
        dataStore.edit { it[Keys.authMode] = mode.name }
    }

    suspend fun requireServerUrl(): ServerUrl =
        current().serverUrl?.let(ServerUrl::parse) ?: error("Server URL is not configured")

    suspend fun requireCleartextConfirmation() {
        val value = current()
        if (value.serverUrl?.let(ServerUrl::parse)?.isCleartext == true) {
            check(value.httpConfirmed) { "Cleartext HTTP requires confirmation" }
        }
    }

    suspend fun isCleartextConfirmed(url: String): Boolean =
        !ServerUrl.parse(url).isCleartext || current().httpConfirmed
}
