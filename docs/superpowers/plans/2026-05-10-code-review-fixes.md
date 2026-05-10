# Code Review Fixes — Wild Ear Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all critical, major, and minor issues found during the 2026-05-10 architectural review of the Wild Ear Android app.

**Architecture:** Fixes are ordered by severity (CRITICAL → MAJOR → MINOR) with intra-task dependencies honoured (Task 1 before Task 4, Task 5 before Task 6, Task 7 before any Room schema changes). Each task is self-contained and ends with a full CI pass.

**Tech Stack:** Kotlin, Jetpack Compose, TFLite (BirdNET v2.4, Perch v2, YAMNet), Room, Hilt, Kotlin Coroutines/Flow, OkHttp, iNaturalist API.

---

## CI command (run after every task)

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon && \
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
```

---

## Phase 1 — CRITICAL

### Task 1 — CRITICAL: Inference pipeline resource leaks and race conditions

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 1

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/YamNetGate.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InterpreterFactory.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/BirdNetMetaModel.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Test: `app/src/test/java/com/sound2inat/inference/` (existing tests)

**Context for the agent:** Read each file before editing. The inference package has six independent bugs fixed in this task. Complete all six before committing — they form one coherent changeset.

---

- [ ] **Step 1 — Read all six files**

  Read in order:
  1. `YamNetGate.kt`
  2. `YamNetTfliteGate.kt`
  3. `InterpreterFactory.kt`
  4. `BirdNetMetaModel.kt`
  5. `LiveInferenceEngine.kt`
  6. `InferenceRunner.kt`

---

- [ ] **Step 2 — Add `close()` default method to `YamNetGate` interface**

  File: `app/src/main/java/com/sound2inat/inference/YamNetGate.kt`

  **Reason:** `YamNetTfliteGate` holds a TFLite interpreter but `YamNetGate` has no `close()` contract, so callers cannot release the native memory.

  Replace the entire file with:

  ```kotlin
  package com.sound2inat.inference

  fun interface YamNetGate {
      /**
       * Classifies a PCM window and returns a [YamNetGateResult] with biological
       * score, background score, and a PASS/DOWNRANK recommendation.
       *
       * Returns null when the gate is unavailable (model not installed, error) —
       * callers must treat null as PASS (fail-open).
       */
      suspend fun classify(pcmFloat32: FloatArray, sampleRateHz: Int): YamNetGateResult?

      /** Releases native TFLite resources. Safe to call multiple times. */
      fun close() {}
  }
  ```

---

- [ ] **Step 3 — Add `Mutex` protection and `close()` to `YamNetTfliteGate`**

  File: `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`

  **Bug 1:** `classifyInternal` calls `interp.run(input, output)` outside the existing `mutex`, so parallel workers (`parallelism=2`) race on the same interpreter.
  **Bug 2:** `interpreter` is never closed — a new `InferenceRunner` creation leaks the previous native TFLite handle.

  **Change 1:** protect `classifyInternal` body with `mutex.withLock { }` (move the entire logic inside the lock). The existing `mutex` in the class is reused.

  Replace the method `classifyInternal`:

  ```kotlin
  @Suppress("ReturnCount")
  private suspend fun classifyInternal(pcmFloat32: FloatArray, sampleRateHz: Int): YamNetGateResult? =
      mutex.withLock {
          ensureLoadedUnlocked()
          val interp = interpreter ?: return@withLock null
          val resampled = resampleTo16k(pcmFloat32, sampleRateHz)

          var maxBioScore = 0f
          var maxNoiseScore = 0f
          var anyNoiseTopWithLowBio = false
          var frameStart = 0
          while (frameStart < resampled.size) {
              val frame = FloatArray(YAMNET_FRAME_SIZE)
              val end = (frameStart + YAMNET_FRAME_SIZE).coerceAtMost(resampled.size)
              resampled.copyInto(frame, destinationOffset = 0, startIndex = frameStart, endIndex = end)

              val input = arrayOf(frame)
              val output = arrayOf(FloatArray(CLASS_COUNT))
              interp.run(input, output)
              val probs = output[0]

              val bioScore = bioIndices.maxOfOrNull { probs[it] } ?: 0f
              val noiseScore = noiseIndices.maxOfOrNull { probs[it] } ?: 0f
              val topClass = probs.indices.maxByOrNull { probs[it] } ?: 0
              if (bioScore > maxBioScore) maxBioScore = bioScore
              if (noiseScore > maxNoiseScore) maxNoiseScore = noiseScore
              if (topClass in noiseIndices && bioScore < BIO_THRESHOLD) anyNoiseTopWithLowBio = true
              frameStart += YAMNET_FRAME_SIZE
          }
          val passes = !(maxBioScore < BIO_THRESHOLD && anyNoiseTopWithLowBio)
          val recommendation = if (passes) GateRecommendation.PASS else GateRecommendation.DOWNRANK
          YamNetGateResult(
              biologicalScore = maxBioScore,
              backgroundScore = maxNoiseScore,
              recommendation = recommendation,
          )
      }
  ```

  **Change 2:** rename `ensureLoaded` to `ensureLoadedUnlocked` (it will now be called from *within* the lock):

  ```kotlin
  private fun ensureLoadedUnlocked() {
      if (interpreter != null) return
      val state = modelManager.stateFor(YamNetV1.descriptor) as? ModelInstallState.Ready
          ?: return
      val classMap = parseClassMap(state.labelsFile)
      bioIndices = BIOLOGICAL_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
      noiseIndices = NOISE_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
      interpreter = factory.create(state.labelsFile.parentFile!!.resolve("${YamNetV1.descriptor.id}.tflite"), threads = 1)
  }
  ```

  Wait — `ensureLoaded()` is a `suspend fun` that calls `modelManager.stateFor()` (also suspend). Since we're now inside a `mutex.withLock {}` block (which is also suspend), this is fine. But the current `ensureLoaded` is a `suspend fun` and uses `mutex.withLock`. We're restructuring so that `classifyInternal` itself holds the lock and calls a non-suspend `ensureLoadedUnlocked`.

  However `modelManager.stateFor()` is suspend. So we need to keep it as suspend. The cleanest fix is: keep `ensureLoaded()` as a suspend fun that holds the mutex, and call it *before* acquiring the lock for the inference, using a two-phase approach:

  ```kotlin
  @Suppress("ReturnCount")
  private suspend fun classifyInternal(pcmFloat32: FloatArray, sampleRateHz: Int): YamNetGateResult? {
      ensureLoaded() // acquires mutex internally, loads model if needed, then releases
      val resampled = resampleTo16k(pcmFloat32, sampleRateHz)
      return mutex.withLock {
          val interp = interpreter ?: return@withLock null
          var maxBioScore = 0f
          var maxNoiseScore = 0f
          var anyNoiseTopWithLowBio = false
          var frameStart = 0
          while (frameStart < resampled.size) {
              val frame = FloatArray(YAMNET_FRAME_SIZE)
              val end = (frameStart + YAMNET_FRAME_SIZE).coerceAtMost(resampled.size)
              resampled.copyInto(frame, destinationOffset = 0, startIndex = frameStart, endIndex = end)
              val input = arrayOf(frame)
              val output = arrayOf(FloatArray(CLASS_COUNT))
              interp.run(input, output)
              val probs = output[0]
              val bioScore = bioIndices.maxOfOrNull { probs[it] } ?: 0f
              val noiseScore = noiseIndices.maxOfOrNull { probs[it] } ?: 0f
              val topClass = probs.indices.maxByOrNull { probs[it] } ?: 0
              if (bioScore > maxBioScore) maxBioScore = bioScore
              if (noiseScore > maxNoiseScore) maxNoiseScore = noiseScore
              if (topClass in noiseIndices && bioScore < BIO_THRESHOLD) anyNoiseTopWithLowBio = true
              frameStart += YAMNET_FRAME_SIZE
          }
          val passes = !(maxBioScore < BIO_THRESHOLD && anyNoiseTopWithLowBio)
          YamNetGateResult(
              biologicalScore = maxBioScore,
              backgroundScore = maxNoiseScore,
              recommendation = if (passes) GateRecommendation.PASS else GateRecommendation.DOWNRANK,
          )
      }
  }
  ```

  The existing `ensureLoaded()` method already uses `mutex.withLock { }` internally (non-reentrant), so calling it before the second `mutex.withLock` is safe — kotlinx Mutex is not reentrant.

  **Change 3:** add `close()` override after the companion object (or before it):

  ```kotlin
  override fun close() {
      // runCatching: interpreter.close() is idempotent on TFLite but not declared so; guard defensively.
      runCatching { interpreter?.close() }
      interpreter = null
  }
  ```

---

- [ ] **Step 4 — Fix `InterpreterFactory.kt`: protect file descriptors on exception**

  File: `app/src/main/java/com/sound2inat/inference/InterpreterFactory.kt`

  **Bug:** `RandomAccessFile` and `FileChannel` are opened before the `try` block. If both `Interpreter` constructors throw, the file handles leak.

  In `TfliteInterpreterFactory.create()`, wrap the body so that `raf` and `channel` are closed on any exception. The `close()` on the returned `InterpreterApi` already closes them on happy path.

  Replace the entire `create` function body:

  ```kotlin
  override fun create(modelFile: File, threads: Int, allowDelegate: Boolean): InterpreterApi {
      val raf = RandomAccessFile(modelFile, "r")
      val channel = raf.channel
      try {
          val buffer: MappedByteBuffer =
              channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())

          var gpu: GpuDelegate? = null
          val interpreter = if (allowDelegate) {
              try {
                  val delegate = GpuDelegate()
                  val opts = Interpreter.Options().apply {
                      numThreads = threads
                      addDelegate(delegate)
                  }
                  val i = Interpreter(buffer, opts)
                  gpu = delegate
                  Log.i(TAG, "TFLite using GPU delegate (threads=$threads)")
                  i
              } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                  gpu?.close()
                  gpu = null
                  Log.w(TAG, "GPU delegate unavailable; falling back to CPU+XNNPACK (threads=$threads)", e)
                  Interpreter(buffer, Interpreter.Options().apply { numThreads = threads })
              }
          } else {
              Log.d(TAG, "GPU delegate skipped; using CPU+XNNPACK (threads=$threads)")
              Interpreter(buffer, Interpreter.Options().apply { numThreads = threads })
          }

          val capturedGpu = gpu
          return object : InterpreterApi {
              override val outputTensorCount: Int get() = interpreter.outputTensorCount
              override fun getOutputShape(index: Int): IntArray =
                  interpreter.getOutputTensor(index).shape()
              override fun run(input: Any, output: Any) = interpreter.run(input, output)
              override fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>) {
                  interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
              }
              override fun close() {
                  interpreter.close()
                  capturedGpu?.close()
                  channel.close()
                  raf.close()
              }
          }
      } catch (t: Throwable) {
          runCatching { channel.close() }
          runCatching { raf.close() }
          throw t
      }
  }
  ```

---

- [ ] **Step 5 — Fix `BirdNetMetaModel.kt`: move `interp.run` inside mutex**

  File: `app/src/main/java/com/sound2inat/inference/BirdNetMetaModel.kt`

  **Bug:** `ensureLoaded()` acquires the mutex to lazily load the interpreter. But `interp.run(input, output)` in `computePriors` is called *after* the mutex is released — two concurrent callers can both get the same non-thread-safe interpreter reference and call `.run()` simultaneously.

  Replace `computePriors`:

  ```kotlin
  private suspend fun computePriors(
      latitude: Double,
      longitude: Double,
      weekOfYear: Int,
  ): Map<String, Float>? = mutex.withLock {
      ensureLoadedUnlocked()
      val interp = interpreter ?: return@withLock null
      if (labels.isEmpty()) return@withLock null

      val weekMeta = (cos(weekOfYear * WEEK_RADIANS) + 1.0).toFloat()
      val input = arrayOf(floatArrayOf(latitude.toFloat(), longitude.toFloat(), weekMeta))
      val output = arrayOf(FloatArray(labels.size))
      interp.run(input, output)
      val rawPriors = output[0]

      val result = HashMap<String, Float>(labels.size)
      for (i in labels.indices) {
          val multiplier = applyThreshold(rawPriors[i])
          if (multiplier > 0f) result[labels[i].scientificName] = multiplier
      }
      result
  }
  ```

  Rename the existing `ensureLoaded()` (which currently acquires the mutex itself) to `ensureLoadedUnlocked()` and remove the inner `mutex.withLock` from it — it will now be called from within the outer lock in `computePriors`:

  ```kotlin
  private fun ensureLoadedUnlocked() {
      if (interpreter != null) return
      val state = modelManager.stateFor(BirdNetMetaV24.descriptor) as? ModelInstallState.Ready
          ?: return
      labels = Labels.load(state.labelsFile, LabelsFormat.BirdNetUnderscore)
      interpreter = factory.create(state.modelFile, threads = 1, allowDelegate = false)
  }
  ```

  **Note:** `modelManager.stateFor()` is a `suspend` function. Since `computePriors` is already `suspend` and `mutex.withLock` is a suspend call, calling a suspend function from inside `withLock` is fine. However `ensureLoadedUnlocked` must now be a `suspend fun` too. Rename it `private suspend fun ensureLoadedUnlocked()` and keep the `modelManager.stateFor()` call inside it.

  Remove the old `ensureLoaded()` method entirely.

---

- [ ] **Step 6 — Fix `LiveInferenceEngine.kt`: `AtomicBoolean` start guard + `model.close()` in `stop()`**

  File: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`

  **Bug 1:** `stop()` does not call `model.close()` — every recording leaks the BirdNET TFLite interpreter.
  **Bug 2:** `start()` guard is `if (workerJob != null) return` — this is not thread-safe under concurrent calls.

  **Change 1:** Add `AtomicBoolean` start guard. In the class body, replace the existing `@Volatile private var stopped = false` with an `AtomicBoolean` that also tracks "started":

  ```kotlin
  private val started = java.util.concurrent.atomic.AtomicBoolean(false)
  @Volatile private var stopped = false
  ```

  Replace the existing `start()` method:

  ```kotlin
  open fun start(scope: CoroutineScope) {
      check(!started.getAndSet(true)) { "LiveInferenceEngine is single-use: cannot start after stop or after a prior start" }
      workerJob = scope.launch(Dispatchers.Default) { worker() }
  }
  ```

  **Change 2:** Add `model.close()` in `stop()`. Replace the existing `stop()`:

  ```kotlin
  open suspend fun stop() {
      if (stopped) return
      stopped = true
      queue.close()
      withTimeoutOrNull(DRAIN_TIMEOUT_MS) { workerJob?.join() }
      workerJob?.cancel()
      workerJob = null
      runCatching { model.close() }
          .onFailure { Log.w(TAG, "model.close() threw in stop()", it) }
  }
  ```

