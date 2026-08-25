package io.github.kemko.grimmoryuploader.upload

import io.github.kemko.grimmoryuploader.upload.db.UploadJobDao
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeUploadJobDao : UploadJobDao {
    private var nextId = 1L
    private val jobs = linkedMapOf<Long, UploadJobEntity>()
    private val values = MutableStateFlow<List<UploadJobEntity>>(emptyList())
    private var stagedPathBeforeTransition: String? = null
    var progressUpdateCount = 0
        private set

    override suspend fun insert(job: UploadJobEntity): Long = synchronized(this) {
        nextId.also { id ->
            jobs[id] = job.copy(id = id)
            nextId++
            emit()
        }
    }

    override suspend fun find(id: Long): UploadJobEntity? = synchronized(this) { jobs[id] }

    override suspend fun byServer(serverUrl: String): List<UploadJobEntity> =
        synchronized(this) { jobs.values.filter { it.serverUrl == serverUrl } }

    override suspend fun delete(id: Long) {
        synchronized(this) {
            jobs.remove(id)
            emit()
        }
    }

    override fun observeAll() = values.asStateFlow()

    override suspend fun pending(): List<UploadJobEntity> = synchronized(this) {
        jobs.values.filter { it.state !in TERMINAL_STATES }
    }

    override suspend fun pendingIntake(): UploadJobEntity? = synchronized(this) {
        jobs.values.firstOrNull { it.state == UploadJobState.STAGED }
    }

    override suspend fun transition(
        id: Long,
        fromStates: List<UploadJobState>,
        toState: UploadJobState,
        reason: String?,
        updatedAt: Long,
    ): Int {
        stagedPathBeforeTransition?.let { path ->
            synchronized(this) {
                jobs[id] = jobs.getValue(id).copy(stagedPath = path)
                stagedPathBeforeTransition = null
            }
        }
        return change(id) { job ->
            job.takeIf { it.state in fromStates }
                ?.copy(state = toState, failureReason = reason, updatedAt = updatedAt)
        }
    }

    override suspend fun configure(
        id: Long,
        serverUrl: String,
        libraryId: Long,
        pathId: Long,
        recompressEpub: Boolean,
        serverCleartextConfirmed: Boolean,
        updatedAt: Long,
    ): Int = change(id) { job ->
        job.takeIf { it.serverUrl.isBlank() && it.state == UploadJobState.STAGED }?.copy(
            serverUrl = serverUrl,
            libraryId = libraryId,
            pathId = pathId,
            recompressEpub = recompressEpub,
            serverCleartextConfirmed = serverCleartextConfirmed,
            updatedAt = updatedAt,
        )
    }

    override suspend fun attachStagedPath(id: Long, path: String, displayName: String, updatedAt: Long): Int =
        change(id) { job ->
            job.takeIf { it.state !in TERMINAL_STATES }
                ?.copy(stagedPath = path, displayName = displayName, updatedAt = updatedAt)
        }

    override suspend fun clearStagedPath(id: Long) {
        change(id) { it.copy(stagedPath = null) }
    }

    override suspend fun updateProgress(id: Long, stage: String, current: Long, total: Long, updatedAt: Long): Int {
        val changed = change(id) { job ->
            job.takeIf { it.state in setOf(UploadJobState.QUEUED, UploadJobState.RUNNING) }
                ?.copy(progressStage = stage, progressCurrent = current, progressTotal = total, updatedAt = updatedAt)
        }
        if (changed == 1) progressUpdateCount++
        return changed
    }

    override suspend fun confirmSourceCleartext(id: Long, updatedAt: Long): Int = change(id) { job ->
        job.takeIf { it.state == UploadJobState.AWAITING_CLEARTEXT }
            ?.copy(state = UploadJobState.QUEUED, sourceCleartextConfirmed = true, failureReason = null, updatedAt = updatedAt)
    }

    override suspend fun retry(id: Long, updatedAt: Long): Int = change(id) { job ->
        job.takeIf { it.state == UploadJobState.FAILED && it.sourceUrl != null }
            ?.copy(state = UploadJobState.QUEUED, failureReason = null, updatedAt = updatedAt)
    }

    suspend fun replace(job: UploadJobEntity) {
        synchronized(this) {
            jobs[job.id] = job
            emit()
        }
    }

    fun attachBeforeNextTransition(path: String) {
        stagedPathBeforeTransition = path
    }

    private inline fun change(id: Long, transform: (UploadJobEntity) -> UploadJobEntity?): Int = synchronized(this) {
        val updated = jobs[id]?.let(transform) ?: return@synchronized 0
        jobs[id] = updated
        emit()
        1
    }

    private fun emit() {
        values.value = jobs.values.sortedByDescending(UploadJobEntity::createdAt)
    }

    private companion object {
        val TERMINAL_STATES = setOf(UploadJobState.SUCCEEDED, UploadJobState.FAILED, UploadJobState.CANCELLED)
    }
}
