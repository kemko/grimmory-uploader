package io.github.kemko.grimmoryuploader.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.kemko.grimmoryuploader.MainActivity

enum class TransferStage { DOWNLOAD, VALIDATION, RECOMPRESSION, UPLOAD }

data class TransferProgress(
    val stage: TransferStage,
    val current: Long = 0,
    val total: Long = -1,
)

class TransferNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Book transfers", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun progressNotification(jobId: Long, name: String, progress: TransferProgress): Notification {
        val builder = builder(jobId, name)
            .setContentText(progress.stage.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress.total > 0) {
            builder.setProgress(100, ((progress.current * 100) / progress.total).coerceIn(0, 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun showProgress(jobId: Long, name: String, progress: TransferProgress) =
        notify(jobId, progressNotification(jobId, name, progress))

    fun showSuccess(jobId: Long, name: String) = notify(
        jobId,
        builder(jobId, name).setContentText("Upload complete").setAutoCancel(true).build(),
    )

    fun showFailure(jobId: Long, name: String, reason: String) = notify(
        jobId,
        builder(jobId, name).setContentText(reason.take(160)).setAutoCancel(true).build(),
    )

    fun showInputFailure(reason: String) = notify(
        INPUT_FAILURE_ID,
        builder(INPUT_FAILURE_ID, "Grimmory Uploader")
            .setContentText(reason.take(160))
            .setAutoCancel(true)
            .build(),
    )

    fun showAuthRequired(jobId: Long, name: String) = notify(
        jobId,
        builder(jobId, name)
            .setContentText("Sign in to continue")
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Sign in",
                    pendingIntent(TransferActionReceiver.ACTION_AUTH, jobId),
                ).build(),
            )
            .build(),
    )

    fun showCleartextConfirmation(jobId: Long, name: String) = notify(
        jobId,
        builder(jobId, name).setContentText("HTTP confirmation required").setAutoCancel(true).build(),
    )

    fun cancel(jobId: Long) = manager.cancel(notificationId(jobId))

    private fun builder(jobId: Long, name: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID)
        else Notification.Builder(context)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(name)
            .setContentIntent(pendingIntent(TransferActionReceiver.ACTION_OPEN, jobId))
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Cancel",
                    pendingIntent(TransferActionReceiver.ACTION_CANCEL, jobId),
                ).build(),
            )
    }

    private fun notify(jobId: Long, notification: Notification) {
        runCatching { manager.notify(notificationId(jobId), notification) }
    }

    private fun pendingIntent(action: String, jobId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        notificationId(jobId) + action.hashCode(),
        Intent(context, TransferActionReceiver::class.java).setAction(action)
            .putExtra(TransferActionReceiver.EXTRA_JOB_ID, jobId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationId(jobId: Long): Int = (jobId xor (jobId ushr 32)).toInt().coerceAtLeast(1)

    private companion object {
        const val CHANNEL_ID = "book_transfers"
        const val INPUT_FAILURE_ID = 1L
        val TransferStage.label: String
            get() = when (this) {
                TransferStage.DOWNLOAD -> "Downloading"
                TransferStage.VALIDATION -> "Validating"
                TransferStage.RECOMPRESSION -> "Recompressing"
                TransferStage.UPLOAD -> "Uploading"
            }
    }
}
