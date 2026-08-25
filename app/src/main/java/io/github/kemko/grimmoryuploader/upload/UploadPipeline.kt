package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
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
    private val cleartextConfirmed: suspend (String) -> Boolean = { !ServerUrl.parse(it).isCleartext },
    private val detector: BookFormatDetector = BookFormatDetector(),
    private val transformer: BookTransformer = BookTransformer(),
) {
    suspend fun execute(
        job: UploadJobEntity,
        cancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {},
    ): PipelineResult = withContext(Dispatchers.IO) {
        try {
            ensureNotCancelled(cancelled)
            val source = if (job.stagedPath != null && staging.resolve(job.stagedPath).isFile) {
                staging.resolve(job.stagedPath)
            } else if (job.sourceUrl != null) {
                download(job, cancelled, onProgress)
            } else {
                return@withContext PipelineResult.Failed("Staged source is missing")
            }

            onProgress(TransferProgress(TransferStage.VALIDATION, 0, source.length()))
            val format = try {
                detector.detect(source, job.mimeType)
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
            if (!cleartextConfirmed(job.serverUrl)) {
                return@withContext PipelineResult.AwaitingCleartextConfirmation(job.serverUrl)
            }
            onProgress(TransferProgress(TransferStage.UPLOAD, 0, uploadLength(source, format, job.recompressEpub)))
            val body = ProgressRequestBody(
                transformedBody(source, format, job.recompressEpub),
            ) { written, total -> onProgress(TransferProgress(TransferStage.UPLOAD, written, total)) }
            apiFor(job.serverUrl).upload(
                libraryId = job.libraryId.toInt(),
                pathId = job.pathId.toInt(),
                fileName = outputFileName(job.displayName, format),
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
    ): File {
        var current = requireHttpUrl(requireNotNull(job.sourceUrl))
        var redirects = 0
        val target = staging.newFile(job.displayName)
        try {
            while (true) {
                ensureNotCancelled(cancelled)
                if (current.scheme == "http" && !cleartextConfirmed(current.toString())) {
                    throw ConfirmationRequired(current.toString())
                }
                val response = downloadClient.newCall(Request.Builder().url(current).get().build()).execute()
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
                    onProgress(TransferProgress(TransferStage.DOWNLOAD, 0, total))
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            while (true) {
                                ensureNotCancelled(cancelled)
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                onProgress(TransferProgress(TransferStage.DOWNLOAD, copied, total))
                            }
                        }
                    }
                }
                if (followedRedirect) continue
                queue.attachStagedPath(job.id, target.absolutePath)
                return target
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
        } catch (error: IOException) {
            target.delete()
            throw DownloadRetry(error.message ?: "Network transfer failed")
        }
    }

    private fun transformedBody(source: File, format: BookFormat, recompress: Boolean): RequestBody =
        object : RequestBody() {
            override fun contentType() = contentType(format).toMediaType()

            override fun contentLength(): Long = uploadLength(source, format, recompress)

            override fun writeTo(sink: BufferedSink) {
                transformer.transform(source, format, sink.outputStream().nonClosing(), recompress)
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

    private class ConfirmationRequired(val url: String) : IOException()
    private class DownloadFinal(message: String) : IOException(message)
    private class DownloadRetry(message: String) : IOException(message)

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

private fun OutputStream.nonClosing(): OutputStream = object : FilterOutputStream(this) {
    override fun close() = flush()
}
