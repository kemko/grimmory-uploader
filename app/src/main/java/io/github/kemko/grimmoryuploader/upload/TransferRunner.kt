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
    private val nowNanos: () -> Long = System::nanoTime,
) {
    suspend fun run(job: UploadJobEntity, cancelled: () -> Boolean): Boolean {
        if (!queue.transition(job.id, UploadJobState.RUNNING)) return false
        val reporter = ProgressReporter(job.id, job.displayName)
        val result = pipeline.execute(job, cancelled, reporter::update)
        reporter.flush()
        return when (
            result
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

    private inner class ProgressReporter(
        private val jobId: Long,
        private val name: String,
    ) {
        private var latest: TransferProgress? = null
        private var published: TransferProgress? = null
        private var publishedAt = 0L

        fun update(progress: TransferProgress) {
            latest = progress
            if (shouldPublish(progress)) runBlocking { publish(progress) }
        }

        suspend fun flush() {
            latest?.takeIf { it != published }?.let { publish(it) }
        }

        private fun shouldPublish(progress: TransferProgress): Boolean {
            val previous = published ?: return true
            return progress.stage != previous.stage ||
                progress.total != previous.total ||
                progress.current < previous.current ||
                progress.total > 0 && progress.current >= progress.total ||
                progress.current - previous.current >= MIN_PROGRESS_BYTES ||
                nowNanos() - publishedAt >= MIN_PROGRESS_INTERVAL_NANOS
        }

        private suspend fun publish(progress: TransferProgress) {
            queue.updateProgress(jobId, progress)
            events.progress(jobId, name, progress)
            published = progress
            publishedAt = nowNanos()
        }
    }

    private companion object {
        const val MIN_PROGRESS_BYTES = 1024L * 1024
        const val MIN_PROGRESS_INTERVAL_NANOS = 500L * 1000 * 1000
    }
}
