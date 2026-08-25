package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.runBlocking

interface TransferPipeline {
    suspend fun execute(
        job: UploadJobEntity,
        cancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): PipelineResult
}

interface TransferEvents {
    fun progress(jobId: Long, name: String, progress: TransferProgress)
    fun success(jobId: Long, name: String)
    fun authRequired(jobId: Long, name: String)
    fun cleartextRequired(jobId: Long, name: String)
    fun failure(jobId: Long, name: String, reason: String)
}

class TransferRunner(
    private val queue: UploadQueueRepository,
    private val pipeline: TransferPipeline,
    private val events: TransferEvents,
) {
    suspend fun run(job: UploadJobEntity, cancelled: () -> Boolean): Boolean {
        if (!queue.transition(job.id, UploadJobState.RUNNING)) return false
        return when (
            val result = pipeline.execute(job, cancelled) { progress ->
                runBlocking { queue.updateProgress(job.id, progress) }
                events.progress(job.id, job.displayName, progress)
            }
        ) {
            PipelineResult.Success -> {
                if (queue.transition(job.id, UploadJobState.SUCCEEDED)) events.success(job.id, job.displayName)
                false
            }
            is PipelineResult.AwaitingAuth -> {
                if (queue.transition(job.id, UploadJobState.AWAITING_AUTH, result.reason)) {
                    events.authRequired(job.id, job.displayName)
                }
                false
            }
            is PipelineResult.AwaitingCleartextConfirmation -> {
                if (queue.transition(job.id, UploadJobState.AWAITING_CLEARTEXT, "HTTP confirmation required")) {
                    events.cleartextRequired(job.id, job.displayName)
                }
                false
            }
            is PipelineResult.Retry -> queue.transition(job.id, UploadJobState.QUEUED, result.reason)
            is PipelineResult.Failed -> {
                if (queue.transition(job.id, UploadJobState.FAILED, result.reason)) {
                    events.failure(job.id, job.displayName, result.reason)
                }
                false
            }
        }
    }
}
