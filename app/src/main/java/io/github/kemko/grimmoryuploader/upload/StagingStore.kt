package io.github.kemko.grimmoryuploader.upload

import android.content.ContentResolver
import android.net.Uri
import io.github.kemko.grimmoryuploader.share.IncomingIntentParser
import java.io.File
import java.io.IOException
import java.util.UUID

class StagingStore(private val pendingDirectory: File) {
    init {
        require(pendingDirectory.isDirectory || pendingDirectory.mkdirs()) { "Cannot create staging directory" }
    }

    fun stage(resolver: ContentResolver, uri: Uri, displayName: String): File {
        val target = File(pendingDirectory, "${UUID.randomUUID()}-${IncomingIntentParser.sanitizeDisplayName(displayName)}")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("Cannot open input URI")
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun cleanup(path: String?) {
        if (path == null) return
        val target = File(path).canonicalFile
        val root = pendingDirectory.canonicalFile
        require(target.parentFile == root) { "Staging path escapes pending directory" }
        target.delete()
    }

    fun reconcile(activePaths: Set<String>) {
        pendingDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absolutePath !in activePaths) file.delete()
        }
    }
}
