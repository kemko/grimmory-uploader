package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.AuthInterceptor
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInterceptorTest {
    @Test
    fun refreshesAndRetriesA401OnlyOnce() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"accessToken":"new","refreshToken":"r2","expiresIn":3600}"""))
        server.enqueue(MockResponse().setBody("""{"id":42}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"accessToken":"newer","refreshToken":"r3","expiresIn":3600}"""))
        server.enqueue(MockResponse().setBody("""{"id":43}"""))
        server.start()
        try {
            val store = TestTokenStore()
            val base = ServerUrl.parse(server.url("/grimmory/").toString())
            store.write(TokenPair("old", "r", System.currentTimeMillis() + 600_000, base.normalized))
            val auth = AuthRepository(
                GrimmoryApi(OkHttpClient(), serverUrl = { base }),
                store,
                currentServerUrl = { base.normalized },
            )
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth) { base }).build()
            val api = GrimmoryApi(client, serverUrl = { base })
            val user = api.currentUser()
            assertEquals(42L, user.id)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("/grimmory/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            assertEquals(43L, api.currentUser().id)
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            assertEquals("/grimmory/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer newer", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refreshFailureClearsCredentialsAndLeavesRequestUnauthorized() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        try {
            val store = TestTokenStore().apply {
                val serverUrl = ServerUrl.parse(server.url("/grimmory/").toString()).normalized
                write(TokenPair("old", "refresh", System.currentTimeMillis() + 600_000, serverUrl))
            }
            val base = ServerUrl.parse(server.url("/grimmory/").toString())
            val auth = AuthRepository(
                GrimmoryApi(OkHttpClient(), serverUrl = { base }),
                store,
                currentServerUrl = { base.normalized },
            )
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth) { base }).build()

            val error = org.junit.Assert.assertThrows(io.github.kemko.grimmoryuploader.data.network.ApiException::class.java) {
                runBlocking { GrimmoryApi(client, serverUrl = { base }).currentUser() }
            }

            assertEquals(401, error.statusCode)
            assertNull(store.read())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun transientPreRequestRefreshFailureIsRetryable() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val base = ServerUrl.parse(server.url("/").toString())
            val store = TestTokenStore().apply {
                write(TokenPair("expired", "refresh", 0, base.normalized))
            }
            val auth = AuthRepository(
                GrimmoryApi(OkHttpClient(), serverUrl = { base }),
                store,
                currentServerUrl = { base.normalized },
            )
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth) { base }).build()

            org.junit.Assert.assertThrows(IOException::class.java) {
                runBlocking { GrimmoryApi(client, serverUrl = { base }).currentUser() }
            }

            assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun transientPostRequestRefreshFailureIsRetryable() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        try {
            val base = ServerUrl.parse(server.url("/").toString())
            val store = TestTokenStore().apply {
                write(TokenPair("access", "refresh", Long.MAX_VALUE, base.normalized))
            }
            val auth = AuthRepository(
                GrimmoryApi(OkHttpClient(), serverUrl = { base }),
                store,
                currentServerUrl = { base.normalized },
            )
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth) { base }).build()

            org.junit.Assert.assertThrows(IOException::class.java) {
                runBlocking { GrimmoryApi(client, serverUrl = { base }).currentUser() }
            }

            assertEquals("/api/v1/users/me", server.takeRequest().path)
            assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun neverSendsBearerTokenOutsideConfiguredServer() = runBlocking {
        val trusted = MockWebServer()
        val untrusted = MockWebServer()
        untrusted.enqueue(MockResponse().setBody("""{"id":7}"""))
        trusted.start()
        untrusted.start()
        try {
            val store = TestTokenStore().apply {
                write(
                    TokenPair(
                        "secret",
                        "refresh",
                        System.currentTimeMillis() + 600_000,
                        trusted.url("/grimmory/").toString().trimEnd('/'),
                    ),
                )
            }
            val trustedBase = ServerUrl.parse(trusted.url("/grimmory/").toString())
            val auth = AuthRepository(
                GrimmoryApi(OkHttpClient(), serverUrl = { trustedBase }),
                store,
                currentServerUrl = { trustedBase.normalized },
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(auth) { trustedBase })
                .build()

            GrimmoryApi(
                client,
                serverUrl = { ServerUrl.parse(untrusted.url("/").toString()) },
            ).currentUser()

            assertNull(untrusted.takeRequest().getHeader("Authorization"))
        } finally {
            trusted.shutdown()
            untrusted.shutdown()
        }
    }
}
