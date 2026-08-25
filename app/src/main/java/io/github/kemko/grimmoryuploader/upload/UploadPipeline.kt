package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.data.network.await
import io.github.kemko.grimmoryuploader.format.BookFormat
import io.github.kemko.grimmoryuploader.format.BookFormatDetector
import io.github.kemko.grimmoryuploader.format.BookTransformer
import io.github.kemko.grimmoryuploader.format.UnsupportedBookException
import io.github.kemko.grimmoryuploader.share.IncomingIntentParser
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

sealed interface PipelineResult {
    data object Success : PipelineResult
    data class AwaitingAuth(val reason: String = "Authentication required") : PipelineResult
    data class AwaitingCleartextConfirmation(val url: String) : PipelineResult
    data class Retry(val reason: String) : PipelineResult
    data class Failed(val reason: String) : PipelineResult
}

class UploadPipeline(
    private val queue: UploadQueueRepository,
    private val staging: StagingStore,
    private val downloadClient: OkHttpClient,
    private val apiFor: (String) -> GrimmoryApi,
    private val detector: BookFormatDetector = BookFormatDetector(),
    private val transformer: BookTransformer = BookTransformer(),
) : TransferPipeline {
    override suspend fun execute(
        job: UploadJobEntity,
        cancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): PipelineResult = withContext(Dispatchers.IO) {
        try {
            ensureNotCancelled(cancelled)
            val source = if (job.stagedPath != null && staging.resolve(job.stagedPath).isFile) {
                TransferSource(staging.resolve(job.stagedPath), job.displayName)
            } else if (job.sourceUrl != null) {
                download(job, cancelled, onProgress)
            } else {
                return@withContext PipelineResult.Failed("Staged source is missing")
            }

            onProgress(TransferProgress(TransferStage.VALIDATION, 0, source.file.length()))
            val format = try {
                detector.detect(source.file, job.mimeType, cancelled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return@withContext PipelineResult.Failed(error.message ?: "Invalid book format")
            }
            ensureNotCancelled(cancelled)

            if (format == BookFormat.EPUB && job.recompressEpub) {
                onProgress(TransferProgress(TransferStage.RECOMPRESSION, 0, -1))
            } else {
                onProgress(TransferProgress(TransferStage.RECOMPRESSION, 1, 1))
            }
            if (ServerUrl.parse(job.serverUrl).isCleartext && !job.serverCleartextConfirmed) {
                return@withContext PipelineResult.AwaitingCleartextConfirmation(job.serverUrl)
            }
            onProgress(TransferProgress(TransferStage.UPLOAD, 0, uploadLength(source.file, format, job.recompressEpub)))
            val body = ProgressRequestBody(
                transformedBody(source.file, format, job.recompressEpub, cancelled),
            ) { written, total -> onProgress(TransferProgress(TransferStage.UPLOAD, written, total)) }
            apiFor(job.serverUrl).upload(
                libraryId = job.libraryId.toInt(),
                pathId = job.pathId.toInt(),
                fileName = outputFileName(source.displayName, format),
                contentType = contentType(format),
                content = body,
            )
            PipelineResult.Success
        } catch (_: CancellationException) {
            throw CancellationException("Transfer cancelled")
        } catch (error: ConfirmationRequired) {
            PipelineResult.AwaitingCleartextConfirmation(error.url)
        } catch (error: DownloadFinal) {
            PipelineResult.Failed(error.message ?: "Download failed")
        } catch (error: DownloadRetry) {
            PipelineResult.Retry(error.message ?: "Download failed")
        } catch (error: ApiException) {
            when {
                error.statusCode == 401 -> PipelineResult.AwaitingAuth()
                error.statusCode in 400..499 -> PipelineResult.Failed(error.message ?: "Server rejected upload")
                else -> PipelineResult.Retry(error.message ?: "Server request failed")
            }
        } catch (error: UnsupportedBookException) {
            PipelineResult.Failed(error.message ?: "Unsupported book format")
        } catch (error: IOException) {
            PipelineResult.Retry(error.message ?: "Network transfer failed")
        } catch (error: IllegalArgumentException) {
            PipelineResult.Failed(error.message ?: "Invalid upload")
        } catch (error: Throwable) {
            PipelineResult.Failed(error.message ?: "Upload failed")
        }
    }

    private suspend fun download(
        job: UploadJobEntity,
        cancelled: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): TransferSource {
        var current = requireHttpUrl(requireNotNull(job.sourceUrl))
        var redirects = 0
        val target = staging.newFile(job.displayName)
        try {
            while (true) {
                ensureNotCancelled(cancelled)
                if (current.scheme == "http" && !job.sourceCleartextConfirmed) {
                    throw ConfirmationRequired(current.toString())
                }
                val response = downloadClient.newCall(Request.Builder().url(current).get().build()).await()
                var followedRedirect = false
                response.use {
                    if (it.code in 300..399) {
                        val location = it.header("Location") ?: throw DownloadFinal("Redirect has no Location")
                        val next = current.resolve(location) ?: throw DownloadFinal("Invalid redirect URL")
                        if (current.scheme == "https" && next.scheme == "http") {
                            throw DownloadFinal("Redirect to cleartext HTTP is not allowed")
                        }
                        requireHttpUrl(next)
                        redirects++
                        if (redirects > MAX_REDIRECTS) throw DownloadFinal("Too many redirects")
                        current = next
                        followedRedirect = true
                        return@use
                    }
                    if (it.code in 400..499) throw DownloadFinal("Download failed with HTTP ${it.code}")
                    if (!it.isSuccessful) throw DownloadRetry("Download failed with HTTP ${it.code}")
                    val body = it.body
                    val total = body.contentLength()
                    if (total > staging.maxBytes) throw DownloadFinal("Book exceeds the ${staging.maxBytes / (1024 * 1024)} MiB staging limit")
                    onProgress(TransferProgress(TransferStage.DOWNLOAD, 0, total))
                    body.byteStream().use { input ->
                        staging.copy(input, target, cancelled) { copied ->
                            onProgress(TransferProgress(TransferStage.DOWNLOAD, copied, total))
                        }
                    }
                    val displayName = contentDispositionFileName(it.header("Content-Disposition"))
                        ?: current.pathSegments.lastOrNull()?.takeIf(String::isNotBlank)
                        ?: job.displayName
                    val safeName = IncomingIntentParser.sanitizeDisplayName(displayName)
                    queue.attachStagedPath(job.id, target.absolutePath, safeName)
                    return TransferSource(target, safeName)
                }
                if (followedRedirect) continue
            }
        } catch (error: ConfirmationRequired) {
            target.delete()
            throw error
        } catch (error: DownloadFinal) {
            target.delete()
            throw error
        } catch (error: DownloadRetry) {
            target.delete()
            throw error
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: StagingLimitException) {
            target.delete()
            throw DownloadFinal(error.message ?: "Book is too large")
        } catch (error: IOException) {
            target.delete()
            throw DownloadRetry(error.message ?: "Network transfer failed")
        }
    }

    private fun transformedBody(
        source: File,
        format: BookFormat,
        recompress: Boolean,
        cancelled: () -> Boolean,
    ): RequestBody =
        object : RequestBody() {
            override fun contentType() = contentType(format).toMediaType()

            override fun contentLength(): Long = uploadLength(source, format, recompress)

            override fun writeTo(sink: BufferedSink) {
                transformer.transform(source, format, sink.outputStream().nonClosing(), recompress, cancelled)
            }
        }

    private fun uploadLength(source: File, format: BookFormat, recompress: Boolean): Long =
        if (format == BookFormat.EPUB && recompress || format == BookFormat.FB2_ZIP) -1L else source.length()

    private fun outputFileName(displayName: String, format: BookFormat): String {
        val safe = IncomingIntentParser.sanitizeDisplayName(displayName)
        return if (format == BookFormat.FB2_ZIP && safe.lowercase().endsWith(".zip")) safe.dropLast(4) else safe
    }

    private fun contentType(format: BookFormat): String = when (format) {
        BookFormat.PDF -> "application/pdf"
        BookFormat.FB2, BookFormat.FB2_ZIP -> "application/x-fictionbook+xml"
        BookFormat.EPUB -> "application/epub+zip"
    }

    private fun requireHttpUrl(value: String): HttpUrl =
        requireHttpUrl(value.toHttpUrlOrNull() ?: throw DownloadFinal("Invalid download URL"))

    private fun requireHttpUrl(value: HttpUrl): HttpUrl {
        if (value.scheme != "http" && value.scheme != "https" || value.host.isBlank() || value.username.isNotEmpty() || value.fragment != null) {
            throw DownloadFinal("Only safe HTTP(S) URLs are supported")
        }
        return value
    }

    private fun ensureNotCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancellationException("Transfer cancelled")
    }

    private fun contentDispositionFileName(header: String?): String? {
        if (header == null) return null
        val parameters = header.split(';').drop(1).map(String::trim)
        parameters.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.let { value ->
                val parts = value.split("''", limit = 2)
                if (parts.size == 2 && parts[0].equals("UTF-8", ignoreCase = true)) {
                    return runCatching {
                        URLDecoder.decode(parts[1].replace("+", "%2B"), StandardCharsets.UTF_8)
                    }.getOrNull()
                }
            }
        return parameters.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)
    }

    private class ConfirmationRequired(val url: String) : IOException()
    private class DownloadFinal(message: String) : IOException(message)
    private class DownloadRetry(message: String) : IOException(message)

    private companion object {
        const val MAX_REDIRECTS = 5
    }

    private data class TransferSource(val file: File, val displayName: String)
}

private fun OutputStream.nonClosing(): OutputStream = object : FilterOutputStream(this) {
    override fun close() = flush()
}
