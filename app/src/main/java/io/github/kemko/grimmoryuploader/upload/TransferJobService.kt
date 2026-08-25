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
        val container = (application as GrimmoryUploaderApp).container
        lateinit var transfer: RunningTransfer
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var reschedule = false
            try {
                val queued = container.database.jobs().find(id) ?: return@launch
                val notifications = container.transferNotifications
                setNotification(
                    params,
                    TransferScheduler.stableJobId(id),
                    notifications.progressNotification(id, queued.displayName, TransferProgress(TransferStage.VALIDATION)),
                    JOB_END_NOTIFICATION_POLICY_REMOVE,
                )
                reschedule = TransferRunner(container.upload, container.pipeline, notifications).run(queued) {
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
