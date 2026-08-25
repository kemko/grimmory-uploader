package io.github.kemko.grimmoryuploader.ui.auth

import android.content.Intent
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import io.github.kemko.grimmoryuploader.di.AppContainer

class AuthViewModel(private val container: AppContainer) {
    private val auth: AuthRepository = container.auth

    suspend fun isAuthenticated(): Boolean = runCatching {
        auth.validAccessToken() != null && auth.currentUser().let { true }
    }.getOrElse {
        auth.logout()
        false
    }

    suspend fun login(username: String, password: String): Result<TokenPair> = runCatching {
        auth.login(username, password)
    }

    suspend fun startOidc(): Result<Intent> = runCatching { container.oidc.start() }

    suspend fun resumeTransfers() {
        container.transferScheduler.resumeAwaitingAuth()
    }

    suspend fun saveMode(mode: AuthMode) = container.settings.setAuthMode(mode)
}

class AuthSettingsViewModel(private val settings: AppSettingsRepository) {
    suspend fun mode(): AuthMode = settings.current().authMode
}
