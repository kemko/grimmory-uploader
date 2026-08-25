package io.github.kemko.grimmoryuploader.upload

import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSchedulerTest {
    @Test
    fun createsStablePositiveJobIds() {
        assertTrue(TransferScheduler.stableJobId(42) > 0)
        assertTrue(TransferScheduler.stableJobId(42) == TransferScheduler.stableJobId(42))
    }
}
