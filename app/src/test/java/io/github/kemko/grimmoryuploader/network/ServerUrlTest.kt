package io.github.kemko.grimmoryuploader.network

import io.github.kemko.grimmoryuploader.data.network.InvalidServerUrl
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizesHostPortAndPathPrefix() {
        val server = ServerUrl.parse(" HTTPS://Example.COM:443/grimmory/ ")
        assertEquals("https://example.com/grimmory", server.normalized)
        assertEquals("/grimmory/api/v1/users/me", server.endpoint("api/v1/users/me").encodedPath)
    }

    @Test
    fun rejectsNonHttpCredentialsAndQuery() {
        listOf("ftp://example.com", "https://user:pass@example.com", "https://example.com/?x=1")
            .forEach { value ->
                try {
                    ServerUrl.parse(value)
                    throw AssertionError("Expected invalid URL: $value")
                } catch (_: InvalidServerUrl) {
                    assertTrue(true)
                }
            }
    }
}
