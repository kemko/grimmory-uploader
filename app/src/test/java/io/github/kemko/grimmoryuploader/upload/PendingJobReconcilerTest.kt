package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class PendingJobReconcilerTest {
    @Test
    fun queuesInterruptedJobsAndRemovesOrphans() =
        runBlocking {
            val root = Files.createTempDirectory("reconcile").toFile()
            val active = java.io.File(root, "active").apply { writeText("book") }
            val orphan = java.io.File(root, "orphan").apply { writeText("old") }
            val dao = FakeUploadJobDao()
            val queue = UploadQueueRepository(dao, StagingStore(root))
            val job =
                queue.enqueue(
                    IncomingInput.Url("https://example.test/book.fb2", "book.fb2"),
                    UploadSettingsSnapshot("https://example.test"),
                )
            val queued =
                queue.enqueue(
                    IncomingInput.Url("https://example.test/queued.fb2", "queued.fb2"),
                    UploadSettingsSnapshot("https://example.test"),
                )
            queue.transition(queued.id, UploadJobState.QUEUED)
            dao.replace(dao.find(job.id)!!.copy(state = UploadJobState.RUNNING, stagedPath = active.absolutePath))
            PendingJobReconciler(queue, StagingStore(root)).reconcile()

            assertEquals(UploadJobState.QUEUED, dao.find(job.id)!!.state)
            assertEquals(UploadJobState.QUEUED, dao.find(queued.id)!!.state)
            assertFalse(orphan.exists())
            assertTrue(active.exists())
            root.deleteRecursively()
            Unit
        }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
