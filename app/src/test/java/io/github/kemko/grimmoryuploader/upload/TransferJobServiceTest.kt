package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferJobServiceTest {
    @Test
    fun runnerAppliesEveryPipelineResultAndProgress() = runBlocking {
        val cases = listOf(
            PipelineResult.Success to UploadJobState.SUCCEEDED,
            PipelineResult.AwaitingAuth("login") to UploadJobState.AWAITING_AUTH,
            PipelineResult.AwaitingCleartextConfirmation("http://books.test") to UploadJobState.AWAITING_CLEARTEXT,
            PipelineResult.Retry("offline") to UploadJobState.QUEUED,
            PipelineResult.Failed("invalid") to UploadJobState.FAILED,
        )
        cases.forEachIndexed { index, (result, expectedState) ->
            val root = Files.createTempDirectory("runner-$index").toFile()
            val dao = FakeUploadJobDao()
            val queue = UploadQueueRepository(dao, StagingStore(root))
            val job = queue.enqueue(
                IncomingInput.Url("https://books.test/book.fb2", "book.fb2"),
                UploadSettingsSnapshot("https://grimmory.test"),
            )
            queue.transition(job.id, UploadJobState.QUEUED)
            val events = RecordingEvents()
            val pipeline = object : TransferPipeline {
                override suspend fun execute(
                    job: UploadJobEntity,
                    cancelled: () -> Boolean,
                    onProgress: (TransferProgress) -> Unit,
                ): PipelineResult {
                    assertFalse(cancelled())
                    onProgress(TransferProgress(TransferStage.UPLOAD, 1, 2))
                    return result
                }
            }

            val reschedule = TransferRunner(queue, pipeline, events).run(
                requireNotNull(dao.find(job.id)),
            ) { false }

            assertEquals(expectedState, dao.find(job.id)?.state)
            assertEquals(result is PipelineResult.Retry, reschedule)
            assertEquals(TransferStage.UPLOAD.name, dao.find(job.id)?.progressStage)
            assertTrue(events.names.isNotEmpty())
            root.deleteRecursively()
        }
    }

    @Test
    fun runnerCoalescesFrequentProgressAndFlushesTheFinalValue() = runBlocking {
        val root = Files.createTempDirectory("runner-progress").toFile()
        val dao = FakeUploadJobDao()
        val queue = UploadQueueRepository(dao, StagingStore(root))
        val job = queue.enqueue(
            IncomingInput.Url("https://books.test/book.fb2", "book.fb2"),
            UploadSettingsSnapshot("https://grimmory.test"),
        )
        queue.transition(job.id, UploadJobState.QUEUED)
        val events = RecordingEvents()
        val updates = 300
        val chunkSize = 8L * 1024
        val pipeline = object : TransferPipeline {
            override suspend fun execute(
                job: UploadJobEntity,
                cancelled: () -> Boolean,
                onProgress: (TransferProgress) -> Unit,
            ): PipelineResult {
                repeat(updates) { index ->
                    onProgress(TransferProgress(TransferStage.UPLOAD, (index + 1) * chunkSize, -1))
                }
                return PipelineResult.Success
            }
        }

        TransferRunner(queue, pipeline, events).run(requireNotNull(dao.find(job.id))) { false }

        assertTrue(dao.progressUpdateCount < updates / 10)
        assertEquals(updates * chunkSize, dao.find(job.id)?.progressCurrent)
        assertEquals(dao.progressUpdateCount, events.names.count { it == "progress" })
        root.deleteRecursively()
        Unit
    }

    private class RecordingEvents : TransferEvents {
        val names = mutableListOf<String>()
        override fun progress(jobId: Long, name: String, progress: TransferProgress) { names += "progress" }
        override fun success(jobId: Long, name: String) { names += "success" }
        override fun authRequired(jobId: Long, name: String) { names += "auth" }
        override fun cleartextRequired(jobId: Long, name: String) { names += "cleartext" }
        override fun failure(jobId: Long, name: String, reason: String) { names += "failure" }
    }
}
