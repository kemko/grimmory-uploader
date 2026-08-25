package io.github.kemko.grimmoryuploader.format

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFormatTest {
    private val detector = BookFormatDetector()
    private val transformer = BookTransformer()
    private val fb2 = "<?xml version=\"1.0\"?><FictionBook xmlns=\"http://www.grimmory.test/fb2\"><description/><body><p>book</p></body></FictionBook>"

    @Test
    fun detectsContentNotExtension() {
        val dir = Files.createTempDirectory("formats").toFile()
        val pdf = File(dir, "wrong.fb2").apply { writeText("%PDF-1.7\n") }
        val xml = File(dir, "wrong.bin").apply { writeText(fb2) }
        assertEquals(BookFormat.PDF, detector.detect(pdf))
        assertEquals(BookFormat.FB2, detector.detect(xml))
        val djvu = File(dir, "book.djvu").apply { writeBytes("AT&TFORM\u0000\u0000\u0000\u0000DJVU".toByteArray()) }
        assertThrows(UnsupportedBookException::class.java) { detector.detect(djvu) }
        dir.deleteRecursively()
    }

    @Test
    fun detectsAndRejectsArchives() {
        val dir = Files.createTempDirectory("archives").toFile()
        val epub = zip(dir, "book.any", listOf("mimetype" to "application/epub+zip", "OEBPS/content.xhtml" to "<html/>"), storedFirst = true)
        val fb2zip = zip(dir, "book.zip", listOf("nested/book.fb2" to fb2))
        val ordinary = zip(dir, "ordinary.zip", listOf("readme.txt" to "hello"))
        assertEquals(BookFormat.EPUB, detector.detect(epub))
        assertEquals(BookFormat.FB2_ZIP, detector.detect(fb2zip))
        assertThrows(UnsupportedBookException::class.java) { detector.detect(ordinary) }
        dir.deleteRecursively()
    }

    @Test
    fun detectsPdfFb2AndEpubFromStreams() {
        val epub = zip(
            Files.createTempDirectory("stream-epub").toFile(),
            "book.epub",
            listOf("mimetype" to "application/epub+zip"),
            storedFirst = true,
        ).readBytes()

        assertEquals(BookFormat.PDF, detector.detect(ByteArrayInputStream("%PDF-1.7".toByteArray())))
        assertEquals(BookFormat.FB2, detector.detect(ByteArrayInputStream(fb2.toByteArray())))
        assertEquals(BookFormat.EPUB, detector.detect(ByteArrayInputStream(epub)))
    }

    @Test
    fun rejectsDuplicateAndExternalEntityFb2() {
        val dir = Files.createTempDirectory("invalid").toFile()
        val duplicate = zip(dir, "duplicate.zip", listOf("a.fb2" to fb2, "b.fb2" to fb2))
        assertThrows(IllegalArgumentException::class.java) { detector.detect(duplicate) }
        val evil = File(dir, "evil.xml").apply {
            writeText("<!DOCTYPE FictionBook [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><FictionBook>&x;</FictionBook>")
        }
        assertThrows(Exception::class.java) { detector.detect(evil) }
        dir.deleteRecursively()
    }

    @Test
    fun transformsWithoutCreatingOutputFile() {
        val dir = Files.createTempDirectory("transform").toFile()
        val source = File(dir, "book.fb2").apply { writeText(fb2) }
        val output = ByteArrayOutputStream()
        transformer.transform(source, BookFormat.FB2, output)
        assertArrayEquals(fb2.toByteArray(), output.toByteArray())
        assertEquals(setOf("book.fb2"), dir.listFiles()!!.map(File::getName).toSet())
        dir.deleteRecursively()
    }

    @Test
    fun epubMimetypeIsStoredFirstAndOtherEntriesAreCompressed() {
        val dir = Files.createTempDirectory("epub").toFile()
        val source = zip(dir, "book.epub", listOf("mimetype" to "application/epub+zip", "OEBPS/content.xhtml" to "x".repeat(10_000)), storedFirst = true)
        val output = ByteArrayOutputStream()
        transformer.transform(source, BookFormat.EPUB, output)
        val result = File(dir, "result.epub").apply { writeBytes(output.toByteArray()) }
        ZipFile(result).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("OEBPS/content.xhtml").method)
        }
        assertFalse(File(dir, "book.fb2").exists())
        dir.deleteRecursively()
    }

    @Test
    fun extractsFb2ZipAndPassesThroughEpubWhenRecompressionIsDisabled() {
        val dir = Files.createTempDirectory("transform-options").toFile()
        val fb2zip = zip(dir, "book.fb2.zip", listOf("nested/book.fb2" to fb2))
        val fb2Output = ByteArrayOutputStream()
        transformer.transform(fb2zip, BookFormat.FB2_ZIP, fb2Output)
        assertArrayEquals(fb2.toByteArray(), fb2Output.toByteArray())

        val epub = zip(dir, "book.epub", listOf("mimetype" to "application/epub+zip", "content" to "body"), storedFirst = true)
        val epubOutput = ByteArrayOutputStream()
        transformer.transform(epub, BookFormat.EPUB, epubOutput, recompressEpub = false)
        assertArrayEquals(epub.readBytes(), epubOutput.toByteArray())
        dir.deleteRecursively()
    }

    private fun zip(dir: File, name: String, entries: List<Pair<String, String>>, storedFirst: Boolean = false): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entryName, value) ->
                val entry = ZipEntry(entryName)
                if (storedFirst && entryName == "mimetype") {
                    val bytes = value.toByteArray(StandardCharsets.UTF_8)
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    val crc = java.util.zip.CRC32().apply { update(bytes) }
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(value.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
