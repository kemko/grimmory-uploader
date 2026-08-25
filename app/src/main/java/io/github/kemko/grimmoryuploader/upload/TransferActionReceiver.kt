package io.github.kemko.grimmoryuploader.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kemko.grimmoryuploader.GrimmoryUploaderApp
import io.github.kemko.grimmoryuploader.MainActivity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        if (jobId < 0) return
        val app = context.applicationContext as GrimmoryUploaderApp
        when (intent.action) {
            ACTION_CANCEL -> {
                goAsync().also { result ->
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching { app.container.upload.transition(jobId, UploadJobState.CANCELLED, "Cancelled by user") }
                        app.container.transferScheduler.cancel(jobId)
                        app.container.transferNotifications.cancel(jobId)
                        result.finish()
                    }
                }
            }
            ACTION_CONFIRM_HTTP -> {
                goAsync().also { result ->
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            app.container.upload.confirmSourceCleartext(jobId)
                            app.container.transferScheduler.schedule(jobId)
                        }
                        result.finish()
                    }
                }
            }
            ACTION_AUTH, ACTION_OPEN -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(intent.action)
                        .putExtra(EXTRA_JOB_ID, jobId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
    }

    companion object {
        const val EXTRA_JOB_ID = "upload_job_id"
        const val ACTION_CANCEL = "io.github.kemko.grimmoryuploader.action.CANCEL"
        const val ACTION_AUTH = "io.github.kemko.grimmoryuploader.action.AUTH"
        const val ACTION_OPEN = "io.github.kemko.grimmoryuploader.action.OPEN"
        const val ACTION_CONFIRM_HTTP = "io.github.kemko.grimmoryuploader.action.CONFIRM_HTTP"
    }
}
