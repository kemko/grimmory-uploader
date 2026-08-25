package io.github.kemko.grimmoryuploader.upload.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadJobDao {
    @Insert
    suspend fun insert(job: UploadJobEntity): Long

    @Query("SELECT * FROM upload_jobs WHERE id = :id")
    suspend fun find(id: Long): UploadJobEntity?

    @Query("SELECT * FROM upload_jobs WHERE serverUrl = :serverUrl")
    suspend fun byServer(serverUrl: String): List<UploadJobEntity>

    @Query("DELETE FROM upload_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM upload_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UploadJobEntity>>

    @Query("SELECT * FROM upload_jobs WHERE state IN ('STAGED', 'AWAITING_AUTH', 'AWAITING_CLEARTEXT', 'QUEUED', 'RUNNING') ORDER BY createdAt")
    suspend fun pending(): List<UploadJobEntity>

    @Query("SELECT * FROM upload_jobs WHERE state IN ('STAGED', 'AWAITING_AUTH', 'AWAITING_CLEARTEXT', 'QUEUED', 'RUNNING') ORDER BY createdAt LIMIT 1")
    suspend fun pendingIntake(): UploadJobEntity?

    @Query("UPDATE upload_jobs SET state = :toState, failureReason = :reason, updatedAt = :updatedAt WHERE id = :id AND state IN (:fromStates)")
    suspend fun transition(
        id: Long,
        fromStates: List<UploadJobState>,
        toState: UploadJobState,
        reason: String?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE upload_jobs SET serverUrl = :serverUrl, libraryId = :libraryId, pathId = :pathId, recompressEpub = :recompressEpub, serverCleartextConfirmed = :serverCleartextConfirmed, updatedAt = :updatedAt WHERE id = :id AND serverUrl = '' AND state = 'STAGED'")
    suspend fun configure(
        id: Long,
        serverUrl: String,
        libraryId: Long,
        pathId: Long,
        recompressEpub: Boolean,
        serverCleartextConfirmed: Boolean,
        updatedAt: Long,
    ): Int

    @Query("UPDATE upload_jobs SET stagedPath = :path, displayName = :displayName, updatedAt = :updatedAt WHERE id = :id AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    suspend fun attachStagedPath(id: Long, path: String, displayName: String, updatedAt: Long): Int

    @Query("UPDATE upload_jobs SET stagedPath = NULL WHERE id = :id")
    suspend fun clearStagedPath(id: Long)

    @Query("UPDATE upload_jobs SET progressStage = :stage, progressCurrent = :current, progressTotal = :total, updatedAt = :updatedAt WHERE id = :id AND state IN ('QUEUED', 'RUNNING')")
    suspend fun updateProgress(id: Long, stage: String, current: Long, total: Long, updatedAt: Long): Int

    @Query("UPDATE upload_jobs SET sourceCleartextConfirmed = 1, state = 'QUEUED', failureReason = NULL, updatedAt = :updatedAt WHERE id = :id AND state = 'AWAITING_CLEARTEXT'")
    suspend fun confirmSourceCleartext(id: Long, updatedAt: Long): Int

    @Query("UPDATE upload_jobs SET serverCleartextConfirmed = 1, state = 'QUEUED', failureReason = NULL, updatedAt = :updatedAt WHERE id = :id AND state = 'AWAITING_CLEARTEXT'")
    suspend fun confirmServerCleartext(id: Long, updatedAt: Long): Int

    @Query("UPDATE upload_jobs SET state = 'QUEUED', failureReason = NULL, updatedAt = :updatedAt WHERE id = :id AND state = 'FAILED' AND sourceUrl IS NOT NULL")
    suspend fun retry(id: Long, updatedAt: Long): Int
}
