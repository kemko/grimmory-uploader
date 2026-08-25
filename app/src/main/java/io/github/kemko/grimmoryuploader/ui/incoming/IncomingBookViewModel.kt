package io.github.kemko.grimmoryuploader.ui.incoming

import android.content.ContentResolver
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.UploadSettingsSnapshot
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState

class IncomingBookViewModel(private val container: AppContainer) {
    suspend fun persistAndPrepare(
        input: IncomingInput,
        resolver: ContentResolver?,
        beforeSchedule: () -> Unit = {},
    ): Result<IncomingPreparation> = runCatching {
        val current = container.settings.current()
        val server = requireNotNull(current.serverUrl) { "Configure a Grimmory server first" }
        val job = container.upload.enqueue(
            input,
            UploadSettingsSnapshot(server, current.libraryId.toLong(), current.pathId.toLong(), current.recompressEpub),
            resolver,
        )
        val token = container.auth.validAccessToken()
        if (token == null) {
            container.upload.transition(job.id, UploadJobState.AWAITING_AUTH)
            IncomingPreparation(job, requiresAuth = true)
        } else {
            beforeSchedule()
            container.upload.transition(job.id, UploadJobState.QUEUED)
            container.transferScheduler.schedule(job.id)
            IncomingPreparation(job, requiresAuth = false)
        }
    }
}

data class IncomingPreparation(val job: UploadJobEntity, val requiresAuth: Boolean)
