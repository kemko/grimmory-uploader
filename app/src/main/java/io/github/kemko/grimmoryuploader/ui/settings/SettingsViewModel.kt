package io.github.kemko.grimmoryuploader.ui.settings

import io.github.kemko.grimmoryuploader.data.settings.AppSettings
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer

class SettingsViewModel(private val container: AppContainer) {
    suspend fun current(): AppSettings = container.settings.current()

    suspend fun save(
        serverUrl: String,
        authMode: AuthMode,
        libraryId: Int,
        pathId: Int,
        recompressEpub: Boolean,
        confirmCleartext: Boolean,
    ) {
        val old = container.settings.current()
        val normalizedNew = io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse(serverUrl).normalized
        if (old.serverUrl != null && old.serverUrl != normalizedNew) {
            container.upload.pending()
                .filter { it.serverUrl == old.serverUrl }
                .forEach { container.transferScheduler.cancel(it.id) }
            container.upload.cancelForServer(old.serverUrl)
        }
        container.settings.setServerUrl(normalizedNew)
        container.settings.setAuthMode(authMode)
        container.settings.setLibraryId(libraryId)
        container.settings.setPathId(pathId)
        container.settings.setRecompressEpub(recompressEpub)
        if (normalizedNew.startsWith("http://")) {
            check(confirmCleartext) { "HTTP requires explicit confirmation" }
            container.settings.setHttpConfirmed(true)
        }
    }
}
