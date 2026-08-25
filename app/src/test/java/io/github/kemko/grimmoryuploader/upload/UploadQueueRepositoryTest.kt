package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadQueueRepositoryTest {
    @Test
    fun snapshotsSettingsAndCleansTerminalJob() {
        runBlocking {
        val dao = FakeUploadJobDao()
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
        repository.transition(job.id, UploadJobState.QUEUED)
        repository.transition(job.id, UploadJobState.RUNNING)
        repository.transition(job.id, UploadJobState.SUCCEEDED)
        assertEquals(UploadJobState.SUCCEEDED, dao.find(job.id)!!.state)
        root.deleteRecursively()
        }
    }

    @Test
    fun changingServerDeletesActiveAndFailedJobsWithTheirStagingFiles() {
        runBlocking {
            val root = Files.createTempDirectory("queue-server").toFile()
            val staged = java.io.File(root, "book.fb2").apply { writeText("book") }
            val dao = FakeUploadJobDao()
            val repository = UploadQueueRepository(dao, StagingStore(root))
            val job = repository.enqueue(
                IncomingInput.Url("https://example.test/book.fb2", "book.fb2"),
                UploadSettingsSnapshot("https://example.test"),
            )
            dao.replace(dao.find(job.id)!!.copy(stagedPath = staged.absolutePath))
            val failed = repository.enqueue(
                IncomingInput.Url("https://example.test/failed.fb2", "failed.fb2"),
                UploadSettingsSnapshot("https://example.test"),
            )
            repository.transition(failed.id, UploadJobState.QUEUED)
            repository.transition(failed.id, UploadJobState.RUNNING)
            repository.transition(failed.id, UploadJobState.FAILED, "invalid")

            repository.cancelForServer("https://example.test/")

            assertFalse(staged.exists())
            assertEquals(null, dao.find(job.id))
            assertEquals(null, dao.find(failed.id))
            root.deleteRecursively()
        }
    }

    @Test
    fun retriesFailedJobAndRejectsRetryForOtherStates() {
        runBlocking {
            val root = Files.createTempDirectory("queue-retry").toFile()
            val dao = FakeUploadJobDao()
            val repository = UploadQueueRepository(dao, StagingStore(root))
            val job = repository.enqueue(
                IncomingInput.Url("https://example.test/book.fb2", "book.fb2"),
                UploadSettingsSnapshot("https://example.test"),
            )
            repository.transition(job.id, UploadJobState.QUEUED)
            repository.transition(job.id, UploadJobState.RUNNING)
            repository.transition(job.id, UploadJobState.FAILED, "temporary")
            repository.retry(job.id)
            assertEquals(UploadJobState.QUEUED, dao.find(job.id)!!.state)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.retry(job.id) }
            }
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalFailureClearsLocalStagingAndCannotBeRetried() = runBlocking {
        val root = Files.createTempDirectory("queue-local-failure").toFile()
        val staged = java.io.File(root, "local.fb2").apply { writeText("book") }
        val dao = FakeUploadJobDao()
        val repository = UploadQueueRepository(dao, StagingStore(root))
        val job = repository.enqueue(
            IncomingInput.Url("https://placeholder.test/local.fb2", "local.fb2"),
            UploadSettingsSnapshot("https://example.test"),
        )
        dao.replace(
            requireNotNull(dao.find(job.id)).copy(
                sourceUrl = null,
                sourceUri = "content://books/local",
                stagedPath = staged.absolutePath,
            ),
        )
        repository.transition(job.id, UploadJobState.QUEUED)
        repository.transition(job.id, UploadJobState.RUNNING)

        repository.transition(job.id, UploadJobState.FAILED, "invalid")

        assertFalse(staged.exists())
        assertEquals(null, dao.find(job.id)?.stagedPath)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.retry(job.id) }
        }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun terminalStateCannotBeOverwrittenByLateCompletion() = runBlocking {
        val root = Files.createTempDirectory("queue-race").toFile()
        val dao = FakeUploadJobDao()
        val repository = UploadQueueRepository(dao, StagingStore(root))
        val job = repository.enqueue(
            IncomingInput.Url("https://example.test/book.fb2", "book.fb2"),
            UploadSettingsSnapshot("https://example.test"),
        )
        repository.transition(job.id, UploadJobState.QUEUED)
        repository.transition(job.id, UploadJobState.RUNNING)
        assertTrue(repository.transition(job.id, UploadJobState.CANCELLED))
        assertFalse(repository.transition(job.id, UploadJobState.SUCCEEDED))
        assertEquals(UploadJobState.CANCELLED, dao.find(job.id)?.state)
        assertEquals(UploadJobState.CANCELLED, repository.observeAll().first().single().state)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun terminalTransitionCleansPathAttachedAfterInitialRead() = runBlocking {
        val root = Files.createTempDirectory("queue-attach-race").toFile()
        val staged = java.io.File(root, "download.fb2").apply { writeText("book") }
        val dao = FakeUploadJobDao()
        val repository = UploadQueueRepository(dao, StagingStore(root))
        val job = repository.enqueue(
            IncomingInput.Url("https://example.test/book.fb2", "book.fb2"),
            UploadSettingsSnapshot("https://example.test"),
        )
        dao.attachBeforeNextTransition(staged.absolutePath)

        assertTrue(repository.transition(job.id, UploadJobState.CANCELLED))
        assertFalse(staged.exists())
        root.deleteRecursively()
        Unit
    }
}
