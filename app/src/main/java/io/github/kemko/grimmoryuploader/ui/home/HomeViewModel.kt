package io.github.kemko.grimmoryuploader.ui.home

import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.flow.Flow

class HomeViewModel(private val container: AppContainer) {
    fun jobs(): Flow<List<UploadJobEntity>> = container.upload.observeAll()

    suspend fun retry(job: UploadJobEntity) {
        container.upload.retry(job.id)
        container.transferScheduler.schedule(job.id)
    }

    suspend fun cancel(job: UploadJobEntity) {
        container.upload.transition(job.id, UploadJobState.CANCELLED, "Cancelled by user")
        container.transferScheduler.cancel(job.id)
    }

    suspend fun confirmCleartext(job: UploadJobEntity) {
        container.upload.confirmSourceCleartext(job.id)
        container.transferScheduler.schedule(job.id)
    }
}
