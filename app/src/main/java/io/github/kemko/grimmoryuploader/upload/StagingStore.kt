package io.github.kemko.grimmoryuploader.upload

import android.content.ContentResolver
import android.net.Uri
import io.github.kemko.grimmoryuploader.share.IncomingIntentParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class StagingLimitException(
    message: String,
) : IOException(message)

class StagingStore(
    private val pendingDirectory: File,
    val maxBytes: Long = MAX_STAGING_BYTES,
) {
    val root: File get() = pendingDirectory.canonicalFile

    init {
        require(pendingDirectory.isDirectory || pendingDirectory.mkdirs()) { "Cannot create staging directory" }
    }

    fun stage(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
    ): File {
        val target = newFile(displayName)
        try {
            resolver.openInputStream(uri)?.use { input ->
                copy(input, target)
            } ?: throw IOException("Cannot open input URI")
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun newFile(displayName: String): File =
        File(pendingDirectory, "${UUID.randomUUID()}-${IncomingIntentParser.sanitizeDisplayName(displayName)}")

    fun copy(
        input: InputStream,
        target: File,
        cancelled: () -> Boolean = { false },
        onBytes: (Long) -> Unit = {},
    ) {
        var copied = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("Transfer cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > maxBytes) throw StagingLimitException("Book exceeds the ${maxBytes / (1024 * 1024)} MiB staging limit")
                output.write(buffer, 0, count)
                onBytes(copied)
            }
        }
    }

    fun resolve(path: String): File {
        val target = File(path).canonicalFile
        require(target.parentFile == root) { "Staging path escapes pending directory" }
        return target
    }

    fun cleanup(path: String?) {
        if (path == null) return
        val target = resolve(path)
        target.delete()
    }

    fun reconcile(activePaths: Set<String>) {
        pendingDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absolutePath !in activePaths) file.delete()
        }
    }

    companion object {
        const val MAX_STAGING_BYTES = 512L * 1024 * 1024
    }
}
