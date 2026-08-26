package io.github.kemko.grimmoryuploader.upload

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferNotificationManagerTest {
    @Test
    fun terminalAndInputNotificationsHaveNoCancelActionOrIdCollision() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = TransferNotificationManager(context)
        val active = notifications.progressNotification(1, "book.fb2", TransferProgress(TransferStage.UPLOAD))
        assertEquals(listOf("Cancel"), active.actions.map { it.title.toString() })

        notifications.showSuccess(1, "book.fb2")
        notifications.showInputFailure("unsupported")
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(
            0,
            manager
                .getNotification(1)
                .actions
                .orEmpty()
                .size,
        )
        assertNotNull(manager.getNotification(Int.MAX_VALUE))
        assertEquals(
            0,
            manager
                .getNotification(Int.MAX_VALUE)
                .actions
                .orEmpty()
                .size,
        )
        notifications.showSuccess(Int.MAX_VALUE.toLong(), "large-id.fb2")
        assertNotNull(manager.getNotification(Int.MAX_VALUE - 1))
        assertNull(manager.getNotification(2))
    }

    @Test
    fun rendersEveryProgressAndActionNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = TransferNotificationManager(context)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))

        TransferStage.entries.forEachIndexed { index, stage ->
            notifications.showProgress(index + 10L, "book.fb2", TransferProgress(stage, 1, 2))
            val notification = requireNotNull(manager.getNotification(index + 10))
            assertEquals(100, notification.extras.getInt("android.progressMax"))
            assertEquals(50, notification.extras.getInt("android.progress"))
            assertFalse(notification.extras.getBoolean("android.progressIndeterminate"))
        }
        notifications.showFailure(20, "book.fb2", "server rejected upload")
        assertEquals(
            0,
            manager
                .getNotification(20)
                .actions
                .orEmpty()
                .size,
        )
        notifications.showAuthRequired(21, "book.fb2")
        assertEquals(listOf("Cancel", "Sign in"), manager.getNotification(21).actions.map { it.title.toString() })
        notifications.showCleartextConfirmation(22, "book.fb2")
        assertEquals(listOf("Cancel", "Allow HTTP"), manager.getNotification(22).actions.map { it.title.toString() })
        notifications.cancel(22)
        assertNull(manager.getNotification(22))
    }
}
