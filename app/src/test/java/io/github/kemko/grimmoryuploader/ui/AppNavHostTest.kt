package io.github.kemko.grimmoryuploader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.di.AppContainer
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context

    @Before
    fun cleanStorage() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("upload.db")
        File(context.filesDir, "settings.preferences_pb").delete()
        File(context.noBackupFilesDir, "pending").deleteRecursively()
        File(context.noBackupFilesDir, "auth.tokens").delete()
        File(context.noBackupFilesDir, "auth.oidc").delete()
    }

    @Test
    fun incomingFirstLaunchStagesBeforeOnboardingAndConsumesIntent() {
        val cipher = AesGcmTokenCipher(
            SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
        )
        val container = AppContainer(context, cipher)
        val source = File(context.cacheDir, "incoming.fb2").apply {
            writeText("<?xml version=\"1.0\"?><FictionBook/>")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(source), "application/x-fictionbook+xml")
        var consumed = false

        compose.setContent {
            MaterialTheme {
                AppNavHost(
                    container = container,
                    launchIntent = intent,
                    onLaunchIntentConsumed = { consumed = true },
                )
            }
        }

        compose.onNodeWithText("Connect Grimmory").assertIsDisplayed()
        val pending = runBlocking { container.upload.pendingIntake() }
        assertNotNull(pending)
        assertTrue(File(requireNotNull(pending?.stagedPath)).isFile)
        assertTrue(consumed)
        container.database.close()
    }
}
