package io.github.kemko.grimmoryuploader.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.kemko.grimmoryuploader.auth.TestTokenStore
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import io.github.kemko.grimmoryuploader.ui.onboarding.OnboardingViewModel
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {
    @Test
    fun validatesServerAndDiscoversLocalAuth() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setBody("{\"oidcEnabled\":false}"))
        server.start()
        try {
            val settings = AppSettingsRepository(
                PreferenceDataStoreFactory.create {
                    Files.createTempDirectory("onboarding").resolve("settings.preferences_pb").toFile()
                },
            )
            val api = GrimmoryApi(OkHttpClient(), { io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse(server.url("/grimmory/").toString()) })
            val auth = AuthRepository(
                api,
                TestTokenStore(),
                currentServerUrl = { server.url("/grimmory").toString().trimEnd('/') },
            )
            val viewModel = OnboardingViewModel(settings) {
                auth.healthcheck()
                runCatching { auth.publicSettings() }.getOrNull()
            }

            val result = viewModel.configureServer(server.url("/grimmory/").toString(), confirmCleartext = true).getOrThrow()

            assertEquals(AuthMode.LOCAL, result.mode)
            assertEquals("${server.url("/grimmory").toString().trimEnd('/')}", settings.current().serverUrl)
            assertTrue(settings.current().httpConfirmed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsCleartextBeforePersistingIt() = runBlocking {
        val settings = AppSettingsRepository(
            PreferenceDataStoreFactory.create {
                Files.createTempDirectory("onboarding-http").resolve("settings.preferences_pb").toFile()
            },
        )
        val api = GrimmoryApi(OkHttpClient(), { io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse("https://example.com") })
        val auth = AuthRepository(api, TestTokenStore(), currentServerUrl = { "https://example.com" })
        val viewModel = OnboardingViewModel(settings) {
            auth.healthcheck()
            runCatching { auth.publicSettings() }.getOrNull()
        }

        assertFalse(viewModel.configureServer("http://example.com", confirmCleartext = false).isSuccess)
        assertEquals(null, settings.current().serverUrl)
    }

    @Test
    fun failedHealthcheckDoesNotPersistCandidateServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val settings = AppSettingsRepository(
                PreferenceDataStoreFactory.create {
                    Files.createTempDirectory("onboarding-failure").resolve("settings.preferences_pb").toFile()
                },
            )
            val api = GrimmoryApi(
                OkHttpClient(),
                { io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse(server.url("/").toString()) },
            )
            val auth = AuthRepository(
                api,
                TestTokenStore(),
                currentServerUrl = { server.url("/").toString().trimEnd('/') },
            )
            val viewModel = OnboardingViewModel(settings) {
                auth.healthcheck()
                auth.publicSettings()
            }

            assertFalse(viewModel.configureServer(server.url("/").toString(), confirmCleartext = true).isSuccess)
            assertEquals(null, settings.current().serverUrl)
        } finally {
            server.shutdown()
        }
    }
}
