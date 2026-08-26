package io.github.kemko.grimmoryuploader.upload.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class UploadJobState {
    STAGED,
    AWAITING_AUTH,
    AWAITING_CLEARTEXT,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Entity(tableName = "upload_jobs")
data class UploadJobEntity(
    @PrimaryKey(autoGenerate = true)
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
    val serverCleartextConfirmed: Boolean = false,
    val sourceCleartextConfirmed: Boolean = false,
    val failureReason: String? = null,
    val progressStage: String? = null,
    val progressCurrent: Long = 0,
    val progressTotal: Long = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
