package io.github.kemko.grimmoryuploader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.ui.home.HomeViewModel
import io.github.kemko.grimmoryuploader.ui.onboarding.OnboardingViewModel
import io.github.kemko.grimmoryuploader.ui.settings.SettingsViewModel
import io.github.kemko.grimmoryuploader.upload.TransferProgress
import io.github.kemko.grimmoryuploader.upload.TransferStage
import io.github.kemko.grimmoryuploader.upload.UploadSettingsSnapshot
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppScreensTest {
    @get:Rule
    val compose = createComposeRule()

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
    fun onboardingChecksCandidateBeforeCompleting() {
        var configured = false
        compose.setContent {
            MaterialTheme {
                OnboardingScreen(
                    OnboardingViewModel(container.settings) { PublicSettings(oidcEnabled = false) },
                ) { configured = true }
            }
        }

        compose.onNodeWithText("Server URL").performTextInput("https://one.example")
        compose.onNodeWithText("Check server").performClick()
        compose.waitUntil { configured }
        assertTrue(configured)
    }

    @Test
    fun homeRendersEveryStateProgressAndAvailableActions() {
        runBlocking {
            UploadJobState.entries.forEach { target ->
                val job = container.upload.enqueue(
                    IncomingInput.Url("https://books.test/${target.name}.fb2", "${target.name}.fb2"),
                    UploadSettingsSnapshot("https://one.example"),
                )
                when (target) {
                    UploadJobState.STAGED -> Unit
                    UploadJobState.AWAITING_AUTH,
                    UploadJobState.AWAITING_CLEARTEXT,
                    UploadJobState.QUEUED,
                    UploadJobState.CANCELLED,
                    -> container.upload.transition(job.id, target)
                    UploadJobState.RUNNING -> {
                        container.upload.transition(job.id, UploadJobState.QUEUED)
                        container.upload.transition(job.id, target)
                        container.upload.updateProgress(
                            job.id,
                            TransferProgress(TransferStage.DOWNLOAD, current = 5, total = 10),
                        )
                    }
                    UploadJobState.SUCCEEDED,
                    UploadJobState.FAILED,
                    -> {
                        container.upload.transition(job.id, UploadJobState.QUEUED)
                        container.upload.transition(job.id, UploadJobState.RUNNING)
                        container.upload.transition(job.id, target, target.name.lowercase())
                    }
                }
            }
        }
        var openedSettings = false
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    HomeViewModel(container),
                    onSettings = { openedSettings = true },
                    requestNotificationPermission = {},
                    notificationPermissionDenied = true,
                )
            }
        }

        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        val jobs = compose.onNode(hasScrollAction())
        UploadJobState.entries.forEach { state ->
            val name = "${state.name}.fb2"
            jobs.performScrollToNode(hasText(name))
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        jobs.performScrollToNode(hasText("Retry"))
        compose.onNodeWithText("Retry").assertIsDisplayed()
        jobs.performScrollToNode(hasText("Allow HTTP"))
        compose.onNodeWithText("Allow HTTP").assertIsDisplayed()
        compose.onAllNodesWithText("Cancel")[0].assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun settingsRendersStoredConfiguration() {
        runBlocking {
            container.settings.applyConfiguration("http://one.example", 7, 9, false, AuthMode.OIDC, true)
        }
        compose.setContent {
            MaterialTheme { SettingsScreen(SettingsViewModel(container), onSaved = { _ -> }) }
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Server URL").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        compose.onNodeWithText("Library ID").assertIsDisplayed()
        compose.onNodeWithText("Path ID").assertIsDisplayed()
        val settings = compose.onNode(hasScrollAction())
        settings.performScrollToNode(hasText("Allow cleartext HTTP"))
        compose.onNodeWithText("Allow cleartext HTTP").assertIsDisplayed()
        settings.performScrollToNode(hasText("Save"))
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun navHostRoutesToAuthHomeAndInputError() {
        val server = MockWebServer()
        server.start()
        try {
            runBlocking {
                container.settings.applyConfiguration(server.url("/").toString(), 1, 1, true, AuthMode.AUTO, true)
            }
            server.enqueue(MockResponse().setBody("""{"oidcEnabled":false}"""))
            compose.setContent {
                MaterialTheme { AppNavHost(container) }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Password").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Password").assertIsDisplayed()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun navHostShowsRejectedInputError() {
        var consumed = false
        compose.setContent {
            MaterialTheme {
                AppNavHost(
                    container,
                    launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://books.test/book.fb2")),
                    onLaunchIntentConsumed = { consumed = true },
                )
            }
        }

        compose.onNodeWithText("Something went wrong").assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
        assertTrue(consumed)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Connect Grimmory").assertIsDisplayed()
    }

    @Test
    fun navHostRoutesAuthenticatedLaunchToHome() {
        val server = MockWebServer()
        server.start()
        try {
            runBlocking {
                container.settings.applyConfiguration(server.url("/").toString(), 1, 1, true, AuthMode.LOCAL, true)
                container.tokenStore.write(
                    TokenPair("access", "refresh", Long.MAX_VALUE, server.url("/").toString().trimEnd('/')),
                )
            }
            server.enqueue(MockResponse().setBody("""{"id":1,"username":"reader"}"""))
            compose.setContent {
                MaterialTheme { AppNavHost(container) }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No transfers").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("No transfers").assertIsDisplayed()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun changingServerRoutesBackToAuthentication() {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        try {
            val firstUrl = first.url("/").toString().trimEnd('/')
            val secondUrl = second.url("/").toString().trimEnd('/')
            runBlocking {
                container.settings.applyConfiguration(firstUrl, 1, 1, true, AuthMode.LOCAL, true)
                container.tokenStore.write(TokenPair("access", "refresh", Long.MAX_VALUE, firstUrl))
            }
            first.enqueue(MockResponse().setBody("""{"id":1,"username":"reader"}"""))
            second.enqueue(MockResponse().setBody("""{"oidcEnabled":false}"""))
            compose.setContent { MaterialTheme { AppNavHost(container) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("No transfers").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithText("Settings").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Server URL").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Server URL").performTextReplacement(secondUrl)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Save"))
            compose.onNodeWithText("Save").performClick()
            compose.onNodeWithText("Change server").performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Password").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Password").assertIsDisplayed()
        } finally {
            first.shutdown()
            second.shutdown()
        }
    }
}
