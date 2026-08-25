package io.github.kemko.grimmoryuploader.upload.db

import kotlinx.coroutines.flow.Flow

interface UploadJobDao {
    suspend fun insert(job: UploadJobEntity): Long

    suspend fun update(job: UploadJobEntity)

    suspend fun find(id: Long): UploadJobEntity?

    fun observe(states: List<UploadJobState>): Flow<List<UploadJobEntity>>

    suspend fun pending(): List<UploadJobEntity>
}
