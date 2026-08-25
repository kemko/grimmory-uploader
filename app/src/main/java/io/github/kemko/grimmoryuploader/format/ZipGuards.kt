package io.github.kemko.grimmoryuploader.format

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry

object ZipGuards {
    const val MAX_ENTRIES = 2_000
    const val MAX_TOTAL_UNCOMPRESSED = 512L * 1024 * 1024
    const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 100L
    const val MAX_NAME_LENGTH = 240
    const val MAX_PATH_DEPTH = 16
    const val MAX_CENTRAL_DIRECTORY_BYTES = 16L * 1024 * 1024

    fun validateArchiveMetadata(file: File) {
        val length = file.length()
        require(length >= MIN_END_RECORD_BYTES) { "ZIP end record is missing" }
        RandomAccessFile(file, "r").use { archive ->
            val tailSize = minOf(length, MAX_END_SEARCH_BYTES).toInt()
            val tail = ByteArray(tailSize)
            archive.seek(length - tailSize)
            archive.readFully(tail)
            val endOffset = findEndRecord(tail)
            require(endOffset >= 0) { "ZIP end record is missing" }

            val disk = tail.unsignedShort(endOffset + 4)
            val directoryDisk = tail.unsignedShort(endOffset + 6)
            val diskEntries = tail.unsignedShort(endOffset + 8)
            val entries = tail.unsignedShort(endOffset + 10)
            val directorySize = tail.unsignedInt(endOffset + 12)
            val directoryOffset = tail.unsignedInt(endOffset + 16)
            require(disk == 0 && directoryDisk == 0 && diskEntries == entries) {
                "Multi-disk ZIP archives are not supported"
            }
            require(entries != ZIP64_SHORT && directorySize != ZIP64_INT && directoryOffset != ZIP64_INT) {
                "ZIP64 archives are not supported"
            }
            require(entries <= MAX_ENTRIES) { "Too many ZIP entries" }
            require(directorySize <= MAX_CENTRAL_DIRECTORY_BYTES) { "ZIP central directory is too large" }

            val absoluteEndOffset = length - tailSize + endOffset
            require(directoryOffset <= absoluteEndOffset && directorySize <= absoluteEndOffset - directoryOffset) {
                "ZIP central directory is invalid"
            }
            val directory = ByteArray(directorySize.toInt())
            archive.seek(directoryOffset)
            archive.readFully(directory)
            var cursor = 0
            repeat(entries) {
                require(cursor <= directory.size - CENTRAL_HEADER_BYTES) { "ZIP central directory is invalid" }
                require(directory.unsignedInt(cursor) == CENTRAL_HEADER_SIGNATURE) {
                    "ZIP central directory is invalid"
                }
                val recordSize = CENTRAL_HEADER_BYTES +
                    directory.unsignedShort(cursor + 28) +
                    directory.unsignedShort(cursor + 30) +
                    directory.unsignedShort(cursor + 32)
                require(recordSize <= directory.size - cursor) { "ZIP central directory is invalid" }
                cursor += recordSize
            }
            require(cursor == directory.size) { "ZIP central directory is invalid" }
        }
    }

    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Unsafe ZIP entry name" }
        require(!name.startsWith('/') && !name.startsWith('\\')) { "Absolute ZIP entry path" }
        val parts = name.replace('\\', '/').split('/')
        val pathParts = if (name.endsWith('/') || name.endsWith('\\')) parts.dropLast(1) else parts
        require(
            pathParts.isNotEmpty() && pathParts.size <= MAX_PATH_DEPTH &&
                pathParts.none { it == "." || it == ".." || it.isEmpty() },
        ) {
            "Unsafe ZIP entry path"
        }
    }

    fun validateEntry(entry: ZipEntry, compressedSize: Long, total: Long) {
        validateName(entry.name)
        require(entry.size <= MAX_ENTRY_SIZE || entry.size < 0) { "ZIP entry is too large" }
        if (entry.size >= 0 && compressedSize > 0) {
            require(entry.size.toDouble() / compressedSize <= MAX_COMPRESSION_RATIO.toDouble()) {
                "ZIP compression ratio is unsafe"
            }
        }
        require(total <= MAX_TOTAL_UNCOMPRESSED) { "ZIP archive is too large" }
    }

    fun validateActualEntry(entry: ZipEntry, compressedSize: Long, actualSize: Long, total: Long) {
        validateName(entry.name)
        require(actualSize <= MAX_ENTRY_SIZE) { "ZIP entry is too large" }
        require(entry.size < 0 || entry.size == actualSize) { "ZIP entry size is invalid" }
        if (compressedSize > 0) {
            require(actualSize.toDouble() / compressedSize <= MAX_COMPRESSION_RATIO.toDouble()) {
                "ZIP compression ratio is unsafe"
            }
        }
        require(total <= MAX_TOTAL_UNCOMPRESSED) { "ZIP archive is too large" }
    }

    fun maxReadableBytes(compressedSize: Long, totalBeforeEntry: Long): Long {
        val ratioLimit = if (compressedSize > 0 && compressedSize <= Long.MAX_VALUE / MAX_COMPRESSION_RATIO) {
            compressedSize * MAX_COMPRESSION_RATIO
        } else {
            Long.MAX_VALUE
        }
        return minOf(MAX_ENTRY_SIZE, MAX_TOTAL_UNCOMPRESSED - totalBeforeEntry, ratioLimit)
            .coerceAtLeast(0)
    }

    private fun findEndRecord(bytes: ByteArray): Int {
        for (offset in bytes.size - MIN_END_RECORD_BYTES downTo 0) {
            if (
                bytes.unsignedInt(offset) == END_HEADER_SIGNATURE &&
                offset + MIN_END_RECORD_BYTES + bytes.unsignedShort(offset + 20) == bytes.size
            ) {
                return offset
            }
        }
        return -1
    }

    private fun ByteArray.unsignedShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.unsignedInt(offset: Int): Long =
        unsignedShort(offset).toLong() or (unsignedShort(offset + 2).toLong() shl 16)

    private const val MIN_END_RECORD_BYTES = 22
    private const val MAX_END_SEARCH_BYTES = MIN_END_RECORD_BYTES + 65_535L
    private const val CENTRAL_HEADER_BYTES = 46
    private const val END_HEADER_SIGNATURE = 0x06054b50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val ZIP64_SHORT = 0xffff
    private const val ZIP64_INT = 0xffffffffL
}
