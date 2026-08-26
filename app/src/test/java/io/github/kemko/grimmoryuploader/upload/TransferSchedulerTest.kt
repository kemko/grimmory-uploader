package io.github.kemko.grimmoryuploader.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferSchedulerTest {
    @Test
    fun buildsAndSchedulesRequiredUserInitiatedJob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("scheduler").toFile()
        val cleared = mutableListOf<Long>()
        val scheduler =
            TransferScheduler(
                context,
                UploadQueueRepository(FakeUploadJobDao(), StagingStore(root)),
                cleared::add,
            )

        val info = scheduler.jobInfo(42, estimatedUploadBytes = 100, estimatedDownloadBytes = 200)
        assertTrue(info.isUserInitiated)
        assertTrue(info.isPersisted)
        assertTrue(info.isRequireStorageNotLow)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, info.networkType)
        assertEquals(100, info.estimatedNetworkUploadBytes)
        assertEquals(200, info.estimatedNetworkDownloadBytes)
        assertEquals(42, info.extras.getLong(TransferScheduler.EXTRA_JOB_ID))
        assertEquals(30_000, info.initialBackoffMillis)

        val scheduledId = scheduler.schedule(42, 100, 200)
        assertEquals(listOf(42L), cleared)
        val system = context.getSystemService(JobScheduler::class.java)
        assertEquals(scheduledId, system.getPendingJob(scheduledId)?.id)
        scheduler.cancel(42)
        assertEquals(listOf(42L, 42L), cleared)
        assertEquals(null, system.getPendingJob(scheduledId))
        assertNotEquals(TransferScheduler.stableJobId(42), TransferScheduler.stableJobId(43))
        assertTrue(TransferScheduler.lifecycleNotificationId(42) < 0)
        assertNotEquals(TransferScheduler.stableJobId(42), TransferScheduler.lifecycleNotificationId(42))
        root.deleteRecursively()
    }

    @Test
    fun authResumeRollsBackFailuresAndContinuesWithLaterJobs() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("scheduler-auth").toFile()
            val dao = FakeUploadJobDao()
            val queue = UploadQueueRepository(dao, StagingStore(root))
            val scheduler = TransferScheduler(context, queue)
            val first =
                queue.enqueue(
                    IncomingInput.Url("https://books.test/first.fb2", "first.fb2"),
                    UploadSettingsSnapshot("https://one.example"),
                )
            val second =
                queue.enqueue(
                    IncomingInput.Url("https://books.test/second.fb2", "second.fb2"),
                    UploadSettingsSnapshot("https://one.example"),
                )
            queue.transition(first.id, UploadJobState.AWAITING_AUTH)
            queue.transition(second.id, UploadJobState.AWAITING_AUTH)
            val scheduled = mutableListOf<Long>()

            scheduler.resumeAwaitingAuth { id ->
                if (id == first.id) error("not visible")
                scheduled += id
            }

            assertEquals(UploadJobState.AWAITING_AUTH, queue.find(first.id)?.state)
            assertEquals("not visible", queue.find(first.id)?.failureReason)
            assertEquals(UploadJobState.QUEUED, queue.find(second.id)?.state)
            assertEquals(listOf(second.id), scheduled)
            root.deleteRecursively()
            Unit
        }

    @Test
    fun visibleReconciliationKeepsFailedJobsQueuedAndContinues() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = Files.createTempDirectory("scheduler-visible").toFile()
            val queue = UploadQueueRepository(FakeUploadJobDao(), StagingStore(root))
            val scheduler = TransferScheduler(context, queue)
            val first =
                queue.enqueue(
                    IncomingInput.Url("https://books.test/first.fb2", "first.fb2"),
                    UploadSettingsSnapshot("https://one.example"),
                )
            val second =
                queue.enqueue(
                    IncomingInput.Url("https://books.test/second.fb2", "second.fb2"),
                    UploadSettingsSnapshot("https://one.example"),
                )
            queue.transition(first.id, UploadJobState.QUEUED)
            queue.transition(second.id, UploadJobState.QUEUED)
            val scheduled = mutableListOf<Long>()

            scheduler.ensureQueuedScheduled { id ->
                if (id == first.id) error("not visible")
                scheduled += id
            }

            assertEquals(UploadJobState.QUEUED, queue.find(first.id)?.state)
            assertEquals(UploadJobState.QUEUED, queue.find(second.id)?.state)
            assertEquals(listOf(second.id), scheduled)
            root.deleteRecursively()
            Unit
        }
}
