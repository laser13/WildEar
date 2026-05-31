package com.sound2inat.app.inference

import android.util.Log
import com.sound2inat.inference.InferenceOutcome
import com.sound2inat.inference.InferenceUseCase
import com.sound2inat.inference.ModelIds
import com.sound2inat.inference.PerchAnalysisOutcome
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

    // Bounded queue: backpressure protects against unbounded growth if the UI
    // somehow floods enqueue(). Capacity is generous relative to realistic
    // review-screen usage (one job per recording).
    private val jobChannel = Channel<QueuedJob>(capacity = MAX_QUEUED_JOBS)

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

        // Worker: dequeue and execute jobs one at a time. Resilient to any per-job
        // failure — a throwable from one job is recorded as Failed and the loop
        // continues. The loop only ends when the channel closes or the scope is
        // cancelled (CancellationException is rethrown).
        scope.launch {
            for (job in jobChannel) {
                try {
                    // Honour cancellations made after trySend but before dequeue.
                    if (_pendingJobs.value.none { it.draftId == job.draftId }) continue
                    _pendingJobs.update { list -> list.filterNot { it.draftId == job.draftId } }
                    _runningDraftId.value = job.draftId
                    _runningStatus.value = JobStatus.Running(null, null)
                    val startMs = System.currentTimeMillis()
                    try {
                        runJob(job)
                        recentDurationMs = System.currentTimeMillis() - startMs
                    } finally {
                        _runningDraftId.value = null
                        _runningStatus.value = null
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e("InferenceQueue", "Job ${job.draftId} failed; worker continues", t)
                    _failedJobs.update {
                        it + (job.draftId to JobStatus.Failed(t.message ?: t::class.simpleName.orEmpty()))
                    }
                }
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

    /** Adds job to queue. Returns false if the same draftId is already running, pending, or the queue is full. */
    suspend fun enqueue(job: QueuedJob): Boolean = enqueueMutex.withLock {
        _failedJobs.update { it - job.draftId }
        _status.update { it - job.draftId }
        if (_pendingJobs.value.any { it.draftId == job.draftId }) return@withLock false
        if (_runningDraftId.value == job.draftId) return@withLock false
        // _pendingJobs.update BEFORE trySend: the worker guard checks _pendingJobs when it
        // dequeues a job. The guard must see the job in pending, so we register it first.
        // If the channel is full (trySend fails), we roll back the pending update so state
        // stays consistent — no orphaned pending entry with no corresponding channel slot.
        _pendingJobs.update { it + job }
        val sent = jobChannel.trySend(job)
        if (sent.isFailure) {
            Log.w("InferenceQueue", "Queue full (>$MAX_QUEUED_JOBS), rejecting ${job.draftId}")
            _pendingJobs.update { it.filterNot { j -> j.draftId == job.draftId } }
            return@withLock false
        }
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

    companion object {
        private const val MAX_QUEUED_JOBS = 64
    }
}
