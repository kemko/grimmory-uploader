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
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth)).build()
            val user = GrimmoryApi(client, serverUrl = { base }).currentUser()
            assertEquals(42L, user.id)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("/grimmory/api/v1/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