---

- [ ] **Step 7 — Fix `InferenceRunner.kt`: call `yamNetGate?.close()` after model closures**

  File: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`

  **Reason:** Now that `YamNetGate` has a `close()` contract, `InferenceRunner` (which owns the gate during inference) should close it after all windows are processed.

  In `runSequential`, add after the existing `finally { model.close() }` block (after the loop, before the return):

  ```kotlin
  // Close the gate after all windows are done.
  runCatching { yamNetGate?.close() }
      .onFailure { Log.w("InferenceRunner", "yamNetGate.close() threw", it) }
  ```

  In `runParallel`, add after `_progress.value = 1f` (before the return):

  ```kotlin
  runCatching { yamNetGate?.close() }
      .onFailure { Log.w("InferenceRunner", "yamNetGate.close() threw", it) }
  ```

  Also add the `frames == 0` early-return gate close:
  In the existing early-return block (`if (frames == 0)`), add before `return emptyList()`:

  ```kotlin
  if (frames == 0) {
      models.forEach { runCatching { it.close() } }
      runCatching { yamNetGate?.close() }
      _progress.value = 1f
      return emptyList()
  }
  ```

---

- [ ] **Step 8 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

  Expected: all three pass. Fix any detekt/compile issues before committing.

---

- [ ] **Step 9 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/inference/YamNetGate.kt \
    app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt \
    app/src/main/java/com/sound2inat/inference/InterpreterFactory.kt \
    app/src/main/java/com/sound2inat/inference/BirdNetMetaModel.kt \
    app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt \
    app/src/main/java/com/sound2inat/inference/InferenceRunner.kt
  git commit -m "fix(inference): close YamNetGate; mutex-protect TFLite run; close model in stop()"
  ```

---

### Task 2 — CRITICAL: UI layer — GPU race in spectrogram + ReviewViewModel concurrency and lifecycle

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 2

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/review/` (existing tests)

**Context for the agent:** Read all three files fully before editing. The `ReviewViewModel` + `ReviewScreen` changes must land in one commit (the externalScope wiring spans both files). The `LiveSpectrogramView` change is independent.

---

- [ ] **Step 1 — Read the three files**

  Read in order:
  1. `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`
  2. `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
  3. `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

---

- [ ] **Step 2 — Fix `LiveSpectrogramView.kt`: double-buffering to prevent GPU race**

  File: `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`

  **Bug:** A single `Bitmap` is mutated in-place via `setPixels()` from `withContext(Dispatchers.Default)` then read by Compose's GPU texture upload on the main thread. There is no synchronization — the GPU may read stale or partially-written pixel data.

  **Fix:** Use a `MutableState<ImageBitmap?>` that only holds completed frames. Render into a separate off-screen `pixels` array on `Dispatchers.Default`, then create a fresh `ImageBitmap` snapshot on the main thread.

  Replace the entire composable body (from `val bitmap = remember` through `revision`):

  ```kotlin
  val pixels = remember(bgArgb) { IntArray(BITMAP_WIDTH_COLS * BITMAP_HEIGHT_BINS) { bgArgb } }
  val ring = remember { SpectrogramRingBuffer(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS) }
  val spectrogram = remember(sampleRateHz) {
      Spectrogram(fftSize = FFT_SIZE, hopSize = HOP_SIZE, sampleRateHz = sampleRateHz)
  }
  val sortBuf = remember { FloatArray(FFT_SIZE / 2 + 1) }
  var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

  LaunchedEffect(audioBlocks) {
      audioBlocks.collect { block ->
          val snapshot: IntArray? = withContext(Dispatchers.Default) {
              val columns = spectrogram.process(block)
              for (col in columns) {
                  whitenInPlace(col, sortBuf)
                  ring.append(logBinDown(col, BITMAP_HEIGHT_BINS, sampleRateHz))
              }
              if (columns.isNotEmpty()) {
                  fillPixels(pixels, ring, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, lut, bgArgb)
                  pixels.copyOf() // hand off a completed snapshot to the main thread
              } else {
                  null
              }
          }
          if (snapshot != null) {
              val bmp = android.graphics.Bitmap.createBitmap(
                  BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, android.graphics.Bitmap.Config.ARGB_8888
              )
              bmp.setPixels(snapshot, 0, BITMAP_WIDTH_COLS, 0, 0, BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS)
              imageBitmap = bmp.asImageBitmap()
          }
      }
  }
  ```

  Remove the old `val bitmap = remember { ... }` and `var revision by remember { mutableStateOf(0) }` lines.

  Update the `Image` composable to use the new state:

  ```kotlin
  imageBitmap?.let { img ->
      Image(
          bitmap = img,
          contentDescription = null,
          modifier = modifier.fillMaxSize(),
          contentScale = ContentScale.FillBounds,
      )
  }
  ```

  Add the required import at the top of the file:
  ```kotlin
  import androidx.compose.ui.graphics.ImageBitmap
  import androidx.compose.ui.graphics.asImageBitmap
  import androidx.compose.runtime.getValue
  import androidx.compose.runtime.setValue
  ```

  Remove the old imports for `revision` (no new ones needed beyond what already exists).

---

- [ ] **Step 3 — Fix `ReviewViewModel.kt`: replace non-atomic `_state.value = _state.value.copy(...)` with `_state.update { }`**

  File: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`

  **Bug:** Three coroutines (`position`, `isPlaying`, `lastError`) each do `_state.value = _state.value.copy(...)`. `MutableStateFlow.value` setter is atomic for individual writes but the read-modify-write pattern is not — one coroutine can clobber another's update.

  Search the file for all occurrences of `_state.value = _state.value.copy(` and replace each one with `_state.update { it.copy(`. The lambda parameter is `it`.

  **Do this globally across the entire file** — there are ~25 occurrences. Use the exact transformation:
  - `_state.value = _state.value.copy(` → `_state.update { it.copy(`
  - At the end of the corresponding `copy(...)` block, add `)` to close the lambda: `_state.update { it.copy(...) }`

  **Important:** `_state.update { it.copy(...) }` already uses `it` as the implicit receiver. Ensure every copy block ends with `}` for the lambda.

  Run a search after editing to verify no occurrences of `_state.value = _state.value.copy(` remain.

---

