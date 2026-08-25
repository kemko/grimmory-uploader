package io.github.kemko.grimmoryuploader.ui.auth

import android.content.Intent
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer

class AuthViewModel(private val container: AppContainer) {
    private val auth: AuthRepository = container.auth

    suspend fun isAuthenticated(): Boolean {
        if (auth.validAccessToken() == null) return false
        return try {
            auth.currentUser()
            true
        } catch (error: io.github.kemko.grimmoryuploader.data.network.ApiException) {
            if (error.statusCode == 401) {
                auth.logout()
                false
            } else {
                throw error
            }
        }
    }

    suspend fun login(username: String, password: String): Result<TokenPair> = runCatching {
        auth.login(username, password)
    }

    suspend fun startOidc(): Result<Intent> = runCatching { container.oidc.start() }

    suspend fun resumeTransfers() {
        container.transferScheduler.resumeAwaitingAuth()
    }

    suspend fun modeDecision(): io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision {
        val settings = container.settings.current()
        val publicSettings = runCatching { auth.publicSettings() }.getOrNull()
        return io.github.kemko.grimmoryuploader.data.auth.AuthModeSelector.select(
            settings.authMode,
            publicSettings,
        )
    }

    suspend fun selectMode(mode: AuthMode) = container.settings.setAuthMode(mode)
}
