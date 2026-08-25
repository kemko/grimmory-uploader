package io.github.kemko.grimmoryuploader.upload

import android.app.job.JobService
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferJobServiceTest {
    @Test
    fun isAJobServiceForSystemOwnedTransferExecution() {
        assertTrue(JobService::class.java.isAssignableFrom(TransferJobService::class.java))
    }
}
