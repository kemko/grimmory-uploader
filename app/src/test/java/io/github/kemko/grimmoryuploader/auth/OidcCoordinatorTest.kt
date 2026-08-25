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
import org.junit.Assert.assertTrue
import org.junit.Test

class OidcCoordinatorTest {
    @Test
    fun startsWithServerStateAndExchangesCallbackCode() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody(
                """{"state":"server-state","authorizationEndpoint":"${server.url("/authorize")}","clientId":"mobile"}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"accessToken":"oidc-access","refreshToken":"oidc-refresh","expiresIn":3600}"""))
        try {
            val base = ServerUrl.parse(server.url("/grimmory/").toString())
            val store = TestTokenStore()
            val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
            lateinit var authorizationData: io.github.kemko.grimmoryuploader.data.auth.OidcAuthorizationData
            val coordinator = OidcCoordinator(
                ContextWrapper(null),
                api,
                AuthRepository(api, store),
                authorizationIntentFactory = { data ->
                    authorizationData = data
                    android.content.Intent("test.oidc")
                },
            )
            val intent = coordinator.start()
            assertEquals("server-state", authorizationData.state)
            assertEquals(Pkce.challenge(authorizationData.codeVerifier), authorizationData.codeChallenge)
            coordinator.handleCallback(state = "server-state", error = null, code = "abc")
            assertEquals("oidc-access", store.read()!!.accessToken)
            assertTrue(server.takeRequest().path!!.endsWith("/auth/oidc/state"))
            val callbackRequest = server.takeRequest()
            val callbackBody = callbackRequest.body.readUtf8()
            assertTrue(callbackBody.contains("\"code\":\"abc\""))
            assertTrue(callbackBody.contains("codeVerifier"))
            coordinator.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pkceChallengeIsStableForVerifier() {
        assertEquals(Pkce.challenge("abc"), Pkce.challenge("abc"))
    }
}
