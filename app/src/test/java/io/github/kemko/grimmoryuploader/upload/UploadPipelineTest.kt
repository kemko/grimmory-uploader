package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobDao
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
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
        server.enqueue(MockResponse().setBody(fb2()))
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val root = Files.createTempDirectory("pipeline").toFile()
        try {
            val dao = FakeDao()
            val staging = StagingStore(root)
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/start").toString(), "book.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString()),
            )
            val progress = mutableListOf<TransferProgress>()
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient.Builder().followRedirects(false).build(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
                cleartextConfirmed = { true },
            )

            assertEquals(PipelineResult.Success, pipeline.execute(job, onProgress = progress::add))
            assertTrue(progress.any { it.stage == TransferStage.DOWNLOAD && it.current > 0 })
            assertTrue(progress.any { it.stage == TransferStage.UPLOAD && it.current > 0 })
            assertEquals(null, server.takeRequest().getHeader("Authorization"))
            assertEquals("/book.fb2", server.takeRequest().path)
            val upload = server.takeRequest()
            assertEquals("/grimmory/api/v1/files/upload?libraryId=1&pathId=1", upload.path)
            assertTrue(upload.body.readUtf8().contains("FictionBook"))
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
            val dao = FakeDao()
            val staging = StagingStore(root)
            val queue = UploadQueueRepository(dao, staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/book.fb2").toString(), "book.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString()),
            )
            val cancelled = AtomicBoolean(false)
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
                cleartextConfirmed = { true },
            )
            org.junit.Assert.assertThrows(CancellationException::class.java) {
                runBlocking {
                    pipeline.execute(
                        job,
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
            val dao = FakeDao()
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
                cleartextConfirmed = { false },
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
            val queue = UploadQueueRepository(FakeDao(), staging)
            val job = queue.enqueue(
                IncomingInput.Url(server.url("/book.epub").toString(), "book.epub"),
                UploadSettingsSnapshot(server.url("/grimmory").toString()),
            )
            val pipeline = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
                cleartextConfirmed = { true },
            )

            assertEquals(PipelineResult.Success, pipeline.execute(job))
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
            val queue = UploadQueueRepository(FakeDao(), staging)
            val authJob = queue.enqueue(
                IncomingInput.Url(server.url("/auth.fb2").toString(), "auth.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString()),
            )
            val retryJob = queue.enqueue(
                IncomingInput.Url(server.url("/retry.fb2").toString(), "retry.fb2"),
                UploadSettingsSnapshot(server.url("/grimmory").toString()),
            )
            fun pipeline() = UploadPipeline(
                queue,
                staging,
                OkHttpClient(),
                { snapshot -> GrimmoryApi(OkHttpClient(), serverUrl = { ServerUrl.parse(snapshot) }) },
                cleartextConfirmed = { true },
            )
            assertTrue(pipeline().execute(authJob) is PipelineResult.AwaitingAuth)
            assertTrue(pipeline().execute(retryJob) is PipelineResult.Retry)
        } finally {
            server.shutdown()
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

    private class FakeDao : UploadJobDao {
        private var nextId = 1L
        private val jobs = linkedMapOf<Long, UploadJobEntity>()
        override suspend fun insert(job: UploadJobEntity): Long = nextId.also { jobs[it] = job.copy(id = it); nextId++ }
        override suspend fun update(job: UploadJobEntity) { jobs[job.id] = job }
        override suspend fun find(id: Long): UploadJobEntity? = jobs[id]
        override fun observe(states: List<UploadJobState>) = kotlinx.coroutines.flow.flowOf(jobs.values.filter { it.state in states })
        override suspend fun pending() = jobs.values.filter { it.state in setOf(UploadJobState.STAGED, UploadJobState.AWAITING_AUTH, UploadJobState.QUEUED, UploadJobState.RUNNING) }
    }
}
