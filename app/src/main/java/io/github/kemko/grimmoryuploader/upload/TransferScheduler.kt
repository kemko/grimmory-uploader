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
) {
    private val appContext = context.applicationContext
    private val scheduler = appContext.getSystemService(JobScheduler::class.java)

    fun schedule(jobId: Long, estimatedUploadBytes: Long = JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), estimatedDownloadBytes: Long = JobInfo.NETWORK_BYTES_UNKNOWN.toLong()): Int {
        val info = jobInfo(jobId, estimatedUploadBytes, estimatedDownloadBytes)
        val result = scheduler.schedule(info)
        check(result == JobScheduler.RESULT_SUCCESS) { "Unable to schedule transfer job" }
        return info.id
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
    }

    suspend fun resumeAwaitingAuth() {
        queue.pending().filter { it.state == UploadJobState.AWAITING_AUTH }.forEach {
            if (queue.transition(it.id, UploadJobState.QUEUED)) schedule(it.id)
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "upload_job_id"

        fun stableJobId(jobId: Long): Int = (jobId xor (jobId ushr 32)).toInt().coerceAtLeast(1)
    }
}
