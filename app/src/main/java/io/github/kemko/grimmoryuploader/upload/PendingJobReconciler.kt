package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.upload.db.UploadJobState

class PendingJobReconciler(
    private val queue: UploadQueueRepository,
    private val staging: StagingStore,
    private val ensureScheduled: (Long) -> Unit = {},
) {
    suspend fun reconcile() {
        val pending = queue.pending()
        pending.filter { it.state == UploadJobState.RUNNING }.forEach {
            queue.transition(it.id, UploadJobState.QUEUED, "Transfer was interrupted")
        }
        staging.reconcile(pending.mapNotNull { it.stagedPath }.toSet())
        queue.pending().filter { it.state == UploadJobState.QUEUED }.forEach {
            ensureScheduled(it.id)
        }
    }
}
