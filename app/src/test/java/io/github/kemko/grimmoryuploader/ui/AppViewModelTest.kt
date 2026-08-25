package io.github.kemko.grimmoryuploader.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.ui.auth.AuthViewModel
import io.github.kemko.grimmoryuploader.ui.home.HomeViewModel
import io.github.kemko.grimmoryuploader.ui.incoming.IncomingBookViewModel
import io.github.kemko.grimmoryuploader.ui.settings.ServerChangeConfirmationRequired
import io.github.kemko.grimmoryuploader.ui.settings.SettingsViewModel
import io.github.kemko.grimmoryuploader.upload.UploadSettingsSnapshot
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("upload.db")
        File(context.filesDir, "settings.preferences_pb").delete()
        File(context.noBackupFilesDir, "pending").deleteRecursively()
        File(context.noBackupFilesDir, "auth.tokens").delete()
        File(context.noBackupFilesDir, "auth.oidc").delete()
        val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        container = AppContainer(context, AesGcmTokenCipher(key))
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    @Test
    fun incomingPreparationPersistsBeforeAuthAndSchedulesAuthenticatedInput() = runBlocking {
        container.settings.applyConfiguration("https://grimmory.test", 2, 3, true, AuthMode.AUTO, false)
        val viewModel = IncomingBookViewModel(container)

        val unauthenticated = viewModel.persistAndPrepare(
            IncomingInput.Url("https://books.test/one.fb2", "one.fb2"),
            null,
        ).getOrThrow()
        assertTrue(unauthenticated.requiresAuth)
        assertEquals(UploadJobState.AWAITING_AUTH, container.upload.find(unauthenticated.job.id)?.state)

        container.tokenStore.write(TokenPair("access", "refresh", Long.MAX_VALUE, "https://grimmory.test"))
        var requestedNotification = false
        val authenticated = viewModel.persistAndPrepare(
            IncomingInput.Url("https://books.test/two.fb2", "two.fb2"),
            null,
        ) { requestedNotification = true }.getOrThrow()
        assertFalse(authenticated.requiresAuth)
        assertTrue(requestedNotification)
        assertEquals(UploadJobState.QUEUED, container.upload.find(authenticated.job.id)?.state)
        assertTrue(viewModel.persist(IncomingInput.File("content://missing", "missing.fb2", null), null).isFailure)
        Unit
    }

    @Test
    fun homeActionsAndConfirmedServerChangeUpdateOnlyActiveJobs() = runBlocking {
        container.settings.applyConfiguration("https://one.example", 1, 1, true, AuthMode.LOCAL, false)
        container.tokenStore.write(TokenPair("access", "refresh", Long.MAX_VALUE, "https://one.example"))
        val failed = container.upload.enqueue(
            IncomingInput.Url("https://books.test/failed.fb2", "failed.fb2"),
            UploadSettingsSnapshot("https://one.example"),
        )
        container.upload.transition(failed.id, UploadJobState.QUEUED)
        container.upload.transition(failed.id, UploadJobState.RUNNING)
        container.upload.transition(failed.id, UploadJobState.FAILED, "offline")
        val home = HomeViewModel(container)

        home.retry(requireNotNull(container.upload.find(failed.id)))
        assertEquals(UploadJobState.QUEUED, container.upload.find(failed.id)?.state)
        home.cancel(requireNotNull(container.upload.find(failed.id)))
        assertEquals(UploadJobState.CANCELLED, container.upload.find(failed.id)?.state)

        val cleartext = container.upload.enqueue(
            IncomingInput.Url("http://books.test/book.fb2", "book.fb2"),
            UploadSettingsSnapshot("https://one.example"),
        )
        container.upload.transition(cleartext.id, UploadJobState.QUEUED)
        container.upload.transition(cleartext.id, UploadJobState.RUNNING)
        container.upload.transition(cleartext.id, UploadJobState.AWAITING_CLEARTEXT)
        home.confirmCleartext(requireNotNull(container.upload.find(cleartext.id)))
        assertEquals(UploadJobState.QUEUED, container.upload.find(cleartext.id)?.state)
        assertTrue(home.jobs().first().isNotEmpty())

        val settings = SettingsViewModel(container)
        assertEquals("https://one.example", settings.current().serverUrl)
        assertThrows(ServerChangeConfirmationRequired::class.java) {
            runBlocking { settings.save("https://two.example", AuthMode.OIDC, 4, 5, false, false) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { settings.save("https://two.example", AuthMode.OIDC, 0, 5, false, false, true) }
        }
        assertTrue(
            settings.save("https://two.example", AuthMode.OIDC, 4, 5, false, false, confirmServerChange = true),
        )
        assertEquals("https://two.example", settings.current().serverUrl)
        assertNull(container.upload.find(cleartext.id))
        assertNull(container.tokenStore.read())
        Unit
    }

    @Test
    fun authViewModelCoversModeLoginUserAndResume() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            container.settings.applyConfiguration(server.url("/").toString(), 1, 1, true, AuthMode.AUTO, true)
            server.enqueue(MockResponse().setBody("""{"oidcEnabled":false}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"accessToken":"access","refreshToken":"refresh","expiresIn":3600}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"id":1,"username":"reader"}"""))
            val viewModel = AuthViewModel(container)

            assertEquals(AuthMode.LOCAL, viewModel.modeDecision().mode)
            assertTrue(viewModel.login("reader", "secret").isSuccess)
            assertTrue(viewModel.isAuthenticated())
            viewModel.selectMode(AuthMode.OIDC)
            assertEquals(AuthMode.OIDC, container.settings.current().authMode)

            val awaiting = container.upload.enqueue(
                IncomingInput.Url("https://books.test/book.fb2", "book.fb2"),
                UploadSettingsSnapshot(server.url("/").toString(), serverCleartextConfirmed = true),
            )
            container.upload.transition(awaiting.id, UploadJobState.AWAITING_AUTH)
            viewModel.resumeTransfers()
            assertEquals(UploadJobState.QUEUED, container.upload.find(awaiting.id)?.state)
        } finally {
            server.shutdown()
        }
        Unit
    }

    @Test
    fun authViewModelClearsOnlyRejectedTokens() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            container.settings.applyConfiguration(server.url("/").toString(), 1, 1, true, AuthMode.LOCAL, true)
            val viewModel = AuthViewModel(container)
            val tokens = TokenPair(
                "access",
                "refresh",
                Long.MAX_VALUE,
                server.url("/").toString().trimEnd('/'),
            )
            container.tokenStore.write(tokens)
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(ApiException::class.java) { runBlocking { viewModel.isAuthenticated() } }
            assertNotNull(container.tokenStore.read())

            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            assertFalse(viewModel.isAuthenticated())
            assertNull(container.tokenStore.read())
        } finally {
            server.shutdown()
        }
        Unit
    }
}
