package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobDao
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PendingJobReconcilerTest {
    @Test
    fun queuesInterruptedJobsAndRemovesOrphans() = runBlocking {
        val root = Files.createTempDirectory("reconcile").toFile()
        val active = java.io.File(root, "active").apply { writeText("book") }
        val orphan = java.io.File(root, "orphan").apply { writeText("old") }
        val dao = FakeDao()
        val queue = UploadQueueRepository(dao, StagingStore(root))
        val job = queue.enqueue(
            IncomingInput.Url("https://example.test/book.fb2", "book.fb2"),
            UploadSettingsSnapshot("https://example.test"),
        )
        dao.update(dao.find(job.id)!!.copy(state = UploadJobState.RUNNING, stagedPath = active.absolutePath))

        PendingJobReconciler(queue, StagingStore(root)).reconcile()

        assertEquals(UploadJobState.QUEUED, dao.find(job.id)!!.state)
        assertFalse(orphan.exists())
        assertTrue(active.exists())
        root.deleteRecursively()
        Unit
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)

    private class FakeDao : UploadJobDao {
        private var nextId = 1L
        private val jobs = linkedMapOf<Long, UploadJobEntity>()
        override suspend fun insert(job: UploadJobEntity): Long = nextId.also { jobs[it] = job.copy(id = it); nextId++ }
        override suspend fun update(job: UploadJobEntity) { jobs[job.id] = job }
        override suspend fun find(id: Long): UploadJobEntity? = jobs[id]
        override fun observe(states: List<UploadJobState>): Flow<List<UploadJobEntity>> = flowOf(jobs.values.filter { it.state in states })
        override suspend fun pending(): List<UploadJobEntity> = jobs.values.filter { it.state in setOf(UploadJobState.STAGED, UploadJobState.AWAITING_AUTH, UploadJobState.QUEUED, UploadJobState.RUNNING) }
    }
}
