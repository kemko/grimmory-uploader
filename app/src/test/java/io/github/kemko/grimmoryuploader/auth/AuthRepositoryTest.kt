package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.AuthModeSelector
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun serializesConcurrentRefreshes() = runBlocking {
        val server = MockWebServer()
        var refreshes = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/auth/refresh") -> {
                    refreshes++
                    MockResponse().setBody("""{"accessToken":"new","refreshToken":"r2","expiresIn":3600}""")
                }
                else -> MockResponse().setBody("{}")
            }
        }
        server.start()
        try {
            val store = TestTokenStore()
            store.write(TokenPair("old", "r", 0))
            val api = GrimmoryApi(
                OkHttpClient(),
                serverUrl = { io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse(server.url("/base/").toString()) },
            )
            val auth = AuthRepository(api, store, nowMillis = { 1_000 })
            awaitAll(async { auth.validAccessToken() }, async { auth.validAccessToken() })
            assertEquals(1, refreshes)
            assertEquals("new", store.read()!!.accessToken)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authModeHonorsForcedOidcAndManualFallback() {
        assertEquals(
            AuthMode.OIDC,
            AuthModeSelector.select(AuthMode.LOCAL, PublicSettings(oidcEnabled = true, oidcForceOnlyMode = true)).mode,
        )
        assertEquals(
            AuthMode.OIDC,
            AuthModeSelector.select(AuthMode.AUTO, null, manualFallback = AuthMode.OIDC).mode,
        )
        assertTrue(AuthModeSelector.select(AuthMode.AUTO, PublicSettings(oidcEnabled = true)).requiresUserChoice)
    }
}
