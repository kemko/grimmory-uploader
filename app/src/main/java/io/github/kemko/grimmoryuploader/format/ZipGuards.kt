package io.github.kemko.grimmoryuploader.format

import java.util.zip.ZipEntry

object ZipGuards {
    const val MAX_ENTRIES = 2_000
    const val MAX_TOTAL_UNCOMPRESSED = 512L * 1024 * 1024
    const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 100L
    const val MAX_NAME_LENGTH = 240
    const val MAX_PATH_DEPTH = 16

    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "Unsafe ZIP entry name" }
        require(!name.startsWith('/') && !name.startsWith('\\')) { "Absolute ZIP entry path" }
        val parts = name.replace('\\', '/').split('/')
        require(parts.size <= MAX_PATH_DEPTH && parts.dropLast(1).none { it == ".." || it.isEmpty() }) {
            "Unsafe ZIP entry path"
        }
    }

    fun validateEntry(entry: ZipEntry, compressedSize: Long, total: Long) {
        validateName(entry.name)
        require(entry.size <= MAX_ENTRY_SIZE || entry.size < 0) { "ZIP entry is too large" }
        if (entry.size >= 0 && compressedSize > 0) {
            require(entry.size / compressedSize <= MAX_COMPRESSION_RATIO) { "ZIP compression ratio is unsafe" }
        }
        require(total <= MAX_TOTAL_UNCOMPRESSED) { "ZIP archive is too large" }
    }
}
