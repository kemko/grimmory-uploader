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
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadQueueRepositoryTest {
    @Test
    fun snapshotsSettingsAndCleansTerminalJob() {
        runBlocking {
        val dao = FakeDao()
        val root = Files.createTempDirectory("queue").toFile()
        val repository = UploadQueueRepository(dao, StagingStore(root))
        val job = repository.enqueue(
            IncomingInput.Url("https://example.test/book.epub", "book.epub"),
            UploadSettingsSnapshot("https://example.test/grimmory/", libraryId = 7, pathId = 9, recompressEpub = false),
        )
        assertEquals("https://example.test/grimmory", job.serverUrl)
        assertEquals(7L, job.libraryId)
        assertEquals(9L, job.pathId)
        assertTrue(!job.recompressEpub)
        repository.transition(job.id, UploadJobState.SUCCEEDED)
        assertEquals(UploadJobState.SUCCEEDED, dao.find(job.id)!!.state)
        root.deleteRecursively()
        }
    }

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
