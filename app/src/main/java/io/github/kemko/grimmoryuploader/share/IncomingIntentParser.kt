package io.github.kemko.grimmoryuploader.share

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

private const val CONTENT_DISPOSITION = "Content-Disposition"

sealed interface IncomingInput {
    val displayName: String
    val mimeType: String?

    data class File(
        val uri: String,
        override val displayName: String,
        override val mimeType: String?,
    ) : IncomingInput

    data class Url(
        val url: String,
        override val displayName: String,
        override val mimeType: String? = "text/plain",
    ) : IncomingInput
}

data class IncomingIntentData(
    val action: String?,
    val dataUri: String? = null,
    val streamUri: String? = null,
    val text: String? = null,
    val mimeType: String? = null,
    val displayName: String? = null,
    val contentDisposition: String? = null,
    val title: String? = null,
)

class IncomingIntentParser(private val resolver: ContentResolver? = null) {
    fun parse(intent: Intent): IncomingInput {
        val data = intent.data
        val stream = intent.getParcelableExtraCompat(Intent.EXTRA_STREAM)
        val sourceUri = data ?: stream
        return parse(IncomingIntentData(
            action = intent.action,
            dataUri = data?.toString(),
            streamUri = stream?.toString(),
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            mimeType = intent.type,
            displayName = sourceUri
                ?.takeIf { it.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) }
                ?.let { uri -> resolver?.let { runCatching { contentName(it, uri) }.getOrNull() } },
            contentDisposition = intent.getStringExtra(CONTENT_DISPOSITION),
            title = intent.getStringExtra(Intent.EXTRA_TITLE),
        ))
    }

    fun parse(input: IncomingIntentData): IncomingInput {
        require(input.action == Intent.ACTION_SEND || input.action == Intent.ACTION_VIEW) { "Unsupported intent action" }
        require(input.action != Intent.ACTION_SEND_MULTIPLE) { "Multiple files are not supported" }
        require(input.dataUri == null || input.streamUri == null) { "Only one file is supported" }
        val uri = input.dataUri ?: input.streamUri
        if (input.action == Intent.ACTION_VIEW) {
            require(input.text.isNullOrBlank()) { "ACTION_VIEW accepts only one file" }
            require(uri != null && isLocalUri(uri)) { "Only local files are accepted for ACTION_VIEW" }
            return fileInput(uri, input)
        }
        if (uri != null) {
            require(input.text.isNullOrBlank()) { "Only one shared input is supported" }
            if (input.streamUri == null && isHttpUrl(uri)) return IncomingInput.Url(uri, urlFilename(uri))
            require(isLocalUri(uri)) { "ACTION_SEND accepts only local files" }
            return fileInput(uri, input)
        }
        val text = input.text?.trim()
        require(text != null && isHttpUrl(text)) { "Expected one HTTP(S) link" }
        return IncomingInput.Url(text, urlFilename(text))
    }

    private fun fileInput(uri: String, input: IncomingIntentData): IncomingInput.File {
        val name = filenameFromContentDisposition(input.contentDisposition)
            ?: input.title
            ?: input.displayName
            ?: runCatching { URLDecoder.decode(uri.substringAfterLast('/'), Charsets.UTF_8) }.getOrDefault("book")
        return IncomingInput.File(uri, sanitizeDisplayName(name), input.mimeType ?: resolver?.getType(Uri.parse(uri)))
    }

    private fun contentName(resolver: ContentResolver, uri: Uri): String? {
        val cursor: Cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    companion object {
        private val unsafeName = Regex("[^A-Za-z0-9._() иА-Яа-яЁё-]")

        fun sanitizeDisplayName(value: String): String {
            val cleaned = value
                .replace(Regex("[\\r\\n\\u0000]"), " ")
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(unsafeName, "_")
                .trim('.', ' ')
            return cleaned.take(240).ifBlank { "book" }
        }

        fun isHttpUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            (uri.scheme?.lowercase(Locale.ROOT) == "http" || uri.scheme?.lowercase(Locale.ROOT) == "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)

        fun urlFilename(value: String): String {
            val path = runCatching { URI(value).path }.getOrNull().orEmpty()
            val candidate = path.substringAfterLast('/').ifBlank { "download" }
            return sanitizeDisplayName(candidate)
        }

        fun filenameFromContentDisposition(value: String?): String? {
            if (value == null) return null
            val match = Regex("(?i)(?:filename\\*|filename)\\s*=\\s*(?:UTF-8''|\\\")?([^\\\";]+)").find(value) ?: return null
            return runCatching { URLDecoder.decode(match.groupValues[1], Charsets.UTF_8) }
                .getOrNull()
                ?.let(::sanitizeDisplayName)
        }

        private fun isLocalUri(uri: String): Boolean =
            runCatching { URI(uri).scheme?.lowercase(Locale.ROOT) }.getOrNull() in setOf("content", "file")
    }

}

private fun Intent.getParcelableExtraCompat(name: String): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, Uri::class.java) else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
