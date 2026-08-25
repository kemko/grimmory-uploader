package io.github.kemko.grimmoryuploader.network

import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GrimmoryApiTest {
    @Test
    fun acceptsRealHealthcheckEnvelopeAndNoContentUpload() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"status":200,"message":"Pong","data":{"status":"UP","version":"1.0"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        try {
            val api = GrimmoryApi(
                OkHttpClient(),
                serverUrl = { ServerUrl.parse(server.url("/").toString()) },
            )

            api.healthcheck()
            api.upload(1, 2, "book.fb2", "application/x-fictionbook+xml", "book".toRequestBody())

            assertEquals("/api/v1/healthcheck", server.takeRequest().path)
            assertEquals("/api/v1/files/upload?libraryId=1&pathId=2", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendsOidcCallbackAsQueryParametersWithEmptyBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"access","refreshToken":"refresh","expires":3600}""",
            ),
        )
        server.start()
        try {
            val api = GrimmoryApi(
                OkHttpClient(),
                serverUrl = { ServerUrl.parse(server.url("/").toString()) },
            )

            api.oidcCallback(
                io.github.kemko.grimmoryuploader.data.network.OidcCallbackRequest(
                    code = "code",
                    state = "state",
                    redirectUri = "app:/callback",
                    codeVerifier = "verifier",
                    nonce = "nonce",
                ),
            )

            val request = server.takeRequest()
            assertEquals("code", request.requestUrl?.queryParameter("code"))
            assertEquals("verifier", request.requestUrl?.queryParameter("code_verifier"))
            assertEquals("app:/callback", request.requestUrl?.queryParameter("redirect_uri"))
            assertEquals("nonce", request.requestUrl?.queryParameter("nonce"))
            assertEquals("state", request.requestUrl?.queryParameter("state"))
            assertEquals(0L, request.bodySize)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun refusesToFollowApiRedirects() {
        val origin = MockWebServer()
        val target = MockWebServer()
        origin.start()
        target.start()
        origin.enqueue(
            MockResponse()
                .setResponseCode(307)
                .addHeader("Location", target.url("/stolen")),
        )
        try {
            val api = GrimmoryApi(
                OkHttpClient(),
                serverUrl = { ServerUrl.parse(origin.url("/").toString()) },
            )

            val error = assertThrows(ApiException::class.java) {
                runBlocking { api.login("user", "password") }
            }

            assertEquals(307, error.statusCode)
            assertEquals(0, target.requestCount)
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

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
