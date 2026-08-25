package io.github.kemko.grimmoryuploader.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState

class TransferScheduler(
    context: Context,
    private val queue: UploadQueueRepository,
    private val clearNotification: (Long) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val scheduler = appContext.getSystemService(JobScheduler::class.java)

    fun schedule(jobId: Long, estimatedUploadBytes: Long = JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), estimatedDownloadBytes: Long = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()): Int {
        val info = jobInfo(jobId, estimatedUploadBytes, estimatedDownloadBytes)
        clearNotification(jobId)
        val result = scheduler.schedule(info)
        check(result == JobScheduler.RESULT_SUCCESS) { "Unable to schedule transfer job" }
        return info.id
    }

    fun ensureScheduled(jobId: Long) {
        if (scheduler.getPendingJob(stableJobId(jobId)) == null) schedule(jobId)
    }

    fun jobInfo(jobId: Long, estimatedUploadBytes: Long = JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), estimatedDownloadBytes: Long = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()): JobInfo =
        JobInfo.Builder(
            stableJobId(jobId),
            ComponentName(appContext, TransferJobService::class.java),
        )
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setEstimatedNetworkBytes(
                estimatedDownloadBytes.coerceAtLeast(JobInfo.NETWORK_BYTES_UNKNOWN.toLong()),
                estimatedUploadBytes.coerceAtLeast(JobInfo.NETWORK_BYTES_UNKNOWN.toLong()),
            )
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putLong(EXTRA_JOB_ID, jobId) })
            .build()

    fun cancel(jobId: Long) {
        scheduler.cancel(stableJobId(jobId))
        clearNotification(jobId)
    }

    suspend fun resumeAwaitingAuth(scheduleJob: (Long) -> Unit = { schedule(it) }) {
        queue.pending().filter { it.state == UploadJobState.AWAITING_AUTH }.forEach {
            if (queue.transition(it.id, UploadJobState.QUEUED)) {
                try {
                    scheduleJob(it.id)
                } catch (error: Exception) {
                    queue.transition(it.id, UploadJobState.AWAITING_AUTH, error.message ?: "Scheduling failed")
                }
            }
        }
    }

    suspend fun ensureQueuedScheduled(ensureJob: (Long) -> Unit = ::ensureScheduled) {
        queue.pending().filter { it.state == UploadJobState.QUEUED }.forEach {
            try {
                ensureJob(it.id)
            } catch (_: Exception) {
                // Keep the job queued so the next visible launch can retry it.
            }
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "upload_job_id"

        fun stableJobId(jobId: Long): Int = (jobId xor (jobId ushr 32)).toInt().coerceAtLeast(1)
        fun lifecycleNotificationId(jobId: Long): Int = -stableJobId(jobId)
    }
}
