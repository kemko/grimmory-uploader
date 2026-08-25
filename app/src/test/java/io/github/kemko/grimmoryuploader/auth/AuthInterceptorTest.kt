package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.AuthInterceptor
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
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
        server.start()
        try {
            val store = TestTokenStore()
            store.write(TokenPair("old", "r", System.currentTimeMillis() + 600_000))
            val base = ServerUrl.parse(server.url("/grimmory/").toString())
            val auth = AuthRepository(GrimmoryApi(OkHttpClient(), serverUrl = { base }), store)
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth) { base }).build()
            val user = GrimmoryApi(client, serverUrl = { base }).currentUser()
            assertEquals(42L, user.id)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("/grimmory/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
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
                write(TokenPair("old", "refresh", System.currentTimeMillis() + 600_000))
            }
            val base = ServerUrl.parse(server.url("/grimmory/").toString())
            val auth = AuthRepository(GrimmoryApi(OkHttpClient(), serverUrl = { base }), store)
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
    fun neverSendsBearerTokenOutsideConfiguredServer() = runBlocking {
        val trusted = MockWebServer()
        val untrusted = MockWebServer()
        untrusted.enqueue(MockResponse().setBody("""{"id":7}"""))
        trusted.start()
        untrusted.start()
        try {
            val store = TestTokenStore().apply {
                write(TokenPair("secret", "refresh", System.currentTimeMillis() + 600_000))
            }
            val trustedBase = ServerUrl.parse(trusted.url("/grimmory/").toString())
            val auth = AuthRepository(GrimmoryApi(OkHttpClient(), serverUrl = { trustedBase }), store)
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
