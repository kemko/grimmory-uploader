package io.github.kemko.grimmoryuploader.ui.incoming

import android.content.ContentResolver
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.UploadSettingsSnapshot
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState

class IncomingBookViewModel(private val container: AppContainer) {
    suspend fun persist(input: IncomingInput, resolver: ContentResolver?): Result<UploadJobEntity> =
        runCatching { container.upload.persist(input, resolver) }

    suspend fun prepare(jobId: Long, beforeSchedule: () -> Unit = {}): Result<IncomingPreparation> = runCatching {
        val current = container.settings.current()
        val server = requireNotNull(current.serverUrl) { "Configure a Grimmory server first" }
        var job = requireNotNull(container.upload.find(jobId)) { "Incoming upload is missing" }
        if (job.serverUrl.isBlank()) {
            job = container.upload.configure(
                job.id,
                UploadSettingsSnapshot(
                    server,
                    current.libraryId.toLong(),
                    current.pathId.toLong(),
                    current.recompressEpub,
                    current.httpConfirmed,
                ),
            )
        }
        val authenticated = container.auth.isAuthenticated { container.api.currentUser() }
        if (!authenticated) {
            container.upload.transition(job.id, UploadJobState.AWAITING_AUTH)
            IncomingPreparation(requireNotNull(container.upload.find(job.id)), requiresAuth = true)
        } else {
            beforeSchedule()
            check(container.upload.transition(job.id, UploadJobState.QUEUED)) { "Upload is no longer schedulable" }
            try {
                container.transferScheduler.schedule(job.id)
            } catch (error: Throwable) {
                container.upload.transition(job.id, UploadJobState.STAGED, error.message ?: "Scheduling failed")
                throw error
            }
            IncomingPreparation(requireNotNull(container.upload.find(job.id)), requiresAuth = false)
        }
    }

    suspend fun persistAndPrepare(
        input: IncomingInput,
        resolver: ContentResolver?,
        beforeSchedule: () -> Unit = {},
    ): Result<IncomingPreparation> = persist(input, resolver).fold(
        onSuccess = { prepare(it.id, beforeSchedule) },
        onFailure = { Result.failure(it) },
    )
}

data class IncomingPreparation(val job: UploadJobEntity, val requiresAuth: Boolean)
