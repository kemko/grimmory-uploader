package io.github.kemko.grimmoryuploader.auth

import android.content.ContextWrapper
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.OidcCoordinator
import io.github.kemko.grimmoryuploader.data.auth.Pkce
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OidcCoordinatorTest {
    @Test
    fun startsWithServerStateAndExchangesCallbackCode() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueueOidcStart("server-state")
        server.enqueue(MockResponse().setBody("""{"accessToken":"oidc-access","refreshToken":"oidc-refresh","expires":3600}"""))
        try {
            val base = ServerUrl.parse(server.url("/grimmory/").toString())
            val store = TestTokenStore()
            val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
            lateinit var authorizationData: io.github.kemko.grimmoryuploader.data.auth.OidcAuthorizationData
            val coordinator = OidcCoordinator(
                ContextWrapper(null),
                api,
                AuthRepository(api, store, currentServerUrl = { base.normalized }),
                store,
                authorizationIntentFactory = { data ->
                    authorizationData = data
                    android.content.Intent("test.oidc")
                },
            )
            val intent = coordinator.start()
            assertEquals("server-state", authorizationData.state)
            assertEquals("openid profile email", authorizationData.scope)
            assertEquals(Pkce.challenge(authorizationData.codeVerifier), authorizationData.codeChallenge)
            coordinator.handleCallback(state = "server-state", error = null, code = "abc")
            assertEquals("oidc-access", store.read()!!.accessToken)
            assertTrue(server.takeRequest().path!!.endsWith("/public-settings"))
            assertEquals("/issuer/.well-known/openid-configuration", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.endsWith("/auth/oidc/state"))
            val callbackRequest = server.takeRequest()
            assertEquals("abc", callbackRequest.requestUrl?.queryParameter("code"))
            assertEquals(authorizationData.codeVerifier, callbackRequest.requestUrl?.queryParameter("code_verifier"))
            assertEquals(0L, callbackRequest.bodySize)
            coordinator.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pkceChallengeIsStableForVerifier() {
        assertEquals(Pkce.challenge("abc"), Pkce.challenge("abc"))
    }

    @Test
    fun rejectsInvalidErrorAndReplayedCallbacksWithoutTokenExchange() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueueOidcStart("expected")
        try {
            val store = TestTokenStore()
            val base = ServerUrl.parse(server.url("/").toString())
            val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
            val coordinator = OidcCoordinator(
                ContextWrapper(null),
                api,
                AuthRepository(api, store, currentServerUrl = { base.normalized }),
                store,
                authorizationIntentFactory = { android.content.Intent("test.oidc") },
            )
            coordinator.start()

            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.handleCallback("wrong", null, "code") }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.handleCallback("expected", "access_denied", null) }
            }
            assertNull(store.read())
            assertNull(store.readPendingOidc())
            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.handleCallback("expected", null, "replay") }
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancellationClearsPendingRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueueOidcStart("expected")
        try {
            val store = TestTokenStore()
            val base = ServerUrl.parse(server.url("/").toString())
            val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
            val coordinator = OidcCoordinator(
                ContextWrapper(null),
                api,
                AuthRepository(api, store, currentServerUrl = { base.normalized }),
                store,
                authorizationIntentFactory = { android.content.Intent("test.oidc") },
            )
            coordinator.start()

            assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.handleAuthorizationResult(null) }
            }
            assertNull(store.readPendingOidc())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun usesPublicProviderDetailsAndDiscovery() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueueOidcStart("state")
        try {
            val store = TestTokenStore()
            val base = ServerUrl.parse(server.url("/").toString())
            val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
            lateinit var data: io.github.kemko.grimmoryuploader.data.auth.OidcAuthorizationData
            OidcCoordinator(
                ContextWrapper(null),
                api,
                AuthRepository(api, store, currentServerUrl = { base.normalized }),
                store,
                authorizationIntentFactory = { value -> data = value; android.content.Intent("test.oidc") },
            ).start()
            assertEquals(server.url("/authorize").toString(), data.authorizationEndpoint)
            assertEquals("openid profile email", data.scope)
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsProviderScopesWithoutOpenid() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody(
                """{"oidcEnabled":true,"oidcProviderDetails":{"clientId":"mobile","issuerUri":"${server.url("/issuer")}","scopes":"profile email"}}""",
            ),
        )
        try {
            val base = ServerUrl.parse(server.url("/").toString())
            val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
            val coordinator = OidcCoordinator(
                ContextWrapper(null),
                api,
                AuthRepository(api, TestTokenStore(), currentServerUrl = { base.normalized }),
                TestTokenStore(),
                authorizationIntentFactory = { android.content.Intent("test.oidc") },
            )

            assertThrows(IllegalArgumentException::class.java) { runBlocking { coordinator.start() } }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun MockWebServer.enqueueOidcStart(state: String) {
        enqueue(
            MockResponse().setBody(
                """{"oidcEnabled":true,"oidcProviderDetails":{"clientId":"mobile","issuerUri":"${url("/issuer")}","scopes":"openid profile email"}}""",
            ),
        )
        enqueue(
            MockResponse().setBody(
                """{"authorization_endpoint":"${url("/authorize")}","token_endpoint":"${url("/token")}"}""",
            ),
        )
        enqueue(MockResponse().setBody("""{"state":"$state"}"""))
    }
}
