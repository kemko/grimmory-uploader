package io.github.kemko.grimmoryuploader.format

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BookTransformer {
    fun transform(
        source: File,
        format: BookFormat,
        output: OutputStream,
        recompressEpub: Boolean = true,
        cancelled: () -> Boolean = { false },
    ) {
        when (format) {
            BookFormat.PDF -> source.inputStream().use { it.copyCancellableTo(output, cancelled) }
            BookFormat.FB2 -> source.inputStream().use { it.copyCancellableTo(output, cancelled) }
            BookFormat.FB2_ZIP -> copyFb2FromZip(source, output, cancelled)
            BookFormat.EPUB -> if (recompressEpub) recompressEpub(source, output, cancelled) else {
                source.inputStream().use { it.copyCancellableTo(output, cancelled) }
            }
        }
    }

    private fun copyFb2FromZip(source: File, output: OutputStream, cancelled: () -> Boolean = { false }) {
        ZipGuards.validateArchiveMetadata(source)
        ZipFile(source).use { zip ->
            val entry = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }.singleOrNull()
                ?: throw UnsupportedBookException("Expected exactly one FB2 entry")
            zip.getInputStream(entry).use { it.copyCancellableTo(output, cancelled) }
        }
    }

    private fun recompressEpub(source: File, output: OutputStream, cancelled: () -> Boolean = { false }) {
        ZipGuards.validateArchiveMetadata(source)
        ZipFile(source).use { input ->
            val zipOutput = ZipOutputStream(output).apply { setLevel(Deflater.BEST_COMPRESSION) }
            val mimetype = input.getEntry("mimetype") ?: throw UnsupportedBookException("EPUB mimetype is missing")
            val mimetypeBytes = input.getInputStream(mimetype).use { it.readNBytes(64) }
            require(mimetypeBytes.toString(Charsets.UTF_8).trim() == "application/epub+zip") { "Invalid EPUB mimetype" }
            zipOutput.use { zip ->
                val stored = java.util.zip.ZipEntry("mimetype").apply {
                    method = java.util.zip.ZipEntry.STORED
                    size = mimetypeBytes.size.toLong()
                    val checksum = CRC32()
                    checksum.update(mimetypeBytes)
                    crc = checksum.value
                }
                zip.putNextEntry(stored)
                zip.write(mimetypeBytes)
                zip.closeEntry()
                input.entries().asSequence().filter { it.name != "mimetype" }.forEach { entry ->
                    checkNotCancelled(cancelled)
                    ZipGuards.validateName(entry.name)
                    val next = java.util.zip.ZipEntry(entry.name).apply { method = java.util.zip.ZipEntry.DEFLATED }
                    zip.putNextEntry(next)
                    input.getInputStream(entry).use { it.copyCancellableTo(zip, cancelled) }
                    zip.closeEntry()
                }
            }
        }
    }
}

private fun java.io.InputStream.copyCancellableTo(output: OutputStream, cancelled: () -> Boolean) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        checkNotCancelled(cancelled)
        val count = read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}

private fun checkNotCancelled(cancelled: () -> Boolean) {
    if (cancelled()) throw kotlinx.coroutines.CancellationException("Transfer cancelled")
}
