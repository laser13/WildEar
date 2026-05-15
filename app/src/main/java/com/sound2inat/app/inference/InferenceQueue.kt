package com.sound2inat.app.inference

import android.util.Log
import com.sound2inat.inference.InferenceOutcome
import com.sound2inat.inference.InferenceUseCase
import com.sound2inat.inference.ModelIds
import com.sound2inat.inference.PerchAnalysisOutcome
import com.sound2inat.inference.SceneTags
import com.sound2inat.storage.DraftRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed class JobStatus {
    /** Waiting in queue. position=0 means this job is next; position=1 means one job is ahead. */
    data class Queued(val position: Int, val estimatedWaitMs: Long) : JobStatus()

    /** Currently executing. Either progress value may be null when that model hasn't started yet. */
    data class Running(val birdnetProgress: Float?, val perchProgress: Float?) : JobStatus()

    /** Terminal error. Cleared on the next enqueue for this draftId, or explicitly via clearError. */
    data class Failed(val message: String) : JobStatus()
}

data class QueuedJob(
    val draftId: String,
    val audioPath: String,
    val lat: Double?,
    val lon: Double?,
    val recordedAt: Long,
    val runBirdnet: Boolean,
    val runPerch: Boolean,
    /** true = use inferenceReanalysis (no YAMNet gate); false = use inference (with gate). */
    val skipYamNetGate: Boolean,
)

@Singleton
class InferenceQueue @Inject constructor(
    private val scope: CoroutineScope,
    private val inferenceUseCase: InferenceUseCase,
    private val repo: DraftRepository,
) {
    private val enqueueMutex = Mutex()
    private val jobChannel = Channel<QueuedJob>(Channel.UNLIMITED)

    private val _pendingJobs = MutableStateFlow<List<QueuedJob>>(emptyList())
    private val _runningDraftId = MutableStateFlow<String?>(null)
    private val _runningStatus = MutableStateFlow<JobStatus.Running?>(null)
    private val _failedJobs = MutableStateFlow<Map<String, JobStatus.Failed>>(emptyMap())

    @Volatile private var recentDurationMs: Long = 120_000L

    /**
     * Backing store for [status]. Kept in sync with the four source flows via [combine] so reactive
     * collectors see updates, and also patched directly in [clearError] / [cancelQueued] so that
     * [status].value reflects mutations synchronously without requiring a coroutine dispatch cycle.
     */
    private val _status = MutableStateFlow<Map<String, JobStatus>>(emptyMap())
    val status: StateFlow<Map<String, JobStatus>> = _status.asStateFlow()

    init {
        // Sync combine output into _status for reactive UI collectors.
        scope.launch {
            combine(
                _pendingJobs,
                _runningDraftId,
                _runningStatus,
                _failedJobs,
            ) { pending, runningId, runningStatus, failed ->
                buildMap {
                    runningId?.let { put(it, runningStatus ?: JobStatus.Running(null, null)) }
                    pending.forEachIndexed { idx, job ->
                        val position = if (runningId != null) idx + 1 else idx
                        put(
                            job.draftId,
                            JobStatus.Queued(
                                position = position,
                                estimatedWaitMs = position.toLong() * recentDurationMs,
                            )
                        )
                    }
                    putAll(failed)
                }
            }.collect { _status.value = it }
        }

        // Worker: dequeue and execute jobs one at a time.
        scope.launch {
            try {
                for (job in jobChannel) {
                    // Honour cancellations made after trySend but before dequeue.
                    if (_pendingJobs.value.none { it.draftId == job.draftId }) continue
                    _pendingJobs.update { list -> list.filterNot { it.draftId == job.draftId } }
                    _runningDraftId.value = job.draftId
                    _runningStatus.value = JobStatus.Running(null, null)
                    val startMs = System.currentTimeMillis()
                    try {
                        runJob(job)
                        recentDurationMs = System.currentTimeMillis() - startMs
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        _failedJobs.update {
                            it + (
                                job.draftId to JobStatus.Failed(
                                    t.message ?: t::class.simpleName.orEmpty()
                                )
                                )
                        }
                    } finally {
                        _runningDraftId.value = null
                        _runningStatus.value = null
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e("InferenceQueue", "Worker crashed unexpectedly", t)
            }
        }
    }

    private suspend fun runJob(job: QueuedJob) {
        val inferenceJob = if (job.skipYamNetGate) {
            inferenceUseCase.inferenceReanalysis
        } else {
            inferenceUseCase.inference
        }

        if (job.runBirdnet) {
            val outcome = inferenceJob.run(job.audioPath, job.lat, job.lon, job.recordedAt) { p ->
                _runningStatus.value = JobStatus.Running(birdnetProgress = p, perchProgress = null)
            }
            when (outcome) {
                is InferenceOutcome.Success -> {
                    repo.mergeAndPersist(
                        draftId = job.draftId,
                        newModelId = outcome.modelId,
                        newModelVersion = outcome.modelVersion,
                        freshDetections = outcome.detections,
                        promoteToReviewed = true,
                    )
                    if (outcome.sceneTags != SceneTags.EMPTY) {
                        repo.updateSceneTags(job.draftId, outcome.sceneTags.toJson())
                    }
                }
                is InferenceOutcome.Failure ->
                    _failedJobs.update { it + (job.draftId to JobStatus.Failed(outcome.message)) }
            }
        }

        // Note: if BirdNET succeeded and Perch fails below, BirdNET results are already persisted.
        // The VM will see a Failed status for the draft but Room will show the BirdNET detections.
        if (job.runPerch) {
            val perchJob = if (job.skipYamNetGate) {
                inferenceUseCase.perchReanalysis
            } else {
                inferenceUseCase.perchAnalysis
            }
            _runningStatus.value = JobStatus.Running(birdnetProgress = null, perchProgress = 0f)
            val outcome = perchJob.run(job.audioPath, job.lat, job.lon, job.recordedAt) { p ->
                _runningStatus.value = JobStatus.Running(birdnetProgress = null, perchProgress = p)
            }
            when (outcome) {
                is PerchAnalysisOutcome.Success -> repo.mergeAndPersist(
                    draftId = job.draftId,
                    newModelId = ModelIds.PERCH,
                    newModelVersion = "perch",
                    freshDetections = outcome.detections,
                )
                is PerchAnalysisOutcome.Failure ->
                    _failedJobs.update { it + (job.draftId to JobStatus.Failed(outcome.message)) }
                PerchAnalysisOutcome.NotInstalled ->
                    _failedJobs.update { it + (job.draftId to JobStatus.Failed("Perch not installed")) }
            }
        }
    }

    /** Adds job to queue. Returns false if the same draftId is already running or pending. */
    suspend fun enqueue(job: QueuedJob): Boolean = enqueueMutex.withLock {
        _failedJobs.update { it - job.draftId }
        _status.update { it - job.draftId }
        if (_pendingJobs.value.any { it.draftId == job.draftId }) return@withLock false
        if (_runningDraftId.value == job.draftId) return@withLock false
        _pendingJobs.update { it + job }
        jobChannel.trySend(job)
        true
    }

    /** Removes a pending (not yet running) job. No-op if job is already running. */
    fun cancelQueued(draftId: String) {
        _pendingJobs.update { it.filterNot { j -> j.draftId == draftId } }
        _status.update { if (it[draftId] is JobStatus.Queued) it - draftId else it }
    }

    fun clearError(draftId: String) {
        _failedJobs.update { it - draftId }
        _status.update { if (it[draftId] is JobStatus.Failed) it - draftId else it }
    }
}