- [ ] **Step 4 — Fix `ReviewViewModel.kt` + `ReviewScreen.kt`: externalScope ownership**

  **Bug:** `ReviewViewModel` is instantiated via `ReviewViewModelFactory.create(draftId)` outside any `ViewModelStore`. Its `viewModelScope` is technically attached to the `ViewModel` object but `onCleared()` is never called automatically — coroutines on that scope are only cancelled when `release()` is called explicitly from `DisposableEffect`.

  The spec fix: always pass `externalScope` from the parent `ReviewPagerViewModel.viewModelScope`. `ReviewPagerViewModel` is a proper `@HiltViewModel` whose `onCleared()` fires on configuration changes.

  **In `ReviewViewModel.kt`:** Find the `ReviewViewModelFactory`. Read the factory class and how it creates instances. The factory likely looks like:

  ```kotlin
  class ReviewViewModelFactory @Inject constructor(
      ...,
      val filesDir: File,
  ) {
      fun create(draftId: String): ReviewViewModel = ReviewViewModel(
          draftId = draftId,
          ...
          // no externalScope passed currently
      )
  }
  ```

  Update `ReviewViewModelFactory.create()` to accept and pass an `externalScope` parameter:

  ```kotlin
  fun create(draftId: String, externalScope: CoroutineScope): ReviewViewModel = ReviewViewModel(
      draftId = draftId,
      ...,
      externalScope = externalScope,
  )
  ```

  **In `ReviewViewModel`** constructor: ensure `externalScope` is received. Look for the `private val scope: CoroutineScope` line and confirm it reads `externalScope ?: viewModelScope`. If the constructor currently has no `externalScope` parameter, add one with a default `null`.

  **In `ReviewScreen.kt`:** Find the `remember(draftId) { pagerVm.factory.create(draftId) }` call (around line 139) and update it to pass `pagerVm.viewModelScope`:

  ```kotlin
  val vm = remember(draftId) { pagerVm.factory.create(draftId, pagerVm.viewModelScope) }
  ```

  The `DisposableEffect(draftId) { onDispose { vm.release() } }` stays in place — it handles `pause()` and media player release. The scope cancellation is now handled by `ReviewPagerViewModel.onCleared()`.

  **In `ReviewViewModel.release()`:** Verify that `release()` does NOT cancel the scope (since the scope is now owned by the pager). If `release()` currently calls `scope.cancel()`, remove that call. The method should only call `player.release()` and any other non-scope cleanup.

---

- [ ] **Step 5 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 6 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt \
    app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt \
    app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt
  git commit -m "fix(ui): double-buffer spectrogram bitmap; atomic state updates; ReviewViewModel externalScope"
  ```

---

### Task 3 — CRITICAL: Data layer + Audio — deadlock, silent rename failure, WAV integer overflow

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 3

**Files:**
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`
- Modify: `app/src/main/java/com/sound2inat/recorder/WavWriter.kt`
- Modify: `app/src/main/java/com/sound2inat/inat/WavTrimmer.kt`
- Test: `app/src/test/java/com/sound2inat/` (existing tests)

---

- [ ] **Step 1 — Read all four files**

  Read in order:
  1. `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
  2. `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`
  3. `app/src/main/java/com/sound2inat/recorder/WavWriter.kt`
  4. `app/src/main/java/com/sound2inat/inat/WavTrimmer.kt`

---

- [ ] **Step 2 — Fix `DraftRepository.kt`: remove `observeWithDetections().first()` inside mutex**

  File: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`

  **Bug:** `mergeAndPersist` calls `observeWithDetections(draftId).first()` inside `persistMutex.withLock {}`. `observeWithDetections` is a Room `Flow` — if the draft is deleted concurrently, `mapNotNull` filters the null result and `first()` suspends forever, permanently deadlocking the `persistMutex` for all drafts.

  Replace the call site in `mergeAndPersist`. The current code at the top of the lock is:

  ```kotlin
  val dwd = observeWithDetections(draftId).first()
  val existing = dwd.detections.map { e -> ... }
  val priorModelId = dwd.draft.modelId
  ```

  Replace it with direct DAO calls (no Flow, no `.first()`):

  ```kotlin
  val draft = withContext(ioDispatcher) { drafts.getById(draftId) }
      ?: error("draft $draftId missing in mergeAndPersist")
  val detectionList = withContext(ioDispatcher) { detections.listForDraft(draftId) }
  val existing = detectionList.map { e -> ... }
  val priorModelId = draft.modelId
  ```

  Update all references from `dwd.draft` → `draft` and `dwd.detections` → `detectionList`.

  **Note:** `detections.listForDraft(draftId)` may not exist yet. Check `DetectionDao` — if only `observeForDraft(draftId): Flow<List<DetectionEntity>>` exists, add a one-shot query to `DetectionDao`:

  ```kotlin
  @Query("SELECT * FROM detections WHERE draftId = :draftId")
  fun listForDraft(draftId: String): List<DetectionEntity>
  ```

  This is a non-suspend DAO query (Room Room supports synchronous queries on DAOs when called from a non-main thread — wrap the call in `withContext(ioDispatcher)`).

---

- [ ] **Step 3 — Fix `ModelManager.kt`: check `renameTo()` return value**

  File: `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`

  **Bug:** Lines 61–62 ignore the boolean return of `File.renameTo()`. If the rename fails (e.g. cross-filesystem on some Android OEMs), the `.partial` file is not moved but `ModelInstallState.Ready(mFinal, lFinal)` is emitted anyway — TFLite fails to load a non-existent file.

  Replace:

  ```kotlin
  modelTmp.renameTo(mFinal)
  labelsTmp.renameTo(lFinal)
  ```

  With:

  ```kotlin
  check(modelTmp.renameTo(mFinal)) {
      "Failed to rename model file: ${modelTmp.name} → ${mFinal.name}"
  }
  check(labelsTmp.renameTo(lFinal)) {
      "Failed to rename labels file: ${labelsTmp.name} → ${lFinal.name}"
  }
  ```

  The surrounding `try/catch (t: Throwable)` block already handles this — `check` throwing `IllegalStateException` will be caught, partial files cleaned up, and `ModelInstallState.Failed` emitted.

---

- [ ] **Step 4 — Fix `WavWriter.kt`: change `dataBytesWritten` from `Int` to `Long`**

  File: `app/src/main/java/com/sound2inat/recorder/WavWriter.kt`

  **Bug:** `var dataBytesWritten: Int = 0` overflows at ~6.2 hours at 48 kHz/16-bit/mono. The overflow produces a negative value which is written into the 4-byte RIFF header fields, producing a corrupt WAV.

  **Change 1:** Change the field type:
  ```kotlin
  private var dataBytesWritten: Long = 0L
  ```

  **Change 2:** Update `writeBytes` to use `Long` arithmetic:
  ```kotlin
  fun writeBytes(buf: ByteArray, off: Int, len: Int) {
      out!!.write(buf, off, len)
      dataBytesWritten += len
  }
  ```

  **Change 3:** Add boundary check at the start of `close()` before calling `patchHeader()`:
  ```kotlin
  fun close() {
      out?.flush()
      out?.close()
      out = null
      // RIFF chunk size is 4 bytes: max data = 0xFFFFFFFF − 36 (fmt+data headers)
      require(dataBytesWritten <= 0xFFFF_FFFFL - 36L) {
          "WAV data exceeds 4 GiB RIFF limit (dataBytesWritten=$dataBytesWritten)"
      }
      patchHeader()
  }
  ```

  **Change 4:** Update `patchHeader()` to handle `Long`. The two calls that write the size fields are:
  - `raf.writeIntLe(dataBytesWritten + 36)` — chunk size
  - `raf.writeIntLe(dataBytesWritten)` — data size

  Since both values are now `Long` but bounded by the check above to fit in 4 bytes, cast to `Int` after the check:

  ```kotlin
  private fun patchHeader() {
      val byteRate = sampleRate * channels * bitsPerSample / 8
      val blockAlign = (channels * bitsPerSample / 8).toShort()
      RandomAccessFile(file, "rw").use { raf ->
          raf.seek(0)
          raf.write("RIFF".toByteArray(Charsets.US_ASCII))
          raf.writeIntLe((dataBytesWritten + 36L).toInt())
          raf.write("WAVE".toByteArray(Charsets.US_ASCII))
          raf.write("fmt ".toByteArray(Charsets.US_ASCII))
          raf.writeIntLe(16)
          raf.writeShortLe(1)
          raf.writeShortLe(channels.toShort())
          raf.writeIntLe(sampleRate)
          raf.writeIntLe(byteRate)
          raf.writeShortLe(blockAlign)
          raf.writeShortLe(bitsPerSample.toShort())
          raf.write("data".toByteArray(Charsets.US_ASCII))
          raf.writeIntLe(dataBytesWritten.toInt())
      }
  }
  ```

---

- [ ] **Step 5 — Fix `WavTrimmer.kt`: `leU32` returns `Long`, `msToSamples` uses `Long` arithmetic**

  File: `app/src/main/java/com/sound2inat/inat/WavTrimmer.kt`

  **Bug 1:** `leU32` returns `Int` — the sign bit makes values > 2 GiB negative, causing `NegativeArraySizeException` or silently reading the wrong data size.

  **Bug 2:** `msToSamples` uses `Int` arithmetic: `(ms * sampleRate) / MS_PER_SECOND` overflows at ~12.4 hours at 48 kHz.

  Replace `leU32`:

  ```kotlin
  private fun leU32(buf: ByteArray, o: Int): Long =
      ((buf[o].toLong() and 0xFF)) or
          ((buf[o + 1].toLong() and 0xFF) shl 8) or
          ((buf[o + 2].toLong() and 0xFF) shl 16) or
          ((buf[o + 3].toLong() and 0xFF) shl 24)
  ```

  Update the call site in `trimMono16` — `dataBytes` is now `Long`:

  ```kotlin
  val dataBytes: Long = leU32(header, OFFSET_DATA_SIZE)
  val totalSamples: Long = dataBytes / BYTES_PER_SAMPLE
  ```

  Replace `msToSamples`:

  ```kotlin
  private fun msToSamples(ms: Long, sampleRate: Long): Long =
      (ms * sampleRate) / MS_PER_SECOND
  ```

  Update `sampleRate` usages: `leU32(header, OFFSET_SAMPLE_RATE)` now returns `Long`. Store it as `val sampleRate: Long = leU32(header, OFFSET_SAMPLE_RATE)`.

  Update the `startSample` / `endSample` calculations to use `Long`:

  ```kotlin
  val startSample: Long = msToSamples(startMs.coerceAtLeast(0L), sampleRate).coerceAtMost(totalSamples)
  val endSample: Long = msToSamples(endMs, sampleRate).coerceAtMost(totalSamples)
  ```

  Update `raf.seek(...)`:

  ```kotlin
  raf.seek(HEADER_SIZE.toLong() + startSample * BYTES_PER_SAMPLE)
  val sliceLen: Long = endSample - startSample
  val raw = ByteArray(sliceLen.toInt()) // safe: file must fit in memory for trimming
  ```

  Update `ShortArray(sliceLen.toInt())` and the loop accordingly.

  Update the `WavWriter` call — `sampleRate` is `Long`, must be `Int` for the writer:

  ```kotlin
  val writer = WavWriter(dstFile, sampleRate.toInt(), channels = 1, bitsPerSample = BITS_PCM16)
  ```

  Update the error message `totalSamples * 1000L / sampleRate`:

  ```kotlin
  "Trim range $startMs..$endMs ms collapses to empty (file is ${totalSamples * 1000L / sampleRate} ms)"
  ```

