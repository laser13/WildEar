# Inference Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serialize all review-screen inference (reanalysis) calls through a single application-scoped queue so that concurrent access to the non-thread-safe `BioacousticModel` is impossible, and enqueued jobs complete even after the user navigates away from the card.

**Architecture:** A new `@Singleton InferenceQueue` class owns an `applicationScope`-backed worker that processes `Channel<QueuedJob>` items one at a time. `ReviewViewModel` enqueues jobs instead of calling inference directly, and observes `InferenceQueue.status: StateFlow<Map<String, JobStatus>>` to reflect queue position and progress in the UI.

**Tech Stack:** Kotlin Coroutines (`Channel`, `Mutex`, `StateFlow`, `combine`), Hilt `@Singleton`, existing `InferenceUseCase`, `DraftRepository`, `applicationScope` (already provided in `AppModule`).

---

## Background and motivation

`BioacousticModel` (BirdNET, Perch TFLite) is explicitly **not** thread-safe. `InferenceRunner` serialises calls within a single recording, but nothing prevents two `ReviewViewModel` instances from calling `inferenceReanalysis.run()` concurrently on the same singleton model — causing `IllegalStateException: Model not loaded` or corrupted output.

`HorizontalPager` keeps up to three `ReviewViewModel` instances alive simultaneously. If the user queues reanalysis on two cards, both VMs race on the same model.

Additionally, `viewModelScope` is cancelled when `vm.release()` is called (navigation away), so in-flight inference jobs are silently dropped.

**Out of scope:** Live-recording inference uses `LiveInferenceEngine` which also holds a `BioacousticModel` reference, but it is gated by `RecordingController.start/stop` and never runs concurrently with the review screen in normal use. Serialising live vs review inference is a separate concern not addressed here.

---

## Status model

```kotlin
sealed class JobStatus {
    /** Waiting in queue. position=1 means one job is ahead (currently running). */
    data class Queued(val position: Int, val estimatedWaitMs: Long) : JobStatus()
    /** Currently executing. Either or both progress values may be null (not started yet). */
    data class Running(
        val birdnetProgress: Float?,
        val perchProgress: Float?,
    ) : JobStatus()
    /** Terminal error. Cleared from the map on the next enqueue or after VM picks it up. */
    data class Failed(val message: String) : JobStatus()
    // Success is NOT a status — the result lands in the DB via mergeAndPersist,
    // and the VM receives it via the existing Room observation flow.
}
```

`InferenceQueue` exposes:
```kotlin
val status: StateFlow<Map<String, JobStatus>>
```

Only active draftIds are present in the map. A successfully completed job is removed; the VM gets the result from Room.

---

## QueuedJob model

```kotlin
data class QueuedJob(
    val draftId: String,
    val audioPath: String,
    val lat: Double?,
    val lon: Double?,
    val recordedAt: Long,
    val runBirdnet: Boolean,
    val runPerch: Boolean,
    /** true = use inferenceReanalysis (no YAMNet gate); false = use inference (with gate, for PENDING_INFERENCE). */
    val skipYamNetGate: Boolean,
)
```

---

## InferenceQueue design

```
InferenceQueue (@Singleton)
├── enqueueMutex: Mutex                              (guards check+add+send atomicity)
├── jobChannel: Channel<QueuedJob>(UNLIMITED)
├── _pendingJobs: MutableStateFlow<List<QueuedJob>>
├── _runningDraftId: MutableStateFlow<String?>
├── _runningStatus: MutableStateFlow<JobStatus.Running?>
├── _failedJobs: MutableStateFlow<Map<String, JobStatus.Failed>>
├── recentDurationMs: Long  (var; updated after each job; default 120_000)
└── status: StateFlow<Map<String, JobStatus>>  (derived via combine)
```

### Worker (launched once in `init`, runs in `applicationScope`)

The entire loop body is wrapped in `try/catch(Throwable)` so an unexpected exception does not silently kill the worker:

