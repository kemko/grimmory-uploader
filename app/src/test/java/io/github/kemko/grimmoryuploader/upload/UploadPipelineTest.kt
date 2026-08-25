package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPipelineTest {
    @Test
    fun downloadsRedirectsWithoutAuthAndStreamsUpload() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/book.fb2"))
        server.enqueue(
            MockResponse().setHeader("Content-Disposition", "attachment; filename*=UTF-8''server-book.fb2")
                .setBody(fb2()),
        )
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val root = Files.createTempDirectory("pipeline").toFile()
        try {
            val dao = FakeUploadJobDao()
            val staging = StagingStore(root)
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/start").toString(), "book.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString(), serverCleartextConfirmed = true),
            )
            dao.replace(job.copy(sourceCleartextConfirmed = true))
            val approvedJob = requireNotNull(dao.find(job.id))
            val progress = mutableListOf<TransferProgress>()
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient.Builder().followRedirects(false).build(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
            )

            assertEquals(PipelineResult.Success, pipeline.execute(approvedJob, onProgress = progress::add))
            assertTrue(progress.any { it.stage == TransferStage.DOWNLOAD && it.current > 0 })
            assertTrue(progress.any { it.stage == TransferStage.UPLOAD && it.current > 0 })
            assertEquals(null, server.takeRequest().getHeader("Authorization"))
            assertEquals("/book.fb2", server.takeRequest().path)
            val upload = server.takeRequest()
            assertEquals("/grimmory/api/v1/files/upload?libraryId=1&pathId=1", upload.path)
            val multipart = upload.body.readUtf8()
            assertTrue(multipart.contains("FictionBook"))
            assertTrue(multipart.contains("server-book.fb2"))
            assertEquals(UploadJobState.STAGED, dao.find(job.id)!!.state)
            assertTrue(dao.find(job.id)!!.stagedPath!!.let { java.io.File(it).isFile })
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationRemovesDownloadedStaging() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(fb2()))
        server.start()
        val root = Files.createTempDirectory("pipeline-cancel").toFile()
        try {
            val dao = FakeUploadJobDao()
            val staging = StagingStore(root)
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/book.fb2").toString(), "book.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString(), serverCleartextConfirmed = true),
            )
            dao.replace(job.copy(sourceCleartextConfirmed = true))
            val approvedJob = requireNotNull(dao.find(job.id))
            val cancelled = AtomicBoolean(false)
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
            )
            org.junit.Assert.assertThrows(CancellationException::class.java) {
                runBlocking {
                    pipeline.execute(
                        approvedJob,
                        cancelled = cancelled::get,
                        onProgress = { progress -> if (progress.stage == TransferStage.DOWNLOAD && progress.current > 0) cancelled.set(true) },
                    )
                }
            }
            assertFalse(root.listFiles().orEmpty().any { it.isFile })
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun cleartextDownloadWaitsForConfirmation() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(fb2()))
        server.start()
        val root = Files.createTempDirectory("pipeline-http").toFile()
        try {
            val dao = FakeUploadJobDao()
            val staging = StagingStore(root)
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/book.fb2").toString(), "book.fb2"),
                UploadSettingsSnapshot("https://example.test"),
            )
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
            )
            assertTrue(pipeline.execute(job) is PipelineResult.AwaitingCleartextConfirmation)
            assertEquals(0, server.requestCount)
            assertFalse(root.listFiles().orEmpty().any { it.isFile })
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun recompressedEpubUsesChunkedMultipart() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(epub())))
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val root = Files.createTempDirectory("pipeline-epub").toFile()
        try {
            val staging = StagingStore(root)
            val dao = FakeUploadJobDao()
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/book.epub").toString(), "book.epub"),
                UploadSettingsSnapshot(server.url("/grimmory").toString(), serverCleartextConfirmed = true),
            )
            dao.replace(job.copy(sourceCleartextConfirmed = true))
            val approvedJob = requireNotNull(dao.find(job.id))
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
            )

            assertEquals(PipelineResult.Success, pipeline.execute(approvedJob))
            server.takeRequest()
            val upload = server.takeRequest()
            assertEquals("chunked", upload.headers["Transfer-Encoding"])
            assertTrue(upload.body.readUtf8().contains("application/epub+zip"))
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun pausesForAuthAndRetriesTransientServerError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(fb2()))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(fb2()))
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        val root = Files.createTempDirectory("pipeline-errors").toFile()
        try {
            val staging = StagingStore(root)
            val dao = FakeUploadJobDao()
            val queue = UploadQueueRepository(dao, staging)
            val authJob = queue.enqueue(
                IncomingInput.Url(server.url("/auth.fb2").toString(), "auth.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString(), serverCleartextConfirmed = true),
            )
            val retryJob = queue.enqueue(
                IncomingInput.Url(server.url("/retry.fb2").toString(), "retry.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString(), serverCleartextConfirmed = true),
            )
            dao.replace(authJob.copy(sourceCleartextConfirmed = true))
            dao.replace(retryJob.copy(sourceCleartextConfirmed = true))
            fun pipeline() = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
            )
            assertTrue(pipeline().execute(requireNotNull(dao.find(authJob.id))) is PipelineResult.AwaitingAuth)
            assertTrue(pipeline().execute(requireNotNull(dao.find(retryJob.id))) is PipelineResult.Retry)
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidRedirectsBoundariesAndOversizedDownloads() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "ftp://example.test/book"))
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next")) }
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setChunkedBody("12345", 1))
        server.start()
        val root = Files.createTempDirectory("pipeline-boundaries").toFile()
        try {
            val dao = FakeUploadJobDao()
            val staging = StagingStore(root, maxBytes = 4)
            val queue = UploadQueueRepository(dao, staging)

            suspend fun run(path: String): PipelineResult {
                val job = queue.enqueue(
                    IncomingInput.Url(server.url(path).toString(), "book.fb2"),
                    UploadSettingsSnapshot(server.url("/grimmory").toString(), serverCleartextConfirmed = true),
                )
                dao.replace(job.copy(sourceCleartextConfirmed = true))
                return UploadPipeline(
                    queue,
                    staging,
                    OkHttpClient.Builder().followRedirects(false).build(),
                    { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
                ).execute(requireNotNull(dao.find(job.id)))
            }

            assertTrue(run("/missing-location") is PipelineResult.Failed)
            assertTrue(run("/unsafe-location") is PipelineResult.Failed)
            assertTrue(run("/too-many") is PipelineResult.Failed)
            assertTrue(run("/missing") is PipelineResult.Failed)
            assertTrue(run("/large") is PipelineResult.Failed)
            assertFalse(root.listFiles().orEmpty().any { it.isFile })
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsHttpsToHttpRedirect() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val secureServer = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val cleartextServer = MockWebServer().apply { start() }
        secureServer.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", cleartextServer.url("/book.fb2")),
        )
        val root = Files.createTempDirectory("pipeline-downgrade").toFile()
        try {
            val dao = FakeUploadJobDao()
            val staging = StagingStore(root)
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(secureServer.url("/start").toString(), "book.fb2"),
                UploadSettingsSnapshot(secureServer.url("/grimmory").toString()),
            )
            val result = UploadPipeline(
                queue,
                staging,
                OkHttpClient.Builder()
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .followRedirects(false)
                    .build(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
            ).execute(job)

            assertTrue(result is PipelineResult.Failed)
            assertEquals(0, cleartextServer.requestCount)
        } finally {
            secureServer.shutdown()
            cleartextServer.shutdown()
            root.deleteRecursively()
        }
    }

    private fun fb2() = """
        <?xml version="1.0"?><FictionBook xmlns="http://www.gribuser.ru/xml/ fictionbook/2.0"><description/><body><section><p>Book</p></section></body></FictionBook>
    """.trimIndent()

    private fun epub(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                val crc = CRC32().apply { update(mimetype) }
                this.crc = crc.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.xhtml"))
            zip.write("content".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