---

- [ ] **Step 6 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 7 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/storage/DraftRepository.kt \
    app/src/main/java/com/sound2inat/storage/DetectionDao.kt \
    app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt \
    app/src/main/java/com/sound2inat/recorder/WavWriter.kt \
    app/src/main/java/com/sound2inat/inat/WavTrimmer.kt
  git commit -m "fix(data/audio): eliminate mergeAndPersist deadlock; check renameTo; Long WAV sizes"
  ```

---

## Phase 2 — MAJOR

### Task 4 — MAJOR: Inference pipeline — deduplication and ownership fixes

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 4

**Prerequisite:** Task 1 must be complete (YamNetGate has `close()`).

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/YamNetGateResult.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`

---

- [ ] **Step 1 — Add `suppressesPredictions` to `YamNetGateResult.kt`**

  File: `app/src/main/java/com/sound2inat/inference/YamNetGateResult.kt`

  **Reason:** The soft-gate check (`gateResult?.recommendation == DOWNRANK && predictions.none { it.confidence >= 0.7f }`) is copy-pasted in `InferenceRunner.runSequential`, `InferenceRunner.runParallel`, and `LiveInferenceEngine.runWindow`. One constant `HIGH_CONFIDENCE_OVERRIDE = 0.7f` is duplicated in two companion objects.

  Append to the file (after the existing `data class YamNetGateResult`):

  ```kotlin
  /** Threshold above which a high-confidence prediction overrides a DOWNRANK gate decision. */
  const val GATE_HIGH_CONFIDENCE_OVERRIDE = 0.7f

  /**
   * Returns true when this gate result should suppress [predictions] from reaching
   * the aggregator — i.e. the recommendation is DOWNRANK and no prediction clears
   * [GATE_HIGH_CONFIDENCE_OVERRIDE].
   *
   * Callers pass `this` as the gate result (null = fail-open = never suppress).
   */
  fun YamNetGateResult?.suppressesPredictions(predictions: List<WindowPrediction>): Boolean =
      this?.recommendation == GateRecommendation.DOWNRANK &&
          predictions.none { it.confidence >= GATE_HIGH_CONFIDENCE_OVERRIDE }
  ```

---

- [ ] **Step 2 — Replace duplicated soft-gate block in `InferenceRunner.kt`**

  File: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`

  In `runSequential` (around line 125–127):

  Replace:
  ```kotlin
  if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
      if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
  }
  ```

  With:
  ```kotlin
  if (!hardGate && gateResult.suppressesPredictions(predictions)) continue
  ```

  In `runParallel` (around line 178–180):

  Replace:
  ```kotlin
  if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
      if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
  }
  ```

  With:
  ```kotlin
  if (!hardGate && gateResult.suppressesPredictions(predictions)) continue
  ```

  Remove the `HIGH_CONFIDENCE_OVERRIDE` constant from `InferenceRunner`'s `companion object`.

  Also fix `WavReader.readLeUint32` to return `Long` (bug: returns `Int` — `NegativeArraySizeException` for WAV > 2 GiB). Replace the method:

  ```kotlin
  private fun readLeUint32(buf: ByteArray, o: Int): Long =
      (buf[o].toLong() and 0xFF) or
          ((buf[o + 1].toLong() and 0xFF) shl 8) or
          ((buf[o + 2].toLong() and 0xFF) shl 16) or
          ((buf[o + 3].toLong() and 0xFF) shl 24)
  ```

  Update the call site in `readMono16`:

  ```kotlin
  val dataSize: Long = readLeUint32(header, 40)
  require(dataSize in 0L..Int.MAX_VALUE.toLong()) {
      "WAV dataSize out of safe range: $dataSize bytes"
  }
  val raw = ByteArray(dataSize.toInt())
  ```

---

- [ ] **Step 3 — Replace duplicated soft-gate in `LiveInferenceEngine.kt`**

  File: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`

  In `runWindow` (around line 224–226):

  Replace:
  ```kotlin
  if (gateResult?.recommendation == GateRecommendation.DOWNRANK) {
      if (preds.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) return
  }
  ```

  With:
  ```kotlin
  if (gateResult.suppressesPredictions(preds)) return
  ```

  Remove the `HIGH_CONFIDENCE_OVERRIDE` constant from the `companion object` of `LiveInferenceEngine`.

  Add import at the top of the file:
  ```kotlin
  import com.sound2inat.inference.suppressesPredictions
  ```

---

- [ ] **Step 4 — Add `load()` guard in `BirdNetTfliteModel.kt` and `PerchTfliteModel.kt`**

  **Bug:** Calling `load()` a second time without `close()` leaks the previous interpreter.

  In `BirdNetTfliteModel.kt`, at the top of `override suspend fun load(...)`:

  ```kotlin
  override suspend fun load(modelFile: File, labelsFile: File) {
      interp?.close()
      interp = null
      interp = factory.create(modelFile, threads)
      labels = Labels.load(labelsFile)
  }
  ```

  In `PerchTfliteModel.kt`, at the top of `override suspend fun load(...)`:

  ```kotlin
  override suspend fun load(modelFile: File, labelsFile: File) {
      interp?.close()
      interp = null
      val i = factory.create(modelFile, threads)
      interp = i
      labels = Labels.load(labelsFile, format = LabelsFormat.PerchScientificName)
      logitsTensorIndex = (0 until i.outputTensorCount)
          .firstOrNull { idx ->
              val shape = i.getOutputShape(idx)
              shape.size == 2 && shape[0] == 1 && shape[1] == labels.size
          }
          ?: error(
              "Perch model has no output tensor matching [1, ${labels.size}]. " +
                  "Found shapes: " +
                  (0 until i.outputTensorCount).joinToString { idx ->
                      i.getOutputShape(idx).toList().toString()
                  },
          )
  }
  ```

---

- [ ] **Step 5 — Fix `InferenceUseCase.kt`: Perch ownership — never pass original to `InferenceRunner`**

  File: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`

  **Bug:** In `ProductionPerchAnalysisJob.run()`, the original `perch` singleton from DI is added to `instances`:
  ```kotlin
  val instances = buildList {
      add(perch)  // BUG: passes DI-singleton as owned model
      repeat(parallelism - 1) { add(perch.newInstance()) }
  }
  ```
  `InferenceRunner` will call `close()` on every model it receives — including the DI singleton — making it unusable for subsequent analyses.

  Replace with:

  ```kotlin
  val instances = buildList {
      repeat(parallelism) { add(perch.newInstance()) }
  }
  ```

  This creates `parallelism` fresh instances every run. For `parallelism=1` (the default), it creates exactly one new instance — slightly more allocations than before but correct. The DI-injected `perch` remains open for the lifetime of the app.

---

- [ ] **Step 6 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 7 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/inference/YamNetGateResult.kt \
    app/src/main/java/com/sound2inat/inference/InferenceRunner.kt \
    app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt \
    app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt \
    app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt \
    app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt
  git commit -m "refactor(inference): dedup soft-gate logic; fix load() guard; fix perch ownership"
  ```

---

### Task 5 — MAJOR: UI — targeted fixes

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 5

**Prerequisite:** None (independent from other tasks). Do NOT modify `SettingsViewModelHilt` or `RecordingViewModelHilt` — that's Task 6.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/theme/Theme.kt` (or `Color.kt`)
- Create: `app/src/main/java/com/sound2inat/app/ui/UiUtils.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`

---

- [ ] **Step 1 — `WaveformAndSpectrogram.kt`: `collectAsState` → `collectAsStateWithLifecycle`**

  Find the file at `app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt`.

  Find the line (around line 44) that calls `collectAsState()` on a flow from `MediaPlayer`:

  ```kotlin
  val position by vm.player.position.collectAsState()
  ```

  Replace with:

  ```kotlin
  val position by vm.player.position.collectAsStateWithLifecycle()
  ```

  Add the import if not present:
  ```kotlin
  import androidx.lifecycle.compose.collectAsStateWithLifecycle
  ```

  Remove the unused `collectAsState` import if it's no longer needed in this file.

---