```
scope.launch {
    try {
        for (job in jobChannel) {
            // Honour cancellations made after trySend but before dequeue
            if (_pendingJobs.value.none { it.draftId == job.draftId }) continue
            _pendingJobs.update { list -> list.filterNot { it.draftId == job.draftId } }
            _runningDraftId.value = job.draftId
            _runningStatus.value = Running(null, null)
            val startMs = System.currentTimeMillis()
            try {
                val inferenceJob = if (job.skipYamNetGate)
                    inferenceUseCase.inferenceReanalysis
                else
                    inferenceUseCase.inference

                if (job.runBirdnet) {
                    val outcome = inferenceJob.run(job.audioPath, job.lat, job.lon, job.recordedAt) { p ->
                        _runningStatus.value = Running(birdnetProgress = p, perchProgress = null)
                    }
                    when (outcome) {
                        is InferenceOutcome.Success ->
                            repo.mergeAndPersist(draftId = job.draftId, ..., promoteToReviewed = true)
                        is InferenceOutcome.Failure ->
                            _failedJobs.update { it + (job.draftId to Failed(outcome.message)) }
                    }
                }

                if (job.runPerch) {
                    val perchJob = if (job.skipYamNetGate)
                        inferenceUseCase.perchReanalysis
                    else
                        inferenceUseCase.perchAnalysis
                    _runningStatus.value = Running(birdnetProgress = null, perchProgress = 0f)
                    val outcome = perchJob.run(job.audioPath, job.lat, job.lon, job.recordedAt) { p ->
                        _runningStatus.value = Running(birdnetProgress = null, perchProgress = p)
                    }
                    when (outcome) {
                        is PerchAnalysisOutcome.Success ->
                            repo.mergeAndPersist(draftId = job.draftId, modelId = ModelIds.PERCH, ...)
                        is PerchAnalysisOutcome.Failure ->
                            _failedJobs.update { it + (job.draftId to Failed(outcome.message)) }
                        PerchAnalysisOutcome.NotInstalled ->
                            _failedJobs.update { it + (job.draftId to Failed("Perch not installed")) }
                    }
                }

                recentDurationMs = System.currentTimeMillis() - startMs
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _failedJobs.update { it + (job.draftId to Failed(t.message ?: t::class.simpleName.orEmpty())) }
            } finally {
                _runningDraftId.value = null
                _runningStatus.value = null
            }
        }
    } catch (t: Throwable) {
        // Worker crashed — log and do not rethrow (applicationScope stays alive)
        Log.e("InferenceQueue", "Worker crashed unexpectedly", t)
    }
}
```

### `enqueue(job: QueuedJob): Boolean`

Guarded by `enqueueMutex` to prevent duplicate submissions from concurrent VM calls:

```kotlin
suspend fun enqueue(job: QueuedJob): Boolean = enqueueMutex.withLock {
    // Clear stale error for this draft so new Queued status is visible immediately
    _failedJobs.update { it - job.draftId }
    if (_pendingJobs.value.any { it.draftId == job.draftId }) return@withLock false
    if (_runningDraftId.value == job.draftId) return@withLock false
    _pendingJobs.update { it + job }
    jobChannel.trySend(job)   // UNLIMITED capacity → always succeeds
    true
}
```

Since `enqueue` is now `suspend`, callers must launch it in a coroutine scope.

### `cancelQueued(draftId: String)`

Removes from `_pendingJobs`. Because the job may already be in the Channel, the worker's skip-check (`if none { ... } continue`) handles it there:

```kotlin
fun cancelQueued(draftId: String) {
    _pendingJobs.update { it.filterNot { j -> j.draftId == draftId } }
}
```

### `clearError(draftId: String)`

```kotlin
fun clearError(draftId: String) {
    _failedJobs.update { it - draftId }
}
```

### `status` derivation

```kotlin
val status: StateFlow<Map<String, JobStatus>> = combine(
    _pendingJobs, _runningDraftId, _runningStatus, _failedJobs,
) { pending, runningId, runningStatus, failed ->
    buildMap {
        runningId?.let { put(it, runningStatus ?: Running(null, null)) }
        pending.forEachIndexed { idx, job ->
            val position = if (runningId != null) idx + 1 else idx
            put(job.draftId, Queued(
                position = position,
                estimatedWaitMs = position.toLong() * recentDurationMs,
            ))
        }
        putAll(failed)
    }
}.stateIn(scope, SharingStarted.Eagerly, emptyMap())
```

---

## ReviewUiState additions

```kotlin
// New fields (nullable = not in queue / not running):
val queuePosition: Int? = null          // null → not queued
val estimatedWaitMs: Long? = null       // null → not queued
val queueError: String? = null          // null → no error
```

Existing fields `inferenceProgress` and `perchProgress` continue to represent the Running state (populated from `JobStatus.Running`).

---

## ReviewViewModel changes

### `reanalyze()`

```kotlin
fun reanalyze(runBirdnet: Boolean, runPerch: Boolean) {
    if (!runBirdnet && !runPerch) return
    val s = _state.value
    if (s.queuePosition != null || s.inferenceProgress != null || s.perchProgress != null) return
    val path = s.audioPath ?: return
    scope.launch {
        queue.enqueue(QueuedJob(
            draftId = draftId,
            audioPath = path,
            lat = s.latitude,
            lon = s.longitude,
            recordedAt = s.recordedAtUtcMs,
            runBirdnet = runBirdnet,
            runPerch = runPerch,
            skipYamNetGate = true,
        ))
    }
}
```

