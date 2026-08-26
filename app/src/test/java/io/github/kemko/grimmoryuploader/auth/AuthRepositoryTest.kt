package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.AuthModeSelector
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AuthRepositoryTest {
    @Test
    fun serializesConcurrentRefreshes() =
        runBlocking {
            val server = MockWebServer()
            var refreshes = 0
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path!!.endsWith("/auth/refresh") -> {
                                refreshes++
                                MockResponse().setBody("""{"accessToken":"new","refreshToken":"r2","expires":3600}""")
                            }
                            else -> MockResponse().setBody("{}")
                        }
                }
            server.start()
            try {
                val store = TestTokenStore()
                val base =
                    io.github.kemko.grimmoryuploader.data.network.ServerUrl.parse(
                        server.url("/base/").toString(),
                    )
                store.write(TokenPair("old", "r", 0, base.normalized))
                val api =
                    GrimmoryApi(
                        OkHttpClient(),
                        serverUrl = { base },
                    )
                val auth = AuthRepository(api, store, { base.normalized }, nowMillis = { 1_000 })
                awaitAll(async { auth.validAccessToken() }, async { auth.validAccessToken() })
                assertEquals(1, refreshes)
                assertEquals("new", store.read()!!.accessToken)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun rejectsTokensIssuedByAnotherServer() =
        runBlocking {
            val current = "https://current.example"
            val store =
                TestTokenStore().apply {
                    write(TokenPair("access", "refresh", Long.MAX_VALUE, "https://old.example"))
                }
            val api =
                GrimmoryApi(
                    OkHttpClient(),
                    serverUrl = {
                        io.github.kemko.grimmoryuploader.data.network.ServerUrl
                            .parse(current)
                    },
                )
            val auth = AuthRepository(api, store, { current })

            assertNull(auth.validAccessToken())
            assertNull(store.read())
        }

    @Test
    fun serverInvalidationWinsAgainstInFlightRefresh() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setBody("""{"accessToken":"new","refreshToken":"r2","expires":3600}""")
                    .setBodyDelay(1, TimeUnit.SECONDS),
            )
            server.start()
            try {
                val base =
                    io.github.kemko.grimmoryuploader.data.network.ServerUrl
                        .parse(server.url("/").toString())
                val store =
                    TestTokenStore().apply {
                        write(TokenPair("old", "refresh", 0, base.normalized))
                    }
                val auth =
                    AuthRepository(
                        GrimmoryApi(OkHttpClient(), serverUrl = { base }),
                        store,
                        currentServerUrl = { base.normalized },
                    )

                val refresh = async(Dispatchers.IO) { auth.refresh("old") }
                server.takeRequest()
                val invalidation = async(Dispatchers.IO) { auth.invalidateForServerChange() }
                invalidation.await()
                refresh.await()

                assertNull(store.read())
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun usesGrimmoryExpiresSeconds() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(
                MockResponse().setBody("""{"accessToken":"access","refreshToken":"refresh","expires":120}"""),
            )
            server.start()
            try {
                val base =
                    io.github.kemko.grimmoryuploader.data.network.ServerUrl
                        .parse(server.url("/").toString())
                val auth =
                    AuthRepository(
                        GrimmoryApi(OkHttpClient(), serverUrl = { base }),
                        TestTokenStore(),
                        currentServerUrl = { base.normalized },
                        nowMillis = { 1_000 },
                    )

                assertEquals(121_000L, auth.login("reader", "secret").expiresAtMillis)
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
