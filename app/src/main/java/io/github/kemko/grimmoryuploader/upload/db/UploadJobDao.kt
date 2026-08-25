package io.github.kemko.grimmoryuploader.upload.db

import kotlinx.coroutines.flow.Flow

interface UploadJobDao {
    suspend fun insert(job: UploadJobEntity): Long

    suspend fun update(job: UploadJobEntity)

    suspend fun find(id: Long): UploadJobEntity?

    suspend fun byServer(serverUrl: String): List<UploadJobEntity> = emptyList()

    suspend fun delete(id: Long) = Unit

    fun observe(states: List<UploadJobState>): Flow<List<UploadJobEntity>>

    suspend fun pending(): List<UploadJobEntity>
}
