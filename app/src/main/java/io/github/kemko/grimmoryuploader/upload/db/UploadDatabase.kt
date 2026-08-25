package io.github.kemko.grimmoryuploader.upload.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class UploadDatabase private constructor(private val database: RoomUploadDatabase) {
    fun jobs(): UploadJobDao = Adapter(database.jobs())

    companion object {
        fun create(context: Context): UploadDatabase = Room.databaseBuilder(
            context.applicationContext,
            RoomUploadDatabase::class.java,
            "upload.db",
        ).build()
            .let(::UploadDatabase)
    }

    private class Adapter(private val dao: RoomUploadJobDao) : UploadJobDao {
        override suspend fun insert(job: UploadJobEntity): Long = dao.insert(job.toRecord())

        override suspend fun update(job: UploadJobEntity) = dao.update(job.toRecord())

        override suspend fun find(id: Long): UploadJobEntity? = dao.find(id)?.toEntity()

        override fun observe(states: List<UploadJobState>): Flow<List<UploadJobEntity>> = flow {
            emit(dao.pending().map(RoomUploadJobEntity::toEntity).filter { it.state in states })
        }

        override suspend fun pending(): List<UploadJobEntity> = dao.pending().map(RoomUploadJobEntity::toEntity)
    }
}

private fun UploadJobEntity.toRecord() = RoomUploadJobEntity().also {
    it.id = id
    it.sourceUri = sourceUri
    it.sourceUrl = sourceUrl
    it.stagedPath = stagedPath
    it.displayName = displayName
    it.mimeType = mimeType
    it.state = state.name
    it.serverUrl = serverUrl
    it.libraryId = libraryId
    it.pathId = pathId
    it.recompressEpub = recompressEpub
    it.failureReason = failureReason
    it.createdAt = createdAt
    it.updatedAt = updatedAt
}

private fun RoomUploadJobEntity.toEntity() = UploadJobEntity(
    id = id,
    sourceUri = sourceUri,
    sourceUrl = sourceUrl,
    stagedPath = stagedPath,
    displayName = displayName,
    mimeType = mimeType,
    state = UploadJobState.valueOf(state),
    serverUrl = serverUrl,
    libraryId = libraryId,
    pathId = pathId,
    recompressEpub = recompressEpub,
    failureReason = failureReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
