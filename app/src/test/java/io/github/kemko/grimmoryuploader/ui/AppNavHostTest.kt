package io.github.kemko.grimmoryuploader.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.di.AppContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

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
        val cipher =
            AesGcmTokenCipher(
                SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
            )
        val container = AppContainer(context, cipher)
        val source =
            File(context.cacheDir, "incoming.fb2").apply {
                writeText("<?xml version=\"1.0\"?><FictionBook/>")
            }
        val intent =
            Intent(Intent.ACTION_VIEW)
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

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connect Grimmory").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Connect Grimmory").assertIsDisplayed()
        val pending = runBlocking { container.upload.pendingIntake() }
        assertNotNull(pending)
        assertTrue(File(requireNotNull(pending?.stagedPath)).isFile)
        assertTrue(consumed)
        container.database.close()
    }

    @Test
    fun contentProviderMetadataIsReadOffMainThread() {
        val cipher =
            AesGcmTokenCipher(
                SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
            )
        val container = AppContainer(context, cipher)
        val source =
            File(context.cacheDir, "provider.fb2").apply {
                writeText("<?xml version=\"1.0\"?><FictionBook/>")
            }
        val provider = BookProvider(source)
        ShadowContentResolver.registerProviderInternal("off-main-books", provider)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("content://off-main-books/book"))
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

        compose.waitUntil(5_000) {
            provider.queryOffMain.get() &&
                provider.typeOffMain.get() &&
                consumed
        }
        assertTrue(provider.queryOffMain.get())
        assertTrue(provider.typeOffMain.get())
        container.database.close()
    }

    @Test
    fun incomingWaitsForStartupReconciliationBeforeStaging() {
        val cipher =
            AesGcmTokenCipher(
                SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
            )
        val container = AppContainer(context, cipher)
        val source =
            File(context.cacheDir, "startup.fb2").apply {
                writeText("<?xml version=\"1.0\"?><FictionBook/>")
            }
        val ready = CompletableDeferred<Unit>()

        compose.setContent {
            MaterialTheme {
                AppNavHost(
                    container = container,
                    launchIntent =
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.fromFile(source), "application/x-fictionbook+xml"),
                    awaitStartupReconciliation = { ready.await() },
                )
            }
        }

        compose.waitForIdle()
        assertNull(runBlocking { container.upload.pendingIntake() })
        ready.complete(Unit)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Connect Grimmory").fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(runBlocking { container.upload.pendingIntake() })
        container.database.close()
    }

    private class BookProvider(
        private val source: File,
    ) : ContentProvider() {
        val queryOffMain = AtomicBoolean()
        val typeOffMain = AtomicBoolean()

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queryOffMain.set(Looper.myLooper() != Looper.getMainLooper())
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf("provider.fb2"))
            }
        }

        override fun getType(uri: Uri): String {
            typeOffMain.set(Looper.myLooper() != Looper.getMainLooper())
            return "application/x-fictionbook+xml"
        }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
