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
        confirmServerChange: Boolean = false,
    ): Boolean {
        val old = container.settings.current()
        val normalizedNew = io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse(serverUrl).normalized
        require(libraryId > 0) { "libraryId must be positive" }
        require(pathId > 0) { "pathId must be positive" }
        check(!normalizedNew.startsWith("http://") || confirmCleartext) { "HTTP requires explicit confirmation" }
        val serverChanged = old.serverUrl != null && old.serverUrl != normalizedNew
        if (serverChanged && !confirmServerChange) throw ServerChangeConfirmationRequired
        if (serverChanged) {
            container.upload.pending()
                .filter { it.serverUrl == old.serverUrl }
                .forEach { container.transferScheduler.cancel(it.id) }
            container.upload.cancelForServer(old.serverUrl)
        }
        container.settings.applyConfiguration(
            normalizedNew,
            libraryId,
            pathId,
            recompressEpub,
            authMode,
            confirmCleartext,
        )
        return serverChanged
    }
}

data object ServerChangeConfirmationRequired : IllegalStateException("Confirm changing the Grimmory server")
