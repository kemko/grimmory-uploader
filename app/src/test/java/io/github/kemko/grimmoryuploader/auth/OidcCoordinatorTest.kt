package io.github.kemko.grimmoryuploader.auth

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.OidcCallbackException
import io.github.kemko.grimmoryuploader.data.auth.OidcCallbackFailure
import io.github.kemko.grimmoryuploader.data.auth.OidcCoordinator
import io.github.kemko.grimmoryuploader.data.auth.OidcPendingStore
import io.github.kemko.grimmoryuploader.data.auth.Pkce
import io.github.kemko.grimmoryuploader.data.auth.TokenStore
import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OidcCoordinatorTest {
    @Test
    fun startsWithServerStateAndExchangesCallbackCode() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("server-state")
            server.enqueue(MockResponse().setBody("""{"accessToken":"oidc-access","refreshToken":"oidc-refresh","expires":3600}"""))
            try {
                val base = ServerUrl.parse(server.url("/grimmory/").toString())
                val store = TestTokenStore()
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                lateinit var authorizationData: io.github.kemko.grimmoryuploader.data.auth.OidcAuthorizationData
                val coordinator =
                    OidcCoordinator(
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
                assertEquals(authorizationData.redirectUri, callbackRequest.requestUrl?.queryParameter("redirect_uri"))
                assertEquals(authorizationData.nonce, callbackRequest.requestUrl?.queryParameter("nonce"))
                assertEquals(authorizationData.state, callbackRequest.requestUrl?.queryParameter("state"))
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
    fun rejectsInvalidErrorAndReplayedCallbacksWithoutTokenExchange() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator =
                    OidcCoordinator(
                        ContextWrapper(null),
                        api,
                        AuthRepository(api, store, currentServerUrl = { base.normalized }),
                        store,
                        authorizationIntentFactory = { android.content.Intent("test.oidc") },
                    )
                coordinator.start()

                val stateException =
                    assertThrows(OidcCallbackException::class.java) {
                        runBlocking { coordinator.handleCallback("wrong", null, "code") }
                    }
                assertEquals(OidcCallbackFailure.STATE_MISMATCH, stateException.failure)
                assertThrows(OidcCallbackException::class.java) {
                    runBlocking { coordinator.handleCallback("expected", "access_denied", null) }
                }
                assertNull(store.read())
                assertNull(store.readPendingOidc())
                assertThrows(OidcCallbackException::class.java) {
                    runBlocking { coordinator.handleCallback("expected", null, "replay") }
                }
                assertEquals(3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun cancellationClearsPendingRequest() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator =
                    OidcCoordinator(
                        ContextWrapper(null),
                        api,
                        AuthRepository(api, store, currentServerUrl = { base.normalized }),
                        store,
                        authorizationIntentFactory = { android.content.Intent("test.oidc") },
                    )
                coordinator.start()

                val exception =
                    assertThrows(OidcCallbackException::class.java) {
                        runBlocking { coordinator.handleAuthorizationResult(null) }
                    }
                assertEquals(OidcCallbackFailure.CANCELLED, exception.failure)
                assertNull(store.readPendingOidc())
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun appAuthCancellationAndStateMismatchKeepTheirFailureTypes() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator = coordinator(api, store, base)

                server.enqueueOidcStart("expected")
                coordinator.start()
                val cancellation =
                    assertThrows(OidcCallbackException::class.java) {
                        runBlocking {
                            coordinator.handleAuthorizationResult(
                                AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.toIntent(),
                            )
                        }
                    }
                assertEquals(OidcCallbackFailure.CANCELLED, cancellation.failure)
                assertNull(store.readPendingOidc())

                server.enqueueOidcStart("expected")
                coordinator.start()
                val mismatch =
                    assertThrows(OidcCallbackException::class.java) {
                        runBlocking {
                            coordinator.handleAuthorizationResult(
                                AuthorizationException.AuthorizationRequestErrors.STATE_MISMATCH.toIntent(),
                            )
                        }
                    }
                assertEquals(OidcCallbackFailure.STATE_MISMATCH, mismatch.failure)
                assertNull(store.readPendingOidc())
                assertEquals(6, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun appAuthSuccessUsesResponseStateAndCode() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            server.enqueue(MockResponse().setBody("""{"accessToken":"access","refreshToken":"refresh","expires":3600}"""))
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator = coordinator(api, store, base)
                coordinator.start()
                val request =
                    AuthorizationRequest
                        .Builder(
                            AuthorizationServiceConfiguration(
                                Uri.parse("https://issuer.example"),
                                Uri.parse("https://issuer.example/token"),
                            ),
                            "client",
                            ResponseTypeValues.CODE,
                            Uri.parse("io.github.kemko.grimmoryuploader:/oauth2redirect"),
                        ).build()
                val response =
                    AuthorizationResponse
                        .Builder(request)
                        .setState("expected")
                        .setAuthorizationCode("appauth-code")
                        .build()

                assertEquals("io.github.kemko.grimmoryuploader:/oauth2redirect", request.redirectUri.toString())
                coordinator.handleAuthorizationResult(response.toIntent())

                repeat(3) { server.takeRequest() }
                val callback = server.takeRequest()
                assertEquals("appauth-code", callback.requestUrl?.queryParameter("code"))
                assertEquals("expected", callback.requestUrl?.queryParameter("state"))
                assertNull(store.readPendingOidc())
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun providerOAuthErrorUsesStateFromRedirectUri() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator = coordinator(api, store, base)
                coordinator.start()
                val callback =
                    Uri.parse(
                        "io.github.kemko.grimmoryuploader:/oauth2redirect?state=expected&error=access_denied&" +
                            "error_description=User%20denied",
                    )
                val intent =
                    AuthorizationException
                        .fromOAuthRedirect(callback)
                        .toIntent()
                        .setData(callback)

                val exception =
                    assertThrows(OidcCallbackException::class.java) {
                        runBlocking { coordinator.handleAuthorizationResult(intent) }
                    }

                assertEquals(OidcCallbackFailure.PROVIDER_ERROR, exception.failure)
                assertEquals("access_denied", exception.errorCode)
                assertEquals("User denied", exception.errorDescription)
                assertNull(store.readPendingOidc())
                assertEquals(3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun appAuthGeneralErrorIsNotStateMismatch() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator = coordinator(api, store, base)
                coordinator.start()

                val exception =
                    assertThrows(OidcCallbackException::class.java) {
                        runBlocking {
                            coordinator.handleAuthorizationResult(AuthorizationException.GeneralErrors.NETWORK_ERROR.toIntent())
                        }
                    }

                assertEquals(OidcCallbackFailure.APP_AUTH_ERROR, exception.failure)
                assertTrue(exception.failure != OidcCallbackFailure.STATE_MISMATCH)
                assertNull(store.readPendingOidc())
                assertEquals(3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun exchangeFailureClearsPendingRequestAndRejectsReplay() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"message":"OIDC callback failed"}"""))
            try {
                val store = TestTokenStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator = coordinator(api, store, base)
                coordinator.start()

                val exchangeFailure =
                    assertThrows(ApiException::class.java) {
                        runBlocking { coordinator.handleCallback("expected", null, "code") }
                    }
                assertEquals(502, exchangeFailure.statusCode)
                assertNull(store.read())
                assertNull(store.readPendingOidc())

                assertThrows(OidcCallbackException::class.java) {
                    runBlocking { coordinator.handleCallback("expected", null, "replay") }
                }
                assertEquals(4, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun cancelledExchangeClearsPendingRequest() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueueOidcStart("expected")
            server.enqueue(
                MockResponse()
                    .setBody("""{"accessToken":"access","refreshToken":"refresh","expires":3600}""")
                    .setHeadersDelay(30, TimeUnit.SECONDS),
            )
            try {
                val store = CancellationCheckingStore()
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator =
                    OidcCoordinator(
                        ContextWrapper(null),
                        api,
                        AuthRepository(api, store, currentServerUrl = { base.normalized }),
                        store,
                        authorizationIntentFactory = { Intent("test.oidc") },
                    )
                coordinator.start()
                repeat(3) { server.takeRequest() }

                val callback = launch(Dispatchers.IO) { coordinator.handleCallback("expected", null, "code") }
                server.takeRequest()
                callback.cancelAndJoin()

                assertNull(store.readPendingOidc())
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun usesPublicProviderDetailsAndDiscovery() =
        runBlocking {
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
                    authorizationIntentFactory = { value ->
                        data = value
                        android.content.Intent("test.oidc")
                    },
                ).start()
                assertEquals(server.url("/authorize").toString(), data.authorizationEndpoint)
                assertEquals("openid profile email", data.scope)
                assertEquals(3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun rejectsProviderScopesWithoutOpenid() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """{"oidcEnabled":true,"oidcProviderDetails":{"clientId":"mobile","issuerUri":"${server.url(
                        "/issuer",
                    )}","scopes":"profile email"}}""",
                ),
            )
            try {
                val base = ServerUrl.parse(server.url("/").toString())
                val api = GrimmoryApi(OkHttpClient(), serverUrl = { base })
                val coordinator =
                    OidcCoordinator(
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
                """{"oidcEnabled":true,"oidcProviderDetails":{"clientId":"mobile","issuerUri":"${url(
                    "/issuer",
                )}","scopes":"openid profile email"}}""",
            ),
        )
        enqueue(
            MockResponse().setBody(
                """{"authorization_endpoint":"${url("/authorize")}","token_endpoint":"${url("/token")}"}""",
            ),
        )
        enqueue(MockResponse().setBody("""{"state":"$state"}"""))
    }

    private fun coordinator(
        api: GrimmoryApi,
        store: TestTokenStore,
        base: ServerUrl,
    ) = OidcCoordinator(
        ContextWrapper(null),
        api,
        AuthRepository(api, store, currentServerUrl = { base.normalized }),
        store,
        authorizationIntentFactory = { Intent("test.oidc") },
    )

    private class CancellationCheckingStore(
        private val delegate: TestTokenStore = TestTokenStore(),
    ) : TokenStore by delegate,
        OidcPendingStore by delegate {
        override suspend fun clearPendingOidc() {
            yield()
            delegate.clearPendingOidc()
        }
    }
}
