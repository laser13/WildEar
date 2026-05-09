# Inference Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serialize all inference (reanalysis) calls through a single application-scoped queue so that concurrent access to the non-thread-safe `BioacousticModel` is impossible, and enqueued jobs complete even after the user navigates away from the card.

**Architecture:** A new `@Singleton InferenceQueue` class owns an `applicationScope`-backed worker that processes `Channel<QueuedJob>` items one at a time. `ReviewViewModel` enqueues jobs instead of calling inference directly, and observes `InferenceQueue.status: StateFlow<Map<String, JobStatus>>` to reflect queue position and progress in the UI.

**Tech Stack:** Kotlin Coroutines (`Channel`, `StateFlow`, `combine`), Hilt `@Singleton`, existing `InferenceUseCase`, `DraftRepository`, `applicationScope` (already provided in `AppModule`).

---

## Background and motivation

`BioacousticModel` (BirdNET, Perch TFLite) is explicitly **not** thread-safe. `InferenceRunner` serialises calls within a single recording, but nothing prevents two `ReviewViewModel` instances from calling `inferenceReanalysis.run()` concurrently on the same singleton model — causing `IllegalStateException: Model not loaded` or corrupted output.

`HorizontalPager` keeps up to three `ReviewViewModel` instances alive simultaneously. If the user queues reanalysis on two cards, both VMs race on the same model.

Additionally, `viewModelScope` is cancelled when `vm.release()` is called (navigation away), so in-flight inference jobs are silently dropped.

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
    /** Terminal error. Cleared from the map once the VM picks it up. */
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

## InferenceQueue design

```
InferenceQueue (@Singleton)
├── jobChannel: Channel<QueuedJob>(UNLIMITED)
├── _pendingJobs: MutableStateFlow<List<QueuedJob>>
├── _runningDraftId: MutableStateFlow<String?>
├── _runningStatus: MutableStateFlow<JobStatus.Running?>
├── _failedJobs: MutableStateFlow<Map<String, JobStatus.Failed>>
├── recentDurationMs: Long  (mutable, updated after each job; default 120_000)
└── status: StateFlow<Map<String, JobStatus>>  (derived via combine)
```

**Worker** (launched once in `init`, runs forever in `applicationScope`):
```
for (job in jobChannel) {
    remove job from _pendingJobs
    set _runningDraftId = job.draftId
    try {
        if (runBirdnet) → inferenceUseCase.inferenceReanalysis.run(...) { p →
            _runningStatus = Running(birdnetProgress = p, perchProgress = null)
        }
        if (runPerch) → inferenceUseCase.perchReanalysis.run(...) { p →
            _runningStatus = Running(birdnetProgress = null, perchProgress = p)
        }
        record duration → update recentDurationMs
        repo.mergeAndPersist(...)      // DB write; VM picks it up via Room flow
    } catch {
        _failedJobs[draftId] = Failed(message)
    } finally {
        _runningDraftId = null
        _runningStatus = null
    }
}
```

**`enqueue(job: QueuedJob): Boolean`**
- Returns false (no-op) if `draftId` already in `_pendingJobs` or equals `_runningDraftId`
- Appends to `_pendingJobs`, sends to `jobChannel`

**`cancelQueued(draftId: String)`**
- Removes from `_pendingJobs` if present and not yet running
- Running jobs are not interrupted (they will complete)

**`clearError(draftId: String)`**
- Removes from `_failedJobs`

**`status` derivation:**
```kotlin
combine(_pendingJobs, _runningDraftId, _runningStatus, _failedJobs) {
    pending, runningId, runningStatus, failed ->
    buildMap {
        runningId?.let { put(it, runningStatus ?: Running(null, null)) }
        pending.forEachIndexed { idx, job ->
            val position = if (runningId != null) idx + 1 else idx
            put(job.draftId, Queued(
                position = position,
                estimatedWaitMs = position * recentDurationMs,
            ))
        }
        putAll(failed)
    }
}
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

**`reanalyze()`:**
- Guard: return early if `queuePosition != null` or `inferenceProgress != null` or `perchProgress != null`
- Call `queue.enqueue(QueuedJob(...))` instead of launching inference directly
- Remove the direct `inferenceJob = scope.launch { inferenceReanalysis.run(...) }` block

**`startInference()`** (PENDING_INFERENCE auto-path):
- Also routes through `queue.enqueue(...)` so it respects the same serialisation

**`init {}` — new collect:**
```kotlin
scope.launch {
    queue.status
        .map { it[draftId] }
        .distinctUntilChanged()
        .collect { status ->
            _state.update { s -> s.copy(
                inferenceProgress  = (status as? Running)?.birdnetProgress,
                perchProgress      = (status as? Running)?.perchProgress,
                queuePosition      = (status as? Queued)?.position,
                estimatedWaitMs    = (status as? Queued)?.estimatedWaitMs,
                queueError         = (status as? Failed)?.message,
            )}
            if (status is Failed) queue.clearError(draftId)
        }
}
```

**`release()`:** no change — does NOT cancel queued/running jobs (they live in applicationScope).

**Window predictions (`_windowPreds`):** jobs running in applicationScope cannot update the VM's `_windowPreds`. This is acceptable: the spectrogram overlay is a visual bonus. If the user is on the card while it runs, the overlay works via the StateFlow subscription above (progress updates arrive in real time). If they navigated away and returned, no overlay — result is in the species list.

---

## ReviewScreen UI changes

Replace the single inference progress indicator with two states:

**Queued state** (`queuePosition != null && inferenceProgress == null`):
```
[ LinearProgressIndicator(indeterminate) ]
  "В очереди · позиция N · ~X мин"   (position=0 → "следующий в очереди")
```

**Running state** (existing) — progress bar with percentage, same as now.

**Re-analyze button** — disabled when `queuePosition != null || inferenceProgress != null || perchProgress != null`.

**Error** — `queueError` surfaced the same way as existing `inferenceError`.

---

## Files to change

| Action | File |
|--------|------|
| **Create** | `app/src/main/java/com/sound2inat/app/inference/InferenceQueue.kt` |
| **Modify** | `app/src/main/java/com/sound2inat/app/di/AppModule.kt` — add Hilt `@Provides @Singleton` for `InferenceQueue` |
| **Modify** | `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt` — add 3 new fields |
| **Modify** | `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` — queue integration |
| **Modify** | `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` — queue indicator UI |
| **Modify** | `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt` — update tests |

---

## String resources to add

```xml
<!-- queue indicator -->
<string name="inference_queued_position">В очереди · позиция %d · ~%d мин</string>
<string name="inference_queued_next">Следующий в очереди</string>
```

---

## Spec self-review notes

- **No WorkManager** — queue lives in memory; process death drops pending jobs. Acceptable: drafts stay in DB with their last-known status, user can re-queue manually.
- **No cancellation of running jobs** — simplifies implementation; running jobs always complete. User can cancel a *queued* (not yet started) job via the existing navigation-away path: if they want to abort, they'd need an explicit cancel button, which is out of scope here.
- **ETA is approximate** — based on last completed job's duration. First job shows "~2 min" default. Good enough for a mobile field app.
- **Concurrent BirdNET + Perch** within a single job still runs sequentially inside the worker (BirdNET first, then Perch), matching current `reanalyze()` behaviour.
- **`startInference()` (PENDING_INFERENCE)** also routes through the queue — prevents the edge case of auto-inference on three newly-opened cards racing each other.
