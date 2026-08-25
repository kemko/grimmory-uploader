package io.github.kemko.grimmoryuploader.upload

import android.content.ContentResolver
import android.net.Uri
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.upload.db.UploadJobDao
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class UploadSettingsSnapshot(
    val serverUrl: String,
    val libraryId: Long = 1,
    val pathId: Long = 1,
    val recompressEpub: Boolean = true,
    val serverCleartextConfirmed: Boolean = false,
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
        ServerUrl.parse(settings.serverUrl)
        return insert(input, resolver, settings)
    }

    suspend fun persist(input: IncomingInput, resolver: ContentResolver? = null): UploadJobEntity =
        insert(input, resolver, settings = null)

    private suspend fun insert(
        input: IncomingInput,
        resolver: ContentResolver?,
        settings: UploadSettingsSnapshot?,
    ): UploadJobEntity {
        val stagedPath = if (input is IncomingInput.File) {
            requireNotNull(resolver) { "ContentResolver is required for local input" }
            withContext(Dispatchers.IO) {
                staging.stage(resolver, Uri.parse(input.uri), input.displayName).absolutePath
            }
        } else null
        val job = UploadJobEntity(
            sourceUri = (input as? IncomingInput.File)?.uri,
            sourceUrl = (input as? IncomingInput.Url)?.url,
            stagedPath = stagedPath,
            displayName = input.displayName,
            mimeType = input.mimeType,
            serverUrl = settings?.let { ServerUrl.parse(it.serverUrl).normalized }.orEmpty(),
            libraryId = settings?.libraryId ?: 1,
            pathId = settings?.pathId ?: 1,
            recompressEpub = settings?.recompressEpub ?: true,
            serverCleartextConfirmed = settings?.serverCleartextConfirmed ?: false,
        )
        val id = runCatching { dao.insert(job) }.getOrElse {
            stagedPath?.let(staging::cleanup)
            throw it
        }
        return job.copy(id = id)
    }

    suspend fun configure(id: Long, settings: UploadSettingsSnapshot): UploadJobEntity {
        val server = ServerUrl.parse(settings.serverUrl).normalized
        check(
            dao.configure(
                id,
                server,
                settings.libraryId,
                settings.pathId,
                settings.recompressEpub,
                settings.serverCleartextConfirmed,
                System.currentTimeMillis(),
            ) == 1,
        ) { "Incoming upload is no longer configurable" }
        return requireNotNull(dao.find(id))
    }

    suspend fun transition(id: Long, state: UploadJobState, failureReason: String? = null): Boolean {
        val current = dao.find(id) ?: return false
        val allowed = allowedFrom(state)
        if (current.state !in allowed) return false
        val changed = dao.transition(id, allowed.toList(), state, failureReason, System.currentTimeMillis()) == 1
        if (changed && state in TERMINAL_STATES) {
            val stagedPath = dao.find(id)?.stagedPath ?: current.stagedPath
            dao.clearStagedPath(id)
            staging.cleanup(stagedPath)
        }
        return changed
    }

    suspend fun attachStagedPath(id: Long, path: String, displayName: String): UploadJobEntity {
        val staged = File(path).canonicalFile
        val root = staging.root
        require(staged.parentFile == root) { "Staging path escapes pending directory" }
        check(dao.attachStagedPath(id, staged.absolutePath, displayName, System.currentTimeMillis()) == 1) {
            "Upload job is no longer active"
        }
        return requireNotNull(dao.find(id))
    }

    suspend fun find(id: Long): UploadJobEntity? = dao.find(id)

    suspend fun jobsForServer(serverUrl: String): List<UploadJobEntity> =
        dao.byServer(ServerUrl.parse(serverUrl).normalized)

    suspend fun cancelForServer(serverUrl: String) {
        jobsForServer(serverUrl).forEach { job ->
            staging.cleanup(job.stagedPath)
            dao.delete(job.id)
        }
    }

    suspend fun retry(id: Long) {
        require(dao.retry(id, System.currentTimeMillis()) == 1) { "Only recoverable failed jobs can be retried" }
    }

    suspend fun pending() = dao.pending()
    suspend fun pendingIntake() = dao.pendingIntake()
    fun observeAll() = dao.observeAll()

    suspend fun updateProgress(id: Long, progress: TransferProgress) {
        dao.updateProgress(id, progress.stage.name, progress.current, progress.total, System.currentTimeMillis())
    }

    suspend fun confirmCleartext(id: Long) {
        val job = requireNotNull(dao.find(id)) { "Upload is missing" }
        val now = System.currentTimeMillis()
        val changed = when {
            job.sourceUrl?.toHttpUrlOrNull()?.scheme == "http" && !job.sourceCleartextConfirmed ->
                dao.confirmSourceCleartext(id, now)
            ServerUrl.parse(job.serverUrl).isCleartext && !job.serverCleartextConfirmed ->
                dao.confirmServerCleartext(id, now)
            else -> 0
        }
        check(changed == 1) {
            "Upload is not awaiting HTTP confirmation"
        }
    }

    private fun allowedFrom(target: UploadJobState): Set<UploadJobState> = when (target) {
        UploadJobState.STAGED -> setOf(UploadJobState.QUEUED, UploadJobState.RUNNING)
        UploadJobState.AWAITING_AUTH -> setOf(UploadJobState.STAGED, UploadJobState.QUEUED, UploadJobState.RUNNING)
        UploadJobState.AWAITING_CLEARTEXT -> setOf(UploadJobState.STAGED, UploadJobState.QUEUED, UploadJobState.RUNNING)
        UploadJobState.QUEUED -> setOf(UploadJobState.STAGED, UploadJobState.AWAITING_AUTH, UploadJobState.RUNNING)
        UploadJobState.RUNNING -> setOf(UploadJobState.QUEUED)
        UploadJobState.SUCCEEDED, UploadJobState.FAILED -> setOf(UploadJobState.RUNNING)
        UploadJobState.CANCELLED -> ACTIVE_STATES
    }

    private companion object {
        val TERMINAL_STATES = setOf(UploadJobState.SUCCEEDED, UploadJobState.FAILED, UploadJobState.CANCELLED)
        val ACTIVE_STATES = UploadJobState.entries.toSet() - TERMINAL_STATES
    }
}
