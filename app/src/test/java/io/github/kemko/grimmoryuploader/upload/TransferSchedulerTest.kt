package io.github.kemko.grimmoryuploader.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferSchedulerTest {
    @Test
    fun buildsAndSchedulesRequiredUserInitiatedJob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("scheduler").toFile()
        val scheduler = TransferScheduler(
            context,
            UploadQueueRepository(FakeUploadJobDao(), StagingStore(root)),
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
        val system = context.getSystemService(JobScheduler::class.java)
        assertEquals(scheduledId, system.getPendingJob(scheduledId)?.id)
        scheduler.cancel(42)
        assertEquals(null, system.getPendingJob(scheduledId))
        assertNotEquals(TransferScheduler.stableJobId(42), TransferScheduler.stableJobId(43))
        root.deleteRecursively()
    }
}
