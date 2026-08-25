package io.github.kemko.grimmoryuploader.ui.onboarding

import io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision
import io.github.kemko.grimmoryuploader.data.auth.AuthModeSelector
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository

class OnboardingViewModel(
    private val settings: AppSettingsRepository,
    private val auth: AuthRepository,
) {
    suspend fun configureServer(rawUrl: String, confirmCleartext: Boolean): Result<AuthModeDecision> = runCatching {
        val normalized = ServerUrl.parse(rawUrl).normalized
        check(!normalized.startsWith("http://") || confirmCleartext) { "HTTP requires explicit confirmation" }
        settings.setServerUrl(normalized)
        if (normalized.startsWith("http://")) settings.setHttpConfirmed(true)
        auth.apiHealthAndSettings()
    }

    private suspend fun AuthRepository.apiHealthAndSettings(): AuthModeDecision {
        healthcheck()
        val publicSettings = runCatching { publicSettings() }.getOrNull()
        return AuthModeSelector.select(settings.current().authMode, publicSettings)
    }
}
