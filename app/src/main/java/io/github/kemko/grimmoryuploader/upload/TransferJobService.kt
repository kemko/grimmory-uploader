package io.github.kemko.grimmoryuploader.upload

import android.app.job.JobParameters
import android.app.job.JobService
import io.github.kemko.grimmoryuploader.GrimmoryUploaderApp
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<Long, Pair<JobParameters, kotlinx.coroutines.Job>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(TransferScheduler.EXTRA_JOB_ID, -1L)
        if (id < 0) return false
        val container = (application as GrimmoryUploaderApp).container
        val job = scope.launch {
            val queued = container.database.jobs().find(id)
            if (queued == null) {
                jobFinished(params, false)
                return@launch
            }
            val notifications = container.transferNotifications
            setNotification(
                params,
                TransferScheduler.stableJobId(id),
                notifications.progressNotification(id, queued.displayName, TransferProgress(TransferStage.VALIDATION)),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
            container.upload.transition(id, UploadJobState.RUNNING)
            try {
                when (val result = container.pipeline.execute(queued) { progress ->
                    notifications.showProgress(id, queued.displayName, progress)
                }) {
                    PipelineResult.Success -> {
                        container.upload.transition(id, UploadJobState.SUCCEEDED)
                        notifications.showSuccess(id, queued.displayName)
                        jobFinished(params, false)
                    }
                    is PipelineResult.AwaitingAuth -> {
                        container.upload.transition(id, UploadJobState.AWAITING_AUTH, result.reason)
                        notifications.showAuthRequired(id, queued.displayName)
                        jobFinished(params, false)
                    }
                    is PipelineResult.AwaitingCleartextConfirmation -> {
                        container.upload.transition(id, UploadJobState.STAGED, "HTTP confirmation required")
                        notifications.showCleartextConfirmation(id, queued.displayName)
                        jobFinished(params, false)
                    }
                    is PipelineResult.Retry -> {
                        container.upload.transition(id, UploadJobState.QUEUED, result.reason)
                        jobFinished(params, true)
                    }
                    is PipelineResult.Failed -> {
                        container.upload.transition(id, UploadJobState.FAILED, result.reason)
                        notifications.showFailure(id, queued.displayName, result.reason)
                        jobFinished(params, false)
                    }
                }
            } catch (_: CancellationException) {
                // The system stopped the job. Reconciliation restores RUNNING to QUEUED.
            } finally {
                running.remove(id)
            }
        }
        running[id] = params to job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(TransferScheduler.EXTRA_JOB_ID, -1L)
        running.remove(id)?.second?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        running.clear()
        super.onDestroy()
    }
}
