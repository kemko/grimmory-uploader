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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Long, RunningTransfer>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(TransferScheduler.EXTRA_JOB_ID, -1L)
        if (id < 0) return false
        val app = application as GrimmoryUploaderApp
        val container = app.container
        lateinit var transfer: RunningTransfer
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var reschedule = false
            try {
                app.startupReconciliation.await()
                val queued = container.database.jobs().find(id) ?: return@launch
                val notifications = container.transferNotifications
                val lifecycleNotificationId = TransferScheduler.lifecycleNotificationId(id)
                fun showProgress(progress: TransferProgress) = setNotification(
                    params,
                    lifecycleNotificationId,
                    notifications.progressNotification(id, queued.displayName, progress),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
                showProgress(TransferProgress(TransferStage.VALIDATION))
                val events = object : TransferEvents {
                    override fun progress(jobId: Long, name: String, progress: TransferProgress) = showProgress(progress)
                    override fun success(jobId: Long, name: String) = notifications.success(jobId, name)
                    override fun authRequired(jobId: Long, name: String) = notifications.authRequired(jobId, name)
                    override fun cleartextRequired(jobId: Long, name: String) = notifications.cleartextRequired(jobId, name)
                    override fun failure(jobId: Long, name: String, reason: String) =
                        notifications.failure(jobId, name, reason)
                }
                reschedule = TransferRunner(container.upload, container.pipeline, events).run(queued) {
                    transfer.stopped.get() || !transfer.job.isActive
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    container.upload.transition(id, UploadJobState.QUEUED, "Transfer was interrupted")
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    container.upload.transition(
                        id,
                        UploadJobState.QUEUED,
                        error.message ?: "Transfer service failed",
                    )
                    reschedule = true
                }
            } finally {
                running.remove(id, transfer)
                if (!transfer.stopped.get()) jobFinished(params, reschedule)
            }
        }
        transfer = RunningTransfer(job = job)
        if (running.putIfAbsent(id, transfer) != null) return false
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getLong(TransferScheduler.EXTRA_JOB_ID, -1L)
        running.remove(id)?.also {
            it.stopped.set(true)
            it.job.cancel()
        }
        return true
    }

    override fun onDestroy() {
        running.values.forEach { it.stopped.set(true) }
        scope.cancel()
        running.clear()
        super.onDestroy()
    }

    private data class RunningTransfer(
        val job: kotlinx.coroutines.Job,
        val stopped: AtomicBoolean = AtomicBoolean(false),
    )
}
