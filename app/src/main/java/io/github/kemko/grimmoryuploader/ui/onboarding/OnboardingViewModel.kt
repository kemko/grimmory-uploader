package io.github.kemko.grimmoryuploader.ui.onboarding

import io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision
import io.github.kemko.grimmoryuploader.data.auth.AuthModeSelector
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository

class OnboardingViewModel(
    private val settings: AppSettingsRepository,
    private val probe: suspend (ServerUrl) -> PublicSettings?,
) {
    suspend fun configureServer(rawUrl: String, confirmCleartext: Boolean): Result<AuthModeDecision> = runCatching {
        val normalized = ServerUrl.parse(rawUrl).normalized
        check(!normalized.startsWith("http://") || confirmCleartext) { "HTTP requires explicit confirmation" }
        val previous = settings.current()
        val publicSettings = probe(ServerUrl.parse(normalized))
        val decision = AuthModeSelector.select(previous.authMode, publicSettings)
        settings.applyConfiguration(
            normalized,
            previous.libraryId,
            previous.pathId,
            previous.recompressEpub,
            previous.authMode,
            confirmCleartext,
        )
        decision
    }
}
