package io.github.kemko.grimmoryuploader.upload

import java.util.concurrent.atomic.AtomicLong
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressRequestBodyTest {
    @Test
    fun reportsBytesAndPreservesBody() {
        val written = AtomicLong()
        val body = ProgressRequestBody("book".toRequestBody()) { current, _ -> written.set(current) }
        val sink = Buffer()
        body.writeTo(sink)
        assertEquals("book", sink.readUtf8())
        assertEquals(4L, written.get())
    }
}