Remove the direct `inferenceJob = scope.launch { inferenceReanalysis.run(...) }` block entirely.

### `startInference()` (PENDING_INFERENCE auto-path)

Routes through `queue.enqueue(...)` with `skipYamNetGate = false`. Keeps `inferenceStarted = true` guard to prevent re-enqueue on subsequent Room emissions.

```kotlin
private fun startInference(path: String, lat: Double?, lon: Double?, recordedAt: Long) {
    scope.launch {
        queue.enqueue(QueuedJob(
            draftId = draftId,
            audioPath = path,
            lat = lat,
            lon = lon,
            recordedAt = recordedAt,
            runBirdnet = true,
            runPerch = false,
            skipYamNetGate = false,
        ))
    }
}
```

### `init {}` — new queue status collector

```kotlin
scope.launch {
    queue.status
        .map { it[draftId] }
        .distinctUntilChanged()
        .collect { status ->
            _state.update { s -> s.copy(
                inferenceProgress = (status as? Running)?.birdnetProgress,
                perchProgress     = (status as? Running)?.perchProgress,
                queuePosition     = (status as? Queued)?.position,
                estimatedWaitMs   = (status as? Queued)?.estimatedWaitMs,
                queueError        = (status as? Failed)?.message,
                inferenceError    = if (status is Failed) status.message else s.inferenceError,
            )}
            if (status is Failed) queue.clearError(draftId)
        }
}
```

### Annotation skip guard

In the Room observation collector, extend the `inferenceRunning` check:

```kotlin
val inferenceRunning = draft.status == DraftStatus.PENDING_INFERENCE
    || _state.value.inferenceProgress != null
    || _state.value.perchProgress != null
    || _state.value.queuePosition != null   // ← add this
```

### `_state.update` consistency

All places in `ReviewViewModel` that write `_state.value = _state.value.copy(...)` touching inference-progress fields must be converted to `_state.update { s -> s.copy(...) }` to avoid overwriting queue status written by the collector above.

### `release()`

No change — does **not** cancel queued or running jobs (they live in `applicationScope`).

### Window predictions (`_windowPreds`)

Jobs running in `applicationScope` cannot update the VM's `_windowPreds`. **Window predictions (spectrogram overlay) are therefore not shown for jobs that ran while the user was on a different card.** This is acceptable — the overlay is a visual bonus. If the user stays on the card while it runs, they see live progress via the status StateFlow. On return, the results appear in the species list from Room.

---

## DI wiring

`InferenceQueue` must be injected into `ReviewViewModelFactory` (not just bound in `AppModule`). Required changes:

1. `AppModule` (or `SwappableModule`) — add `@Provides @Singleton` for `InferenceQueue`, injecting `@ApplicationScope CoroutineScope`, `InferenceUseCase`, `DraftRepository`.
2. `ReviewViewModelFactory` constructor — add `private val queue: InferenceQueue`.
3. `ReviewViewModelFactory.create(draftId)` — pass `queue` to `ReviewViewModel`.
4. `ReviewViewModel` constructor — add `private val queue: InferenceQueue`.
5. Test fakes — add `FakeInferenceQueue` (or use a real one backed by `UnconfinedTestDispatcher`).

---

## ReviewScreen UI changes

Replace the single inference progress indicator with two states:

**Queued state** (`queuePosition != null && inferenceProgress == null`):
```
[ LinearProgressIndicator(indeterminate) ]
  "В очереди · позиция N · ~X мин"   (position=0 → "Следующий в очереди")
```

**Running state** (existing) — progress bar with percentage, same as now.

**Re-analyze button** — disabled when `queuePosition != null || inferenceProgress != null || perchProgress != null`.

**Error** — `queueError` surfaced the same way as existing `inferenceError`.

---

## String resources to add

```xml
<string name="inference_queued_position">В очереди · позиция %d · ~%d мин</string>
<string name="inference_queued_next">Следующий в очереди</string>
```

---

## Files to change

| Action | File |
|--------|------|
| **Create** | `app/src/main/java/com/sound2inat/app/inference/InferenceQueue.kt` |
| **Modify** | `app/src/main/java/com/sound2inat/app/di/AppModule.kt` or `SwappableModule.kt` |
| **Modify** | `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt` |
| **Modify** | `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` |
| **Modify** | `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` |
| **Modify** | `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt` |
