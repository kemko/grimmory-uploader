package io.github.kemko.grimmoryuploader.upload

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (written: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        val progressSink =
            object : ForwardingSink(sink) {
                override fun write(
                    source: okio.Buffer,
                    byteCount: Long,
                ) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(written, total)
                }
            }
        val buffered = progressSink.buffer()
        try {
            delegate.writeTo(buffered)
            buffered.flush()
        } catch (error: IOException) {
            throw error
        }
    }
}
