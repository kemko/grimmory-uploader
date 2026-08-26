package io.github.kemko.grimmoryuploader.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingIntentParserTest {
    private val parser = IncomingIntentParser()

    @Test
    fun parsesOneSharedFileAndOneUrl() {
        val file =
            parser.parse(
                IncomingIntentData(Intent.ACTION_SEND, streamUri = "content://books/Bad%20Name.fb2", mimeType = "application/octet-stream"),
            ) as IncomingInput.File
        assertEquals("Bad Name.fb2", file.displayName)

        val url = parser.parse(IncomingIntentData(Intent.ACTION_SEND, text = "https://example.test/books/book.epub")) as IncomingInput.Url
        assertEquals("book.epub", url.displayName)
        assertTrue(parser.parse(IncomingIntentData(Intent.ACTION_SEND, dataUri = "https://example.test/book.pdf")) is IncomingInput.Url)
    }

    @Test
    fun viewAcceptsOnlyLocalUri() {
        assertTrue(parser.parse(IncomingIntentData(Intent.ACTION_VIEW, dataUri = "file:///tmp/book.pdf")) is IncomingInput.File)
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(IncomingIntentData(Intent.ACTION_VIEW, dataUri = "https://example.test/book.pdf"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(IncomingIntentData(Intent.ACTION_SEND_MULTIPLE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(IncomingIntentData(Intent.ACTION_SEND, dataUri = "content://books/a.fb2", streamUri = "content://books/b.fb2"))
        }
    }

    @Test
    fun rejectsUnsafeOrNonHttpLinks() {
        assertFalse(IncomingIntentParser.isHttpUrl("file:///tmp/book.fb2"))
        assertFalse(IncomingIntentParser.isHttpUrl("https://user:password@example.test/book"))
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(IncomingIntentData(Intent.ACTION_SEND, text = "javascript:alert(1)"))
        }
        assertEquals("book_name.fb2", IncomingIntentParser.sanitizeDisplayName("../book:name.fb2"))
        assertEquals("book.epub", IncomingIntentParser.filenameFromContentDisposition("attachment; filename*=UTF-8''book.epub"))
    }
}
