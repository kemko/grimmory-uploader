package io.github.kemko.grimmoryuploader.network

import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GrimmoryApiTest {
    @Test
    fun rejectsOversizedSuccessAndErrorBodies() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(1024 * 1024 + 1)))
        server.start()
        try {
            val api = GrimmoryApi(
                OkHttpClient(),
                serverUrl = { ServerUrl.parse(server.url("/").toString()) },
            )
            repeat(2) {
                val error = assertThrows(ApiException::class.java) {
                    runBlocking { api.healthcheck() }
                }
                assertEquals("Grimmory response is too large", error.message)
            }
        } finally {
            server.shutdown()
        }
    }
}
