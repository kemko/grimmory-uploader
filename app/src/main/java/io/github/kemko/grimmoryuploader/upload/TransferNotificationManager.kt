package io.github.kemko.grimmoryuploader.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

enum class TransferStage { DOWNLOAD, VALIDATION, RECOMPRESSION, UPLOAD }

data class TransferProgress(
    val stage: TransferStage,
    val current: Long = 0,
    val total: Long = -1,
)

class TransferNotificationManager(
    private val context: Context,
) : TransferEvents {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Book transfers", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun progressNotification(
        jobId: Long,
        name: String,
        progress: TransferProgress,
    ): Notification {
        val builder =
            activeBuilder(jobId, name)
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

    fun showProgress(
        jobId: Long,
        name: String,
        progress: TransferProgress,
    ) = notify(jobId, progressNotification(jobId, name, progress))

    fun showSuccess(
        jobId: Long,
        name: String,
    ) = notify(
        jobId,
        openBuilder(jobId, name).setContentText("Upload complete").setAutoCancel(true).build(),
    )

    fun showFailure(
        jobId: Long,
        name: String,
        reason: String,
    ) = notify(
        jobId,
        openBuilder(jobId, name).setContentText(reason.take(160)).setAutoCancel(true).build(),
    )

    fun showInputFailure(reason: String) =
        notify(
            INPUT_FAILURE_ID,
            baseBuilder("Grimmory Uploader")
                .setContentText(reason.take(160))
                .setAutoCancel(true)
                .build(),
        )

    fun showAuthRequired(
        jobId: Long,
        name: String,
    ) = notify(
        jobId,
        activeBuilder(jobId, name)
            .setContentText("Sign in to continue")
            .setAutoCancel(true)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        "Sign in",
                        pendingIntent(TransferActionReceiver.ACTION_AUTH, jobId),
                    ).build(),
            ).build(),
    )

    fun showCleartextConfirmation(
        jobId: Long,
        name: String,
    ) = notify(
        jobId,
        activeBuilder(jobId, name)
            .setContentText("HTTP confirmation required")
            .setAutoCancel(true)
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        "Allow HTTP",
                        pendingIntent(TransferActionReceiver.ACTION_CONFIRM_HTTP, jobId),
                    ).build(),
            ).build(),
    )

    fun cancel(jobId: Long) = manager.cancel(notificationId(jobId))

    override fun progress(
        jobId: Long,
        name: String,
        progress: TransferProgress,
    ) = showProgress(jobId, name, progress)

    override fun success(
        jobId: Long,
        name: String,
    ) = showSuccess(jobId, name)

    override fun authRequired(
        jobId: Long,
        name: String,
    ) = showAuthRequired(jobId, name)

    override fun cleartextRequired(
        jobId: Long,
        name: String,
    ) = showCleartextConfirmation(jobId, name)

    override fun failure(
        jobId: Long,
        name: String,
        reason: String,
    ) = showFailure(jobId, name, reason)

    private fun baseBuilder(name: String): Notification.Builder {
        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                Notification.Builder(context)
            }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(name)
    }

    private fun openBuilder(
        jobId: Long,
        name: String,
    ): Notification.Builder = baseBuilder(name).setContentIntent(pendingIntent(TransferActionReceiver.ACTION_OPEN, jobId))

    private fun activeBuilder(
        jobId: Long,
        name: String,
    ): Notification.Builder =
        openBuilder(jobId, name)
            .setContentIntent(pendingIntent(TransferActionReceiver.ACTION_OPEN, jobId))
            .addAction(
                Notification.Action
                    .Builder(
                        null,
                        "Cancel",
                        pendingIntent(TransferActionReceiver.ACTION_CANCEL, jobId),
                    ).build(),
            )

    private fun notify(
        jobId: Long,
        notification: Notification,
    ) {
        val id = if (jobId == INPUT_FAILURE_ID) INPUT_FAILURE_NOTIFICATION_ID else notificationId(jobId)
        runCatching { manager.notify(id, notification) }
    }

    private fun pendingIntent(
        action: String,
        jobId: Long,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId(jobId) + action.hashCode(),
            Intent(context, TransferActionReceiver::class.java)
                .setAction(action)
                .putExtra(TransferActionReceiver.EXTRA_JOB_ID, jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationId(jobId: Long): Int = (jobId xor (jobId ushr 32)).toInt().coerceIn(1, INPUT_FAILURE_NOTIFICATION_ID - 1)

    private companion object {
        const val CHANNEL_ID = "book_transfers"
        const val INPUT_FAILURE_ID = Long.MIN_VALUE
        const val INPUT_FAILURE_NOTIFICATION_ID = Int.MAX_VALUE
        val TransferStage.label: String
            get() =
                when (this) {
                    TransferStage.DOWNLOAD -> "Downloading"
                    TransferStage.VALIDATION -> "Validating"
                    TransferStage.RECOMPRESSION -> "Recompressing"
                    TransferStage.UPLOAD -> "Uploading"
                }
    }
}
