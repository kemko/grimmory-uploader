package io.github.kemko.grimmoryuploader.format

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class BookFormatDetector {
    fun detect(file: File, hint: String? = null, cancelled: () -> Boolean = { false }): BookFormat {
        require(file.isFile) { "Book source does not exist" }
        file.inputStream().use { input ->
            val prefix = input.readNBytes(16)
            if (prefix.startsWith(PDF_SIGNATURE)) return BookFormat.PDF
            if (prefix.isDjvu()) throw UnsupportedBookException("DJVU is not supported")
            if (prefix.startsWith(ZIP_SIGNATURE)) return detectZip(file, cancelled)
            return detectXml(file, cancelled)
        }
    }

    fun detect(input: InputStream, hint: String? = null, cancelled: () -> Boolean = { false }): BookFormat {
        val checked = CancellableInputStream(input, cancelled)
        val buffered = BufferedInputStream(checked)
        buffered.mark(16)
        val prefix = buffered.readNBytes(16)
        buffered.reset()
        if (prefix.startsWith(PDF_SIGNATURE)) return BookFormat.PDF
        if (prefix.isDjvu()) throw UnsupportedBookException("DJVU is not supported")
        if (prefix.startsWith(ZIP_SIGNATURE)) return detectZipStream(buffered, cancelled)
        return detectXml(buffered)
    }

    private fun detectZip(file: File, cancelled: () -> Boolean): BookFormat = ZipFile(file).use { zip ->
        var total = 0L
        var entries = 0
        var fb2Count = 0
        var epub = false
        var firstEntry = true
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            ensureNotCancelled(cancelled)
            val entry = enumeration.nextElement()
            entries++
            require(entries <= ZipGuards.MAX_ENTRIES) { "Too many ZIP entries" }
            val size = entry.size.coerceAtLeast(0)
            total = Math.addExact(total, size)
            ZipGuards.validateEntry(entry, entry.compressedSize, total)
            val lower = entry.name.lowercase()
            if (firstEntry && entry.name == "mimetype") {
                val value = zip.getInputStream(entry).use { CancellableInputStream(it, cancelled).readNBytes(64).toString(StandardCharsets.UTF_8).trim() }
                if (value == "application/epub+zip" && entry.method == java.util.zip.ZipEntry.STORED) epub = true
            }
            if (!entry.isDirectory && lower.endsWith(".fb2")) {
                zip.getInputStream(entry).use { if (isFictionBook(CancellableInputStream(it, cancelled))) fb2Count++ }
            }
            firstEntry = false
        }
        require(fb2Count <= 1) { "Archive contains multiple FB2 books" }
        when {
            epub -> BookFormat.EPUB
            fb2Count == 1 -> BookFormat.FB2_ZIP
            else -> throw UnsupportedBookException("Unsupported ZIP archive")
        }
    }

    private fun detectZipStream(input: InputStream, cancelled: () -> Boolean): BookFormat {
        var entries = 0
        var total = 0L
        var fb2Count = 0
        var epub = false
        var firstEntry = true
        ZipInputStream(input).use { zip ->
            while (true) {
                ensureNotCancelled(cancelled)
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= ZipGuards.MAX_ENTRIES) { "Too many ZIP entries" }
                ZipGuards.validateName(entry.name)
                val counter = CountingInputStream(zip)
                val content = if (firstEntry && entry.name == "mimetype") counter.readNBytes(64) else null
                if (entry.name.lowercase().endsWith(".fb2")) {
                    require(isFictionBook(counter)) { "FB2 XML is invalid" }
                    fb2Count++
                }
                counter.copyTo(java.io.OutputStream.nullOutputStream(), DEFAULT_BUFFER_SIZE)
                val entryBytes = counter.count
                require(entryBytes <= ZipGuards.MAX_ENTRY_SIZE) { "ZIP entry is too large" }
                total = Math.addExact(total, entryBytes)
                ZipGuards.validateEntry(entry, entry.compressedSize, total)
                if (firstEntry && entry.name == "mimetype" && content?.toString(StandardCharsets.UTF_8)?.trim() == "application/epub+zip" && entry.method == ZipEntry.STORED) epub = true
                firstEntry = false
            }
        }
        require(fb2Count <= 1) { "Archive contains multiple FB2 books" }
        return when {
            epub -> BookFormat.EPUB
            fb2Count == 1 -> BookFormat.FB2_ZIP
            else -> throw UnsupportedBookException("Unsupported ZIP archive")
        }
    }

    private fun detectXml(file: File, cancelled: () -> Boolean): BookFormat =
        file.inputStream().use { detectXml(CancellableInputStream(it, cancelled)) }

    private fun detectXml(input: InputStream): BookFormat {
        require(isFictionBook(input)) { "XML root is not FictionBook" }
        return BookFormat.FB2
    }

    private fun isFictionBook(input: InputStream): Boolean {
        var root: String? = null
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.newSAXParser().parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (root == null) root = (localName.orEmpty().ifBlank { qName }).substringAfter(':')
            }
        })
        return root == "FictionBook"
    }

    private fun ByteArray.startsWith(value: ByteArray) = size >= value.size && value.indices.all { this[it] == value[it] }

    private fun ByteArray.isDjvu(): Boolean = size >= 8 &&
        String(this, 0, 8, StandardCharsets.US_ASCII) == "AT&TFORM" &&
        String(this, 8.coerceAtMost(size), (size - 8).coerceAtLeast(0), StandardCharsets.US_ASCII).contains("DJVU")

    companion object {
        private val PDF_SIGNATURE = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
        private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    }

    private fun ensureNotCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw kotlinx.coroutines.CancellationException("Transfer cancelled")
    }
}

private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) count++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { if (it > 0) count += it }
}

private class CancellableInputStream(
    private val delegate: InputStream,
    private val cancelled: () -> Boolean,
) : InputStream() {
    override fun read(): Int {
        checkCancellation()
        return delegate.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkCancellation()
        return delegate.read(buffer, offset, length)
    }

    override fun close() = delegate.close()

    private fun checkCancellation() {
        if (cancelled()) throw kotlinx.coroutines.CancellationException("Transfer cancelled")
    }
}
