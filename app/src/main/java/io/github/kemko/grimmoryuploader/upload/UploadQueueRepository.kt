package io.github.kemko.grimmoryuploader.upload

import android.content.ContentResolver
import android.net.Uri
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobDao
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.io.File
import kotlinx.coroutines.flow.Flow

data class UploadSettingsSnapshot(
    val serverUrl: String,
    val libraryId: Long = 1,
    val pathId: Long = 1,
    val recompressEpub: Boolean = true,
)

class UploadQueueRepository(
    private val dao: UploadJobDao,
    private val staging: StagingStore,
) {
    suspend fun enqueue(
        input: IncomingInput,
        settings: UploadSettingsSnapshot,
        resolver: ContentResolver? = null,
    ): UploadJobEntity {
        val stagedPath = if (input is IncomingInput.File) {
            requireNotNull(resolver) { "ContentResolver is required for local input" }
            staging.stage(resolver, Uri.parse(input.uri), input.displayName).absolutePath
        } else null
        val job = UploadJobEntity(
            sourceUri = (input as? IncomingInput.File)?.uri,
            sourceUrl = (input as? IncomingInput.Url)?.url,
            stagedPath = stagedPath,
            displayName = input.displayName,
            mimeType = input.mimeType,
            serverUrl = ServerUrl.parse(settings.serverUrl).normalized,
            libraryId = settings.libraryId,
            pathId = settings.pathId,
            recompressEpub = settings.recompressEpub,
        )
        val id = runCatching { dao.insert(job) }.getOrElse {
            stagedPath?.let(staging::cleanup)
            throw it
        }
        return job.copy(id = id)
    }

    suspend fun transition(id: Long, state: UploadJobState, failureReason: String? = null) {
        val current = requireNotNull(dao.find(id)) { "Unknown upload job $id" }
        dao.update(current.copy(state = state, failureReason = failureReason, updatedAt = System.currentTimeMillis()))
        if (state == UploadJobState.SUCCEEDED || state == UploadJobState.FAILED || state == UploadJobState.CANCELLED) {
            staging.cleanup(current.stagedPath)
        }
    }

    suspend fun attachStagedPath(id: Long, path: String): UploadJobEntity {
        val current = requireNotNull(dao.find(id)) { "Unknown upload job $id" }
        val staged = File(path).canonicalFile
        val root = staging.root
        require(staged.parentFile == root) { "Staging path escapes pending directory" }
        val updated = current.copy(stagedPath = staged.absolutePath, updatedAt = System.currentTimeMillis())
        dao.update(updated)
        return updated
    }


    suspend fun pending() = dao.pending()
    fun observe(states: List<UploadJobState>): Flow<List<UploadJobEntity>> = dao.observe(states)
}