- [ ] **Step 2 — `SettingsViewModel.kt`: consolidate 13 separate `scope.launch` collectors into one**

  File: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`

  **Bug:** The `init` block launches 13 independent coroutines each doing `_state.value = _state.value.copy(...)`. This causes up to 13 recompositions per startup and uses the non-atomic read-modify-write pattern.

  Replace the entire `init` block (all the `scope.launch { flow.collect { ... } }` calls for the settings flows — not the `for (d in descriptors)` loop which must stay separate since it calls suspend `resolveState`):

  ```kotlin
  init {
      // Model install states require async resolveState() — stay separate.
      for (d in descriptors) {
          scope.launch {
              val st = resolveState(d)
              updateSection(d.id) { it.copy(install = st) }
          }
      }

      // All simple settings flows merged into one coroutine → one state update per combined emission.
      scope.launch {
          kotlinx.coroutines.flow.combine(
              minConfFlow,
              inatTokenFlow,
              inatLoginFlow,
              regionalFilterEnabledFlow,
              regionRadiusKmFlow,
          ) { minConf, inatToken, inatLogin, regionalFilter, regionRadius ->
              Partial1(minConf, inatToken, inatLogin, regionalFilter, regionRadius)
          }.collect { p1 ->
              _state.update { s ->
                  s.copy(
                      minConfidenceDisplay = p1.minConf,
                      inatTokenPresent = p1.inatToken != null,
                      inatLogin = p1.inatLogin,
                      regionalFilterEnabled = p1.regionalFilter,
                      regionRadiusKm = p1.regionRadius,
                  )
              }
          }
      }

      scope.launch {
          kotlinx.coroutines.flow.combine(
              minWindowsFlow,
              yamNetGateEnabledFlow,
              birdNetMetaEnabledFlow,
              allowDeleteUploadedFlow,
              themeModeFlow,
          ) { minWin, yamNet, birdNetMeta, allowDelete, theme ->
              Partial2(minWin, yamNet, birdNetMeta, allowDelete, theme)
          }.collect { p2 ->
              _state.update { s ->
                  s.copy(
                      minWindows = p2.minWin,
                      yamNetGateEnabled = p2.yamNet,
                      birdNetMetaEnabled = p2.birdNetMeta,
                      allowDeleteUploaded = p2.allowDelete,
                      themeMode = p2.theme,
                  )
              }
          }
      }

      scope.launch {
          kotlinx.coroutines.flow.combine(
              audioSourceRawFlow,
              normalizeAudioFlow,
          ) { audioRaw, normalize ->
              audioRaw to normalize
          }.collect { (audioRaw, normalize) ->
              _state.update { s ->
                  s.copy(audioSourceRaw = audioRaw, normalizeAudio = normalize)
              }
          }
      }
  }

  // Local data classes to avoid 5-parameter combine type erasure.
  private data class Partial1(
      val minConf: Float,
      val inatToken: String?,
      val inatLogin: String?,
      val regionalFilter: Boolean,
      val regionRadius: Int,
  )
  private data class Partial2(
      val minWin: Int,
      val yamNet: Boolean,
      val birdNetMeta: Boolean,
      val allowDelete: Boolean,
      val theme: com.sound2inat.app.data.ThemeMode,
  )
  ```

  Also replace any remaining `_state.value = _state.value.copy(...)` in `SettingsViewModel` (outside init) with `_state.update { it.copy(...) }`. Check methods like `onLoginCaptured`, `updateSection`, `signOut`.

---

- [ ] **Step 3 — `ReviewScreen.kt`: add `beyondViewportPageCount = 1` to `HorizontalPager`**

  File: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

  Find the `HorizontalPager(` call (around line 133). Add `beyondViewportPageCount = 1`:

  ```kotlin
  HorizontalPager(
      state = pagerState,
      key = { idx -> draftIds.getOrElse(idx) { idx.toString() } },
      beyondViewportPageCount = 1,
      modifier = Modifier.fillMaxSize(),
  ) { pageIndex ->
  ```

  **Reason:** Without this, Compose destroys the adjacent page when the user starts swiping. The `player.release()` in `DisposableEffect` fires mid-animation, causing an audible glitch and a brief flash of "no audio" state.

---

- [ ] **Step 4 — Add `iNatGreen` constant and `UiUtils.kt`**

  **Step 4a:** Find the color/theme file. Check `app/src/main/java/com/sound2inat/app/ui/theme/` for `Color.kt` or `Theme.kt`. Add the shared constant:

  In `Color.kt` (or `Theme.kt` where other color constants live):
  ```kotlin
  /** iNaturalist brand green — used consistently across Home, Review, and SpeciesDetails. */
  val iNatGreen = Color(0xFF74AC00)
  ```

  **Step 4b:** Create `app/src/main/java/com/sound2inat/app/ui/UiUtils.kt`:

  ```kotlin
  package com.sound2inat.app.ui

  /** Formats a duration in milliseconds as `m:ss`. */
  fun formatDurationMs(ms: Long): String =
      "%d:%02d".format(ms / 60_000L, ms / 1_000L % 60L)
  ```

  **Step 4c:** In `HomeScreen.kt`, `ReviewScreen.kt`, and `SpeciesDetailsSheet.kt` (find these files), replace the 3 hardcoded `Color(0xFF74AC00)` literals with `iNatGreen` (with the appropriate import), and replace the 4 duplicate duration-format lambda/function bodies with `formatDurationMs(ms)`.

---

- [ ] **Step 5 — Fix `RecordingViewModel.kt`: reset `hasSeenRecording` before starting**

  File: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`

  **Bug:** `hasSeenRecording = false` is never reset between recording sessions. On the second navigation to `RecordingScreen`, the field is `true` from the previous session. The guard `if (!hasSeenRecording && uiState is RecordingUiState.Done) RecordingUiState.Idle` never fires, so a stale `Done(prevDraftId)` state might slip through if the user navigates back and starts again before the controller resets.

  Find the `startRecording()` function (it calls `launcher.start(...)` or `controller.start(...)`). Add the reset immediately before the call:

  ```kotlin
  fun startRecording() {
      hasSeenRecording = false  // reset before starting so the Done guard works for this session
      viewModelScope.launch {
          // ... existing permission check and launcher.start() call
      }
  }
  ```

  If there's no `startRecording()` function and recording is started via a different mechanism, find where `launcher.start(...)` is called and add `hasSeenRecording = false` immediately before that call.

---

- [ ] **Step 6 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 7 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt \
    app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt \
    app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt \
    app/src/main/java/com/sound2inat/app/ui/theme/ \
    app/src/main/java/com/sound2inat/app/ui/UiUtils.kt \
    app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt \
    app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt
  git commit -m "fix(ui): lifecycle-aware collection; combine settings flows; dedup colors/utils; hasSeenRecording reset"
  ```

---

### Task 6 — MAJOR: ViewModel refactor — eliminate double-VM pattern

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 6

**Prerequisite:** Task 5 must be complete (SettingsViewModel changes land first).

**Background for the agent:** Currently each screen has two classes:
- `HomeViewModel` — testable, receives dependencies as lambdas/interfaces
- `HomeViewModelHilt` — `@HiltViewModel`, delegates to `HomeViewModel`

Both live in the same `.kt` file. The goal is to merge them: fold all Hilt-injectable dependencies into the main class with `@HiltViewModel @Inject constructor`, delete the wrapper class. Screens use `hiltViewModel<HomeViewModel>()` directly.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/radar/RadarViewModel.kt`
- Modify corresponding Screen files

---

- [ ] **Step 1 — Read all four ViewModel files and corresponding Screen files**

  Read in order:
  1. `HomeViewModel.kt` — find `HomeViewModel` (lambda-based) + `HomeViewModelHilt` (`@HiltViewModel` delegate)
  2. `SettingsViewModel.kt` — find `SettingsViewModel` + `SettingsViewModelHilt`
  3. `RecordingViewModel.kt` — find `RecordingViewModel` + `RecordingViewModelHilt`
  4. `RadarViewModel.kt` — find `RadarViewModel` + `RadarViewModelHilt` (if present)

  Also read:
  5. `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt` — find `hiltViewModel()` call for `HomeViewModelHilt`
  6. `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt`
  7. `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`

---

- [ ] **Step 2 — Merge `HomeViewModel` + `HomeViewModelHilt`**

  In `HomeViewModel.kt`:

  1. Delete the `class HomeViewModel(...)` declaration and move all its logic and state into `HomeViewModelHilt`.
  2. Rename `HomeViewModelHilt` → `HomeViewModel`.
  3. Replace lambda parameters (`observeDrafts: () -> Flow<...>`, `topLabelFor`, `isModelReady`) with direct injected dependencies (`repo: DraftRepository`, `modelManager: ModelManager`, etc.) from what `HomeViewModelHilt` currently injects.
  4. Inline the `delegate.state`, `delegate.refreshModelState()` calls — remove the `delegate` field.

  The resulting class:

  ```kotlin
  @HiltViewModel
  class HomeViewModel @Inject constructor(
      private val repo: DraftRepository,
      private val detectionDao: DetectionDao,
      private val inatObservationDao: InatObservationDao,
      private val modelManager: ModelManager,
      private val taxonPhotoRepository: TaxonPhotoRepository,
      private val settings: Settings,
      private val inferenceQueue: InferenceQueue,
  ) : ViewModel() {

      private val readyState = MutableStateFlow(false)

      init { refreshModelState() }

      fun refreshModelState() {
          viewModelScope.launch { readyState.value = modelManager.stateFor(BirdNetV24.descriptor) is ModelInstallState.Ready }
      }

      val state: StateFlow<HomeUiState> = combine(repo.observeAll(), readyState) { drafts, ready ->
          HomeUiState(
              isModelReady = ready,
              drafts = drafts.map { d ->
                  DraftSummary(id = d.id, recordedAtUtcMs = d.recordedAtUtcMs, durationMs = d.durationMs, status = d.status, topLabel = null)
              },
          )
      }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

      // ... all enrichedDrafts, filteredDrafts, selectedIds, etc. from HomeViewModelHilt
  }
  ```

  Update `HomeScreen.kt`: change `hiltViewModel<HomeViewModelHilt>()` → `hiltViewModel<HomeViewModel>()`.

---

- [ ] **Step 3 — Merge `SettingsViewModel` + `SettingsViewModelHilt`**

  Same pattern as Step 2.

  `SettingsViewModelHilt` injects `ModelManager`, `Settings`, `INatAuthRepository`. These are currently wired via lambdas in `SettingsViewModel`'s constructor.

  The merged class:

  ```kotlin
  @HiltViewModel
  class SettingsViewModel @Inject constructor(
      private val modelManager: ModelManager,
      private val settings: Settings,
      private val inatAuth: INatAuthRepository,
  ) : ViewModel() {
      // Move all state/functions from the old SettingsViewModel directly here.
      // Replace every lambda call with the concrete dependency:
      //   installModel(d, emit) → modelManager.install(d, emit)
      //   removeModel(d)        → modelManager.remove(d)
      //   resolveState(d)       → modelManager.stateFor(d)
      //   minConfFlow           → settings.minConfidenceDisplay
      //   writeMinConf(v)       → settings.setMinConfidenceDisplay(v)
      //   inatTokenFlow         → inatAuth.tokenState
      //   inatLoginFlow         → inatAuth.loginState
      //   acceptInatToken(t)    → inatAuth.acceptCapturedToken(t)
      //   signOutInat()         → inatAuth.logout()
      //   ... etc.
      //
      // The descriptors list: replace `descriptors: List<ModelDescriptor>` with
      // the constant `KnownModels` from `com.sound2inat.modelmanager.KnownModels`.
      //
      // externalScope: remove the parameter — use viewModelScope directly.
      // private val scope = viewModelScope  (no longer optional)
  }
  ```

  Delete `SettingsViewModelHilt`. Update `SettingsScreen.kt` to use `hiltViewModel<SettingsViewModel>()`.

---

- [ ] **Step 4 — Merge `RadarViewModel` + `RadarViewModelHilt`**

  Read `RadarViewModel.kt` fully. `RadarViewModelHilt` likely injects `Settings`, `INatAuthRepository`, `INatObservationsRepository`, `LocationProvider`.

  Same merge pattern: inline the dependency wiring, remove lambda parameters, delete the Hilt wrapper class, update `RadarScreen.kt`.

---

- [ ] **Step 5 — Merge `RecordingViewModel` + `RecordingViewModelHilt`**

  `RecordingViewModel` requires `PermissionsController` which comes from Android `CompositionLocal` (not from Hilt graph). It cannot be injected via `@HiltViewModel` constructor directly.

  **Approach from spec:** add a `initWithPermissions(perms: PermissionsController)` method, call it from `RecordingScreen` via `LaunchedEffect(Unit)`.

  The merged class:

  ```kotlin
  @HiltViewModel
  class RecordingViewModel @Inject constructor(
      private val controller: RecordingController,
      private val launcher: RecordingServiceLauncher,
      @ApplicationContext private val appContext: Context,
      private val photoDao: DraftPhotoDao,
      private val photoStore: PhotoFileStore,
  ) : ViewModel() {

      private var perms: PermissionsController? = null

      fun initWithPermissions(perms: PermissionsController) {
          if (this.perms == null) this.perms = perms
      }

      // Replace all `perms.xxx` usages with `perms!!.xxx` (or check != null first).
      // All existing RecordingViewModel methods remain identical, just using
      // the field `perms` instead of a constructor parameter.
  }
  ```

  In `RecordingScreen.kt`, find the `hiltViewModel()` call that currently creates `RecordingViewModelHilt`. Replace with:

  ```kotlin
  val hiltVm: RecordingViewModel = hiltViewModel()
  val perms = LocalPermissionsController.current  // or however PermissionsController is accessed
  LaunchedEffect(Unit) { hiltVm.initWithPermissions(perms) }
  ```

  Remove the `RecordingViewModelHilt` class and any factory-based creation in the Screen.

---

- [ ] **Step 6 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

  Tests for these VMs use lambda-injection fakes. After the merge, update test constructors to pass `FakeXxx` implementations that previously went to the lambda params, now need to go to concrete params. Check all test files under `app/src/test/java/com/sound2inat/app/ui/`.

---

- [ ] **Step 7 — Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/ui/
  git commit -m "refactor(ui): merge double-ViewModel pattern into single @HiltViewModel per screen"
  ```

---

### Task 7 — MAJOR: Data layer — transaction safety, status guards, schema cleanup

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 7

**Prerequisite:** Task 3 must be complete (DraftRepository already fixed for the `.first()` deadlock).

**Files:**
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/DraftDao.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/DraftEntity.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/Sound2iNatDb.kt`
- Test: `app/src/androidTest/java/com/sound2inat/storage/MigrationTest.kt`

---

- [ ] **Step 1 — Read all five files**

  Read in order:
  1. `DraftRepository.kt`
  2. `DraftDao.kt`
  3. `DraftEntity.kt`
  4. `Sound2iNatDb.kt`
  5. `MigrationTest.kt` (under `androidTest`)

---

- [ ] **Step 2 — Add `updateStatusConditional` to `DraftDao.kt`**

  **Bug:** `updateStatus(id, status)` has no precondition — a racing call can move a draft `UPLOADED → PENDING_INFERENCE`.

  Add to `DraftDao`:

  ```kotlin
  @Query(
      "UPDATE drafts SET status = :newStatus " +
      "WHERE id = :id AND status = :expectedStatus"
  )
  fun updateStatusConditional(id: String, newStatus: DraftStatus, expectedStatus: DraftStatus): Int
  ```

  In `DraftRepository`, find all callers of the existing `drafts.update(entity.copy(status = ...))` pattern and where the status transition is safety-critical (e.g. `PENDING_INFERENCE → PENDING_REVIEW`), replace with:

  ```kotlin
  val affected = drafts.updateStatusConditional(draftId, DraftStatus.PENDING_REVIEW, DraftStatus.PENDING_INFERENCE)
  check(affected > 0) { "Status transition failed for draft $draftId — unexpected current status" }
  ```

  Keep `drafts.update(current.copy(...))` for the full entity update in `attachDetections` (which already reads-then-writes inside `runInTransaction`).

---

- [ ] **Step 3 — Move `mergeBySpecies` + write into `runInTransaction`**

  File: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`

  **Bug:** The read (`listForDraft`) + `mergeBySpecies` + write (`attachDetections`) happens across two separate calls, only the write is in a transaction. Another coroutine can insert detections between read and write.

  In `mergeAndPersist`, after acquiring `persistMutex.withLock`, wrap the whole body in `withContext(ioDispatcher)` and then `runInTransaction`:

  ```kotlin
  suspend fun mergeAndPersist(...) = persistMutex.withLock {
      withContext(ioDispatcher) {
          runInTransaction {
              val draft = drafts.getById(draftId)
                  ?: error("draft $draftId missing in mergeAndPersist")
              val detectionList = detections.listForDraft(draftId)
              val existing = detectionList.map { e -> /* same mapping as before */ }
              val merged = mergeBySpecies(existing, freshDetections)
              val combinedModelId = /* same logic as before */
              val combinedVersion = /* same logic as before */
              // Inline the writes directly (don't call attachDetections — it wraps in runInTransaction too)
              val now = nowMs()
              val finalStatus = if (promoteToReviewed && merged.isNotEmpty()) DraftStatus.REVIEWED else DraftStatus.PENDING_REVIEW
              drafts.update(
                  draft.copy(
                      status = finalStatus,
                      modelId = combinedModelId,
                      modelVersion = combinedVersion.trim('+'),
                      updatedAtUtcMs = now,
                  )
              )
              detections.deleteForDraft(draftId)
              detections.insertAll(merged.map { it.toEntity(draftId) })
          }
      }
  }
  ```

  Extract the `AggregatedDetection.toEntity(draftId)` mapping (see Step 5).

---

- [ ] **Step 4 — Add logging to `DraftRepository.delete`**

  In `delete(draftId)`, the `files.deleteAllFor(draftId)` call returns silently. Find the `File.delete()` calls (directly or inside `WavFileStore.deleteAllFor`) and add logging.

  In `DraftRepository.delete`:

  ```kotlin
  suspend fun delete(draftId: String) = withContext(ioDispatcher) {
      drafts.deleteById(draftId)
      val deleted = files.deleteAllFor(draftId)
      if (!deleted) android.util.Log.w(TAG, "WAV file deletion failed for draft $draftId")
      photoStore?.deletePhotosFor(draftId)
  }
  ```

  If `WavFileStore.deleteAllFor` already returns `Boolean`, use it. If it returns `Unit`, change its return type to `Boolean` and propagate the `File.delete()` result.

  Add `private companion object { const val TAG = "DraftRepository" }` if not present.

---

- [ ] **Step 5 — Extract `AggregatedDetection.toEntity` mapping**

  File: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`

  **Duplication:** The 24-line `DetectionEntity(...)` constructor is copy-pasted in `createWithDetections` and `attachDetections`. Extract:

  ```kotlin
  private fun AggregatedDetection.toEntity(draftId: String): DetectionEntity = DetectionEntity(
      draftId = draftId,
      taxonScientificName = taxonScientificName,
      taxonCommonName = taxonCommonName,
      maxConfidence = maxConfidence,
      detectedWindows = detectedWindows,
      firstSeenMs = firstSeenMs,
      lastSeenMs = lastSeenMs,
      isSelectedByUser = false,
      sources = SourceStats.encode(
          confidenceBySource.mapValues { (src, conf) ->
              SourceStat(
                  maxConf = conf,
                  windows = windowsBySource[src] ?: 0,
                  firstSeenMs = firstSeenBySource[src] ?: firstSeenMs,
                  lastSeenMs = lastSeenBySource[src] ?: lastSeenMs,
              )
          }
      ),
      fragmentRanges = FragmentRanges.encode(fragmentRanges),
      aggregatedConfidence = aggregatedConfidence,
  )
  ```

  Replace the two duplicate `detections.map { DetectionEntity(...) }` blocks with `items.map { it.toEntity(draftId) }`.

---

- [ ] **Step 6 — Add migration v7 to `Sound2iNatDb.kt` and update `DraftEntity.kt`**

  **Background:** `DraftEntity` has `inatObservationId: Long?` and `inatObservationUrl: String?` that were superseded by the `inat_observations` table in migration v2→v3. They are orphaned columns. SQLite on API 28 (minSdk) does not support `DROP COLUMN` — we must recreate the table.

  **Step 6a:** In `DraftEntity.kt`, remove the two fields:

  ```kotlin
  // Remove these two lines:
  val inatObservationId: Long? = null,
  val inatObservationUrl: String? = null,
  ```

  Keep `inatLastError: String? = null`.

  **Step 6b:** In `Sound2iNatDb.kt`:

  1. Change `version = 6` → `version = 7`
  2. Add `MIGRATION_6_7` to the companion object:

  ```kotlin
  val MIGRATION_6_7: Migration = object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
          // SQLite on API 28 (minSdk) does not support DROP COLUMN.
          // Recreate the table without the deprecated inatObservationId/inatObservationUrl columns.
          db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS drafts_new (
                  id TEXT NOT NULL PRIMARY KEY,
                  audioPath TEXT NOT NULL,
                  recordedAtUtcMs INTEGER NOT NULL,
                  durationMs INTEGER NOT NULL,
                  latitude REAL,
                  longitude REAL,
                  locationAccuracyMeters REAL,
                  status TEXT NOT NULL,
                  modelId TEXT,
                  modelVersion TEXT,
                  createdAtUtcMs INTEGER NOT NULL,
                  updatedAtUtcMs INTEGER NOT NULL,
                  inatLastError TEXT
              )
              """.trimIndent(),
          )
          db.execSQL(
              """
              INSERT INTO drafts_new
              SELECT id, audioPath, recordedAtUtcMs, durationMs, latitude, longitude,
                     locationAccuracyMeters, status, modelId, modelVersion,
                     createdAtUtcMs, updatedAtUtcMs, inatLastError
              FROM drafts
              """.trimIndent(),
          )
          db.execSQL("DROP TABLE drafts")
          db.execSQL("ALTER TABLE drafts_new RENAME TO drafts")
      }
  }
  ```

  3. Register `MIGRATION_6_7` in the `Room.databaseBuilder` call in `AppModule.kt`:

  ```kotlin
  .addMigrations(
      Sound2iNatDb.MIGRATION_1_2,
      Sound2iNatDb.MIGRATION_2_3,
      Sound2iNatDb.MIGRATION_3_4,
      Sound2iNatDb.MIGRATION_4_5,
      Sound2iNatDb.MIGRATION_5_6,
      Sound2iNatDb.MIGRATION_6_7,
  )
  ```

  Find `AppModule.kt` (it's under `app/src/main/java/com/sound2inat/app/di/`) and add the migration there.

---

- [ ] **Step 7 — Export the new Room schema and add migration test**

  Run the build once to let Room's annotation processor export the schema JSON:

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

  Check `app/schemas/com.sound2inat.storage.Sound2iNatDb/7.json` is created.

  In `MigrationTest.kt`, add a v6→v7 test following the same pattern as existing migration tests:

  ```kotlin
  @Test
  fun migrate6to7() {
      helper.createDatabase(TEST_DB, 6).apply {
          // Insert a draft row with the old schema (including inatObservationId, inatObservationUrl)
          execSQL(
              "INSERT INTO drafts VALUES ('d1', '/audio/1.wav', 1000, 5000, null, null, null, " +
              "'PENDING_REVIEW', null, null, 1000, 1000, 42, 'https://inat.org/obs/42', null)"
          )
          close()
      }
      val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, Sound2iNatDb.MIGRATION_6_7)
      // Verify the row survived
      val cursor = db.query("SELECT id, audioPath, inatLastError FROM drafts WHERE id = 'd1'")
      assertTrue(cursor.moveToFirst())
      assertEquals("d1", cursor.getString(0))
      assertEquals("/audio/1.wav", cursor.getString(1))
      assertNull(cursor.getString(2))
      cursor.close()
      db.close()
  }
  ```

---

- [ ] **Step 8 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 9 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/storage/ \
    app/src/main/java/com/sound2inat/app/di/ \
    app/schemas/ \
    app/src/androidTest/java/com/sound2inat/storage/MigrationTest.kt
  git commit -m "fix(data): transaction safety; conditional status update; migration v7 drops orphaned columns"
  ```

---

### Task 8 — MAJOR: Audio + iNat — recorder safety, auth, retry

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 8

**Files:**
- Modify: `app/src/main/java/com/sound2inat/recorder/Recorder.kt`
- Modify: `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt`
- Modify: `app/src/main/java/com/sound2inat/recorder/WavWriter.kt`
- Modify: `app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt`
- Modify: `app/src/main/java/com/sound2inat/inat/RegionalStatusRepository.kt`

---

- [ ] **Step 1 — Read all six files**

  Read in order:
  1. `Recorder.kt`
  2. `AndroidAudioRecordSource.kt`
  3. `WavWriter.kt` (already modified in Task 3 — re-read the current state)
  4. `INatAuthRepository.kt`
  5. `INatSubmitter.kt`
  6. `RegionalStatusRepository.kt`

---

- [ ] **Step 2 — Fix `Recorder.kt`: `cancelAndJoin()` + `stop()` before `release()`**

  File: `app/src/main/java/com/sound2inat/recorder/Recorder.kt`

  **Bug 1:** In `DefaultRecorder.stop()`, `job?.cancel()` is called before `writer?.close()`. The pump coroutine may still be writing to the WAV file when `writer.close()` is called — and `writer.close()` itself calls `patchHeader()` which seeks to byte 0. Depending on timing, the pump may write after the header is patched, corrupting the WAV.

  In `stop()`, replace `job?.cancel()` with:

  ```kotlin
  override suspend fun stop(): RecordingResult = withContext(ioDispatcher) {
      source.stop()
      job?.cancelAndJoin()  // wait for pump to finish before closing WAV
      job = null
      writer?.close()
      writer = null
      val durationMs = clock.nowMs() - startMs
      RecordingResult(target!!.absolutePath, durationMs, source.sampleRate, source.channels)
  }
  ```

  Add the missing import:
  ```kotlin
  import kotlinx.coroutines.cancelAndJoin
  ```

  **Bug 2:** In `cancel()`, `source.stop()` is not called before `source.release()` (if `release()` is called). Check the existing `cancel()` implementation and ensure `audioSource.stop()` is called before `release()`.

---

- [ ] **Step 3 — Fix `AndroidAudioRecordSource.kt`: validate `getMinBufferSize` + `STATE_INITIALIZED`**

  File: `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt`

  Find the `AudioRecord.getMinBufferSize(...)` call. Currently it likely uses `coerceAtLeast(MIN_BUFFER)`:

  ```kotlin
  val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
      .coerceAtLeast(SOME_MIN)
  ```

  Replace with a hard check:

  ```kotlin
  val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
  require(minBufSize > 0) {
      "AudioRecord: unsupported configuration (code=$minBufSize). " +
      "sampleRate=$sampleRate channelConfig=$channelConfig encoding=$encoding"
  }
  val bufferSize = minBufSize.coerceAtLeast(MINIMUM_BUFFER_SIZE)
  ```

  Find the MIC fallback `AudioRecord` construction (the fallback that tries `MIC` source after `UNPROCESSED` fails). After creating the fallback `AudioRecord`, add a state check before calling `startRecording()`:

  ```kotlin
  val fallback = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, encoding, bufferSize)
  check(fallback.state == AudioRecord.STATE_INITIALIZED) {
      "AudioRecord MIC fallback failed to initialize (state=${fallback.state})"
  }
  ```

---

- [ ] **Step 4 — Fix `WavWriter.kt`: periodic `patchHeader()` in pump for crash recovery**

  File: `app/src/main/java/com/sound2inat/recorder/WavWriter.kt`

  **Problem:** If the app is force-killed, `close()` is never called and `patchHeader()` never runs — the WAV file has a zeroed header and `WavReader` reads 0 frames.

  **Fix:** Add a counter in `writeBytes` and flush + patch the header every `PATCH_INTERVAL_BYTES`:

  ```kotlin
  private var dataBytesWritten: Long = 0L
  private var bytesSinceLastPatch: Long = 0L

  fun writeBytes(buf: ByteArray, off: Int, len: Int) {
      out!!.write(buf, off, len)
      dataBytesWritten += len
      bytesSinceLastPatch += len
      if (bytesSinceLastPatch >= PATCH_INTERVAL_BYTES) {
          out!!.flush()
          patchHeader()
          bytesSinceLastPatch = 0L
      }
  }

  companion object {
      const val HEADER_SIZE = 44
      // Patch header every ~10 seconds at 48kHz/16-bit/mono (10 * 48000 * 2 = 960000 bytes)
      private const val PATCH_INTERVAL_BYTES = 960_000L
  }
  ```

  `patchHeader()` already uses `RandomAccessFile.seek(0)` — calling it while `out` is still open is safe since `out` is a `BufferedOutputStream` over a `FileOutputStream` on the same file (the RAF opens a separate file descriptor to the same path).

---

- [ ] **Step 5 — Fix `INatAuthRepository.kt`: destroy WebView + refresh mutex**

  File: `app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt`

  **Bug 1:** `onTokenCaptured` calls `cont.resume(token)` without destroying the WebView — the WebView stays alive ~15 seconds after auth completes.

  Find the `onTokenCaptured` callback (around lines 134–158). Add `webView.destroy()` before `cont.resume(token)`:

  ```kotlin
  webView.destroy()
  cont.resume(token)
  ```

  **Bug 2:** Concurrent `401` responses can trigger two simultaneous `refreshToken()` calls. The second uses the already-consumed refresh token, gets `400 invalid_grant`, and triggers a logout.

  Add a `Mutex` + `Deferred` refresh guard. In the class body add:

  ```kotlin
  private val refreshMutex = Mutex()
  private var refreshDeferred: Deferred<String?>? = null
  ```

  In `refreshToken()` (or wherever the token refresh logic lives), wrap with:

  ```kotlin
  suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
      refreshDeferred?.let { return@withLock it.await() }
      val deferred = coroutineScope { async { doRefreshToken() } }
      refreshDeferred = deferred
      try {
          deferred.await()
      } finally {
          refreshDeferred = null
      }
  }
  ```

  Where `doRefreshToken()` is the existing token refresh implementation.

---

- [ ] **Step 6 — Fix `INatSubmitter.kt`: retry on network errors + KDoc for taxonId**

  File: `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt`

  **Fix 1:** Add a KDoc comment to the `taxonId = null` usage in observation creation explaining the intentional two-step model:

  Find the observation creation API call. Add a comment above the `taxonId` field:

  ```kotlin
  // taxonId is intentionally null here: we first create the observation (photo + notes),
  // then add a separate identification for each selected species. This two-step model
  // matches the iNaturalist API contract and allows multiple identifications per observation.
  taxonId = null,
  ```

  **Fix 2:** Wrap network calls in an exponential-backoff retry loop. Add a helper to the file:

  ```kotlin
  private suspend fun <T> withRetry(
      maxAttempts: Int = 3,
      block: suspend () -> T,
  ): T {
      var lastException: Exception? = null
      repeat(maxAttempts) { attempt ->
          try {
              return block()
          } catch (e: java.io.IOException) {
              lastException = e
              if (attempt < maxAttempts - 1) {
                  val delayMs = 1_000L shl attempt // 1s, 2s, 4s
                  kotlinx.coroutines.delay(delayMs)
              }
          }
      }
      throw lastException!!
  }
  ```

  Wrap the multipart audio upload call and observation POST with `withRetry { ... }`. Do NOT retry on 4xx (except 401 which goes through the token refresh).

  For HTTP 5xx, add a check after getting the response:

  ```kotlin
  if (response.code >= 500) throw java.io.IOException("HTTP ${response.code} — server error")
  ```

---

- [ ] **Step 7 — Fix `RegionalStatusRepository.kt`: deduplicate concurrent fetch requests**

  File: `app/src/main/java/com/sound2inat/inat/RegionalStatusRepository.kt`

  **Bug:** Check-then-act on the in-memory cache without a lock — N concurrent callers miss the cache and all fire separate network requests for the same taxon.

  Replace the `HashMap` cache with a `ConcurrentHashMap<String, Deferred<RegionalStatus>>`:

  ```kotlin
  private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<RegionalStatus>>()
  private val cache = java.util.concurrent.ConcurrentHashMap<String, RegionalStatus>()
  ```

  Replace the fetch logic:

  ```kotlin
  suspend fun fetchStatus(taxonId: String, lat: Double, lon: Double): RegionalStatus {
      cache[taxonId]?.let { return it }
      val deferred = inFlight.getOrPut(taxonId) {
          coroutineScope { async { doFetch(taxonId, lat, lon) } }
      }
      return try {
          deferred.await().also {
              cache[taxonId] = it
              inFlight.remove(taxonId)
          }
      } catch (e: Exception) {
          inFlight.remove(taxonId)
          throw e
      }
  }
  ```

  Where `doFetch(...)` is the existing network call.

---

- [ ] **Step 8 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 9 — Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/recorder/ \
    app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt \
    app/src/main/java/com/sound2inat/inat/INatSubmitter.kt \
    app/src/main/java/com/sound2inat/inat/RegionalStatusRepository.kt
  git commit -m "fix(audio/inat): cancelAndJoin in stop(); retry uploads; dedup regional fetch; refresh mutex"
  ```

---

## Phase 3 — MINOR

### Task 9 — MINOR: All small fixes across inference, UI, data, audio, and iNat

**Spec reference:** `docs/superpowers/specs/2026-05-10-code-review-fixes-design.md` Task 9

**Context for the agent:** Read the spec section "Phase 3: MINOR — Task 9" fully before starting. These fixes are independent of each other. Group related ones into sub-commits. Each fix is small; check detekt after every few changes rather than waiting until the end.

**Files (partial list — verify all exist before editing):**
- `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- `app/src/main/java/com/sound2inat/inference/PostRecordingProcessor.kt`
- `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`
- `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- `app/src/main/java/com/sound2inat/modelmanager/ModelInstallState.kt`
- `app/src/androidTest/java/com/sound2inat/storage/MigrationTest.kt`
- `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`
- `app/src/main/java/com/sound2inat/inat/INatObservationsRepository.kt`
- `app/src/main/java/com/sound2inat/location/FusedLocationProvider.kt`
- `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`
- Create: `app/src/main/java/com/sound2inat/inference/AudioConstants.kt`

---

- [ ] **Step 1 — Inference: monotone parallel progress**

  File: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`

  In `runParallel`, each `async` block updates `_progress.value = counter.incrementAndGet().toFloat() / frames`. Because `AtomicInteger.incrementAndGet` is monotone but `MutableStateFlow.value =` is not guarded, a slow worker finishing an early frame can temporarily lower the visible progress.

  Replace all `_progress.value = counter.incrementAndGet().toFloat() / frames` assignments with:

  ```kotlin
  val newProgress = counter.incrementAndGet().toFloat() / frames
  _progress.update { maxOf(it, newProgress) }
  ```

---

- [ ] **Step 2 — Inference: fix Float→Short conversion in `PostRecordingProcessor.kt`**

  File: `app/src/main/java/com/sound2inat/inference/PostRecordingProcessor.kt`

  Find any `(sample * Short.MAX_VALUE).toInt().toShort()` pattern. `Short.MAX_VALUE = 32767`, so `1.0f * 32767 = 32767` (fine), but `-1.0f * 32767 = -32767` (off by one for the negative extreme: `Short.MIN_VALUE = -32768`). More importantly, if audio is unnormalized and any sample exceeds 1.0f, the result overflows silently.

  Replace with:

  ```kotlin
  (sample * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
  ```

---

- [ ] **Step 3 — UI: extract FileProvider authority constant**

  Files: `RecordingViewModel.kt` (line ~109), `ReviewViewModel.kt` (lines ~530, ~549)

  Find the string literal `"com.sound2inat.app.fileprovider"`. Move it to a constant:

  In `app/src/main/java/com/sound2inat/app/` find an appropriate constants file (e.g., `AppConstants.kt`) or add to `UiUtils.kt` (created in Task 5):

  ```kotlin
  val FILE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider"
  ```

  Replace all occurrences of the string literal with `FILE_PROVIDER_AUTHORITY`.

---

- [ ] **Step 4 — UI: `LaunchedEffect(Unit)` → `LaunchedEffect(vm)` in `RecordingScreen.kt`**

  File: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`

  Find `LaunchedEffect(Unit)` near line 76. Replace with `LaunchedEffect(vm)`.

  **Reason:** `LaunchedEffect(Unit)` re-uses the same key across recompositions, so if the VM instance changes (possible if Hilt recreates it), the effect does not re-run. Using `vm` as the key ensures the effect re-runs with the new VM.

---

- [ ] **Step 5 — UI: `SimpleDateFormat` → `DateTimeFormatter` in `HomeScreen.kt`**

  File: `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`

  Around line 508–509, find the `SimpleDateFormat(...)` usage. Replace with `DateTimeFormatter` (available since API 26, minSdk=28):

  ```kotlin
  import java.time.format.DateTimeFormatter
  import java.time.Instant
  import java.time.ZoneId

  val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
      .withZone(ZoneId.systemDefault())
  // Usage:
  formatter.format(Instant.ofEpochMilli(draft.recordedAtUtcMs))
  ```

  Remove the `SimpleDateFormat` import and `Locale` argument.

---

- [ ] **Step 6 — UI: remove dead code in `ReviewScreen.kt`**

  File: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

  Around lines 181–182, find `val uploadedIds = remember(...)`. This variable is declared but never read downstream. Delete the line.

---

- [ ] **Step 7 — Data: `Verifying(val ready: Boolean)` → `data object Verifying`**

  File: `app/src/main/java/com/sound2inat/modelmanager/ModelInstallState.kt`

  Replace:
  ```kotlin
  data class Verifying(val ready: Boolean) : ModelInstallState
  ```
  With:
  ```kotlin
  data object Verifying : ModelInstallState
  ```

  Search for all usages of `ModelInstallState.Verifying(...)` in the codebase (likely `ModelManager.kt`). Currently it's called as `ModelInstallState.Verifying(false)` — replace with `ModelInstallState.Verifying`.

---

- [ ] **Step 8 — Data: `ModelManager.install` dispatcher consistency**

  File: `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`

  Line 41: `withContext(Dispatchers.IO)` — other methods use `withContext(ioDispatcher)`. Replace with `ioDispatcher`:

  ```kotlin
  open suspend fun install(
      descriptor: ModelDescriptor,
      emit: (ModelInstallState) -> Unit,
  ): Unit = withContext(ioDispatcher) {
  ```

---

- [ ] **Step 9 — iNat: fix clock usage in `INatObservationsRepository.kt`**

  File: `app/src/main/java/com/sound2inat/inat/INatObservationsRepository.kt`

  Around line 33, the class has an injected `clock: () -> Long` but some methods call `System.currentTimeMillis()` directly. Find and replace all `System.currentTimeMillis()` calls inside the class with `clock()`.

---

- [ ] **Step 10 — Location: add timeout to `FusedLocationProvider.kt` fallback**

  File: `app/src/main/java/com/sound2inat/location/FusedLocationProvider.kt`

  Around lines 36–52, the `lastLocation` fallback uses `suspendCancellableCoroutine` without a timeout. If the last known location is never available and the coroutine is never cancelled externally, this hangs forever.

  Wrap the second `suspendCancellableCoroutine` (the one waiting for `lastLocation`) with:

  ```kotlin
  withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
      suspendCancellableCoroutine { cont ->
          // existing code
      }
  }
  ```

  Add the constant:
  ```kotlin
  private const val LOCATION_TIMEOUT_MS = 5_000L
  ```

---

- [ ] **Step 11 — iNat: encode URLs in `INaturalistClient.kt`**

  File: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`

  Around lines 79 and 349, find string concatenation for URL building like:
  ```kotlin
  "$BASE_URL/taxa/$name"
  ```

  Replace with `HttpUrl.Builder`:

  ```kotlin
  val url = BASE_URL.toHttpUrl().newBuilder()
      .addPathSegment("taxa")
      .addPathSegment(name)
      .build()
      .toString()
  ```

  Add import: `import okhttp3.HttpUrl.Companion.toHttpUrl`

  **Reason:** Hybrid taxon names contain `×` (Unicode multiply sign) which breaks URL parsing if not percent-encoded. `HttpUrl.Builder.addPathSegment` percent-encodes automatically.

---

- [ ] **Step 12 — Inference: fix `droppedOnFull` accounting + add `AudioConstants.kt`**

  **Step 12a:** File: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`

  The comment says `DROP_OLDEST` evictions are silent (not counted in `droppedOnFull`). The `droppedOnFull` counter only increments when `trySend` returns failure — which only happens on a closed channel with `DROP_OLDEST`. So `droppedOnFull` is effectively always 0 for normal operation.

  To actually count drops, we need to know when a `DROP_OLDEST` eviction happens. The `Channel.trySend` API does not directly expose this. The current documentation says "Should be 0 in normal operation." Since there's no public API to count evictions in `DROP_OLDEST`, document this limitation instead of adding incorrect accounting:

  Update the KDoc on `droppedOnFull` property to clarify:

  ```kotlin
  /**
   * Counter incremented when [trySendWindow] returns failure — expected to remain 0
   * during normal recording since `BUFFERED+DROP_OLDEST` makes `trySend` infallible on
   * an open channel. DROP_OLDEST evictions from a full buffer are not separately counted
   * (the Channel API does not expose eviction callbacks). See [backlog] for queue depth.
   */
  open val droppedOnFull: StateFlow<Int> = _droppedOnFull.asStateFlow()
  ```

  **Step 12b:** Create `app/src/main/java/com/sound2inat/inference/AudioConstants.kt`:

  ```kotlin
  package com.sound2inat.inference

  object AudioConstants {
      const val BIRDNET_SAMPLE_RATE_HZ = 48_000
      const val PERCH_SAMPLE_RATE_HZ = 32_000

      /** BirdNET v2.4 window: 3 seconds at 48 kHz. */
      const val BIRDNET_WINDOW_SAMPLES = 144_000

      /** Perch v2 window: 5 seconds at 32 kHz. */
      const val PERCH_WINDOW_SAMPLES = 160_000
  }
  ```

  Replace the corresponding magic-number literals in `BirdNetTfliteModel.kt`, `PerchTfliteModel.kt`, and `LiveInferenceEngine.kt` (e.g., `48_000`, `32_000`, `144000`, `160000`) with `AudioConstants.BIRDNET_SAMPLE_RATE_HZ` etc.

---

- [ ] **Step 13 — Run CI**

  ```bash
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew detekt lint --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --no-daemon
  JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug --no-daemon
  ```

---

- [ ] **Step 14 — Commit**

  ```bash
  git add app/src/main/java/ app/src/androidTest/java/
  git commit -m "fix(minor): progress monotone; Float→Short; Verifying object; URL encoding; AudioConstants"
  ```

---

## Summary of task dependencies

```
Task 1 (CRITICAL: inference leaks)
  └─► Task 4 (MAJOR: inference dedup — needs YamNetGate.close())

Task 3 (CRITICAL: data+audio)
  └─► Task 7 (MAJOR: data layer — mergeAndPersist listForDraft already fixed)

Task 5 (MAJOR: UI point fixes — SettingsViewModel init)
  └─► Task 6 (MAJOR: ViewModel refactor — merges SettingsViewModel)

Tasks 2, 4, 8, 9 — independent (no blockers from other tasks)
```
