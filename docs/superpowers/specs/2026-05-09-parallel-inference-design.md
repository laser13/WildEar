# Parallel Inference Design

## Goal

Speed up user-triggered Perch reanalysis by running two independent TFLite interpreter instances in parallel, each processing half the audio windows. Expected result: ~2x speedup for Perch (the main bottleneck), with no change to detection quality.

## Background

For a 3-minute recording, Perch processes ~176 windows sequentially — one `model.predict()` call per window. Each call runs the full 160 000-sample TFLite inference. BirdNET processes ~178 windows similarly but completes faster. The sequential design was correct when a single `BioacousticModel` instance was shared; TFLite interpreters are not thread-safe.

Two separate interpreter instances loaded from the same `.tflite` file are fully independent and safe to run concurrently on different threads. This design exploits that independence.

## Scope

- **In scope:** Perch user-triggered reanalysis (`ProductionPerchAnalysisJob` with `yamNetGate = null`), i.e. the `perchReanalysis` path in `InferenceUseCase`.
- **Out of scope:** BirdNET reanalysis (already fast), PENDING_INFERENCE auto-path, live recording (`LiveInferenceEngine`).

## Architecture

### New interface method: `BioacousticModel.newInstance()`

```kotlin
interface BioacousticModel {
    // existing methods unchanged...

    /**
     * Returns a new, unloaded instance of the same model type.
     * The caller is responsible for calling [load] before [predict],
     * and [close] when done. Does not copy interpreter state.
     */
    fun newInstance(): BioacousticModel
}
```

`BirdNetTfliteModel` and `PerchTfliteModel` implement it by constructing a new instance of themselves (no-arg; the actual TFLite interpreter is created inside `load()`).

### Changed: `InferenceRunner` takes a list of models

```kotlin
class InferenceRunner(
    private val models: List<BioacousticModel>,   // CHANGED: was single BioacousticModel
    private val hopSeconds: Float = 1f,
    private val yamNetGate: YamNetGate? = null,
    val hardGate: Boolean = false,
)
```

`models.first()` is used to read metadata (`modelId`, `windowMs`, `expectedSampleRateHz`). All models in the list are assumed to be the same type.

**Sequential path (unchanged behaviour, `models.size == 1`):**
The existing `for (f in 0 until frames)` loop runs on `models[0]`.

**Parallel path (`models.size > 1`):**

```text
windows [0 .. frames-1]
  ├── chunk 0: [0 .. frames/2-1]   → coroutine-A → models[0]
  └── chunk 1: [frames/2 .. end]   → coroutine-B → models[1]

AtomicInteger(0) tracks completed windows for progress reporting.
coroutineScope { async + async } → await both → concatenate → sort by windowStartMs
```

Progress is reported via the existing `_progress` `MutableStateFlow`. With parallel execution, an `AtomicInteger` counts completed windows across both coroutines; each coroutine emits `counter.incrementAndGet().toFloat() / frames` after each `predict()` call.

On any exception in either coroutine, `coroutineScope` cancels the other (structured concurrency). Exceptions propagate to `ProductionPerchAnalysisJob`'s existing `catch(Throwable)` handler.

Each `models[i]` is closed individually in `finally` blocks inside the parallel `async` lambdas, so all interpreters are freed regardless of success or failure.

### Changed: `ProductionPerchAnalysisJob` (reanalysis path)

`ProductionPerchAnalysisJob` gets a new `parallelism: Int = 1` constructor parameter. When `parallelism == 2`, it loads two Perch interpreter instances before creating `InferenceRunner`:

```kotlin
// Simplified; error handling and logging unchanged
val perch = models.first { it.modelId == ModelIds.PERCH }
val instances = buildList {
    add(perch)
    repeat(parallelism - 1) { add(perch.newInstance()) }
}
// Load all instances; if any load() throws, close already-loaded ones before re-throwing.
val loaded = mutableListOf<BioacousticModel>()
try {
    for (inst in instances) {
        inst.load(state.modelFile, state.labelsFile)
        loaded += inst
    }
} catch (t: Throwable) {
    loaded.forEach { runCatching { it.close() } }
    throw t
}

val runner = InferenceRunner(models = loaded, yamNetGate = gate, hardGate = hardGate)
```

Note: `perch.close()` in the existing `finally` block is removed; closing is now handled inside `InferenceRunner.run()` per instance (each `async` block closes its own model in `finally`).

### Changed: `DefaultInferenceUseCase`

`perchReanalysis` is constructed with `parallelism = 2`:

```kotlin
override val perchReanalysis: PerchAnalysisJob = ProductionPerchAnalysisJob(
    models = models,
    modelManager = modelManager,
    settings = settings,
    yamNetGate = null,
    parallelism = 2,   // NEW
)
```

All other jobs (`inference`, `inferenceReanalysis`, `perchAnalysis`) use `parallelism = 1`.

### Unchanged

- `InferenceQueue` — no changes
- `ReviewViewModel` / `ReviewUiState` — no changes
- `DraftRepository` — no changes
- `ProductionInferenceJob` — updates call site from `InferenceRunner(model, ...)` to `InferenceRunner(listOf(model), ...)` only

## Files to change

| Action | File |
|--------|------|
| Modify | `app/src/main/java/com/sound2inat/inference/BioacousticModel.kt` |
| Modify | `app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt` |
| Modify | `app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt` |
| Modify | `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` |
| Modify | `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt` |
| Create | `app/src/test/java/com/sound2inat/inference/ParallelInferenceRunnerTest.kt` |

## Memory impact

Loading two Perch interpreter instances doubles peak memory for the duration of the Perch run only. Both instances are closed inside `InferenceRunner.run()` before the method returns. If an `OutOfMemoryError` is thrown during the second `load()`, it propagates to `ProductionPerchAnalysisJob`'s `catch(Throwable)` handler, which returns `PerchAnalysisOutcome.Failure(...)`. The UI shows the existing error banner; no crash.

## Testing

`ParallelInferenceRunnerTest`:

- **All windows processed** — with `models = [fake1, fake2]` and 10 windows, both fakes together record exactly windows 0–9 (no gaps, no duplicates).
- **Sort correctness** — results arrive sorted by `windowStartMs` regardless of which coroutine finishes first.
- **Progress monotonicity** — progress values emitted are non-decreasing and reach 1.0.
- **Sequential path unchanged** — `models = [fake1]` and 4 windows calls fake1 exactly 4 times.
- **Failure in one coroutine cancels the other** — if `models[1].predict()` throws, `models[0]` stops receiving new calls.

`BirdNetTfliteModel` / `PerchTfliteModel` `newInstance()` — JVM test: create two instances, load both with the same files, close one, verify the other still predicts (not affected by the close).
