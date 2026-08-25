package io.github.kemko.grimmoryuploader.upload.db

enum class UploadJobState { STAGED, AWAITING_AUTH, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class UploadJobEntity(
    val id: Long = 0,
    val sourceUri: String? = null,
    val sourceUrl: String? = null,
    val stagedPath: String? = null,
    val displayName: String,
    val mimeType: String? = null,
    val state: UploadJobState = UploadJobState.STAGED,
    val serverUrl: String,
    val libraryId: Long,
    val pathId: Long,
    val recompressEpub: Boolean,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
