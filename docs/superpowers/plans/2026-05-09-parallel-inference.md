# Parallel Perch Inference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Speed up user-triggered Perch reanalysis ~2× by loading two independent TFLite interpreter instances and splitting windows across parallel coroutines.

**Architecture:** `BioacousticModel.newInstance()` creates an unloaded, independent copy of the same model. `InferenceRunner` is refactored from a single model to `List<BioacousticModel>`; size==1 keeps the existing sequential loop; size>1 splits the window range evenly across coroutines with `coroutineScope { async + async }`, merges and sorts results. `ProductionPerchAnalysisJob` gets a `parallelism` parameter that builds and loads the extra instances; `DefaultInferenceUseCase.perchReanalysis` sets `parallelism = 2`.

**Tech Stack:** Kotlin, Kotlin Coroutines (`coroutineScope`, `async`, `awaitAll`), `AtomicInteger`, `MutableStateFlow`, TFLite (`InterpreterFactory`/`InterpreterApi`), JUnit4, Google Truth, MockK, `TemporaryFolder`.

---

## File Map

| Action  | File |
|---------|------|
| Modify  | `app/src/main/java/com/sound2inat/inference/BioacousticModel.kt` |
| Modify  | `app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt` |
| Modify  | `app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt` |
| Modify  | `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` |
| Modify  | `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt` |
| Modify  | `app/src/test/java/com/sound2inat/inference/BirdNetTfliteModelTest.kt` |
| Modify  | `app/src/test/java/com/sound2inat/inference/PerchTfliteModelTest.kt` |
| Modify  | `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt` |
| Create  | `app/src/test/java/com/sound2inat/inference/ParallelInferenceRunnerTest.kt` |
| Modify  | `app/src/test/java/com/sound2inat/inference/InferenceUseCaseTest.kt` |

---

## Task 1: `BioacousticModel.newInstance()` — interface + implementations

**Before starting:** Read the current file contents for all three source files below so you know the exact lines to edit.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/BioacousticModel.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/BirdNetTfliteModelTest.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/PerchTfliteModelTest.kt`

- [ ] **Step 1: Write failing test in `BirdNetTfliteModelTest.kt`**

  Add at the bottom of `class BirdNetTfliteModelTest`:

  ```kotlin
  @Test
  fun `newInstance returns independent model - closing original does not break copy`() = runTest {
      val labels = tmp.newFile("ni_labels.txt").apply {
          writeText("Sylvia melanothorax_Cyprus Warbler\n")
      }
      val modelFile = tmp.newFile("ni_model.tflite").apply { writeBytes(byteArrayOf(0)) }
      val fake = FakeInterpreterFactory(output = floatArrayOf(0.8f))

      val original = BirdNetTfliteModel(fake)
      original.load(modelFile, labels)
      val copy = original.newInstance() as BirdNetTfliteModel
      copy.load(modelFile, labels)

      original.close()

      // copy's interpreter is a separate object — predict must succeed
      val pcm = FloatArray(48_000 * 3)
      val out = copy.predict(pcm, 48_000, null, null, 0L, 0L, 3_000L)
      assertThat(out).hasSize(1)
      assertThat(out[0].taxonScientificName).isEqualTo("Sylvia melanothorax")
      copy.close()
  }
  ```

- [ ] **Step 2: Run test to confirm compile error**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.BirdNetTfliteModelTest.newInstance*"
  ```

  Expected: compilation error — `Unresolved reference: newInstance`

- [ ] **Step 3: Add `newInstance()` to `BioacousticModel` interface**

  In `BioacousticModel.kt`, insert before `fun close()`:

  ```kotlin
      /**
       * Returns a new, unloaded instance of the same model type backed by the
       * same [InterpreterFactory]. The caller must call [load] before [predict]
       * and [close] when done. Does not copy interpreter state.
       */
      fun newInstance(): BioacousticModel
  ```

- [ ] **Step 4: Implement `newInstance()` in `BirdNetTfliteModel`**

  In `BirdNetTfliteModel.kt`, after the `override fun close() { ... }` block, add:

  ```kotlin
      override fun newInstance(): BioacousticModel = BirdNetTfliteModel(factory, threads)
  ```

- [ ] **Step 5: Implement `newInstance()` in `PerchTfliteModel`**

  In `PerchTfliteModel.kt`, after the `override fun close() { ... }` block, add:

  ```kotlin
      override fun newInstance(): BioacousticModel = PerchTfliteModel(factory, threads)
  ```

- [ ] **Step 6: Run BirdNetTfliteModelTest → PASS**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.BirdNetTfliteModelTest"
  ```

  Expected: all tests PASS, including the new `newInstance` test.

- [ ] **Step 7: Write failing test in `PerchTfliteModelTest.kt`**

  `PerchFakeInterpreterFactory` already exists in that file at line ~24; use it directly.
  The labels format for Perch is bare scientific names, one per line, with row 0 being
  a dataset tag that is filtered out by `Labels.load`. Add at the bottom of `class PerchTfliteModelTest`:

  ```kotlin
  @Test
  fun `newInstance returns independent model - closing original does not break copy`() = runTest {
      // Row 0 = dataset tag (filtered); row 1 = species label.
      val labels = tmp.newFile("ni_labels.csv").apply {
          writeText("inat2024_fsd50k\nRana temporaria\n")
      }
      val modelFile = tmp.newFile("ni_model.tflite").apply { writeBytes(byteArrayOf(0)) }
      // 1 label after filtering; logit 2.0 softmax → probability 1.0 > 0.01 floor → included.
      val fake = PerchFakeInterpreterFactory(output = floatArrayOf(2.0f), labelIndex = 3)

      val original = PerchTfliteModel(fake)
      original.load(modelFile, labels)
      val copy = original.newInstance() as PerchTfliteModel
      copy.load(modelFile, labels)

      original.close()

      val pcm = FloatArray(32_000 * 5)
      val out = copy.predict(pcm, 32_000, null, null, 0L, 0L, 5_000L)
      assertThat(out).hasSize(1)
      assertThat(out[0].taxonScientificName).isEqualTo("Rana temporaria")
      copy.close()
  }
  ```

- [ ] **Step 8: Run PerchTfliteModelTest → PASS**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.PerchTfliteModelTest"
  ```

  Expected: all tests PASS.

- [ ] **Step 9: Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/inference/BioacousticModel.kt \
    app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt \
    app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt \
    app/src/test/java/com/sound2inat/inference/BirdNetTfliteModelTest.kt \
    app/src/test/java/com/sound2inat/inference/PerchTfliteModelTest.kt
  git commit -m "feat(inference): add BioacousticModel.newInstance() to interface and TFLite implementations"
  ```

---

## Task 2: Refactor `InferenceRunner` to accept a list of models + parallel path

**Context:** `InferenceRunner` currently takes `model: BioacousticModel`. This task:
1. Creates `ParallelInferenceRunnerTest` with all 5 spec test cases (TDD — written first).
2. Replaces `model: BioacousticModel` with `models: List<BioacousticModel>` and adds the parallel branch.
3. Updates existing `InferenceRunnerTest` call sites and the `ProductionInferenceJob` call site.

**Closing contract:** `InferenceRunner.run()` closes all models it receives, in both paths:
- Sequential path: wraps the loop in `try/finally { model.close() }`.
- Parallel path: each `async { try { ... } finally { models[i].close() } }`.

This means `ProductionInferenceJob`'s existing `model.close()` in its `finally` block becomes a
safe, idempotent double-close (both `BirdNetTfliteModel.close()` and `PerchTfliteModel.close()`
null-check `interp` before calling `interp.close()`, so a second call is a no-op).

**Files:**
- Create: `app/src/test/java/com/sound2inat/inference/ParallelInferenceRunnerTest.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`

- [ ] **Step 1: Create `ParallelInferenceRunnerTest.kt` with all 5 tests**

  Create `app/src/test/java/com/sound2inat/inference/ParallelInferenceRunnerTest.kt`:

  ```kotlin
  package com.sound2inat.inference

  import com.google.common.truth.Truth.assertThat
  import com.sound2inat.recorder.WavWriter
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.runTest
  import org.junit.Rule
  import org.junit.Test
  import org.junit.rules.TemporaryFolder
  import java.io.File

  /**
   * Fake model for parallel runner tests. Records processed window start times
   * and whether close() was called. Each instance has a distinct [id].
   */
  private class TrackingFakeModel(
      private val id: String,
      override val expectedSampleRateHz: Int = 48_000,
      override val windowMs: Long = 3_000L,
  ) : BioacousticModel {
      override val modelId: String = id
      override val modelVersion: String = "0"
      val processedStartMs = mutableListOf<Long>()
      var closed = false

      override suspend fun load(modelFile: File, labelsFile: File) = Unit

      override suspend fun predict(
          pcmFloat32: FloatArray,
          sampleRateHz: Int,
          latitude: Double?,
          longitude: Double?,
          observedAtMillis: Long,
          windowStartMs: Long,
          windowEndMs: Long,
      ): List<WindowPrediction> {
          processedStartMs += windowStartMs
          return listOf(
              WindowPrediction(
                  startMs = windowStartMs,
                  endMs = windowEndMs,
                  taxonScientificName = "Fakus $id",
                  taxonCommonName = null,
                  confidence = 0.5f,
              ),
          )
      }

      override fun close() { closed = true }
      override fun newInstance(): BioacousticModel = TrackingFakeModel(id, expectedSampleRateHz, windowMs)
  }

  class ParallelInferenceRunnerTest {
      @get:Rule val tmp = TemporaryFolder()

      /**
       * 12 s at 48 kHz → 10 windows (window=3 s / hop=1 s):
       *   frames = 1 + (12×48000 − 3×48000) / 48000 = 10
       * 6 s at 48 kHz → 4 windows: frames = 1 + (6−3)×48000/48000 = 4
       */
      private fun writeSilentWav(durationSeconds: Int, sampleRate: Int = 48_000): File {
          val file = tmp.newFile("sil_${durationSeconds}s_${sampleRate}.wav")
          val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
          writer.open()
          val total = durationSeconds * sampleRate
          val chunk = ShortArray(sampleRate)
          var written = 0
          while (written < total) {
              val n = minOf(chunk.size, total - written)
              writer.writeShorts(chunk, 0, n)
              written += n
          }
          writer.close()
          return file
      }

      @Test
      fun `parallel path covers all windows with no gaps or duplicates`() = runTest {
          val wav = writeSilentWav(durationSeconds = 12) // 10 windows
          val fake1 = TrackingFakeModel("m1")
          val fake2 = TrackingFakeModel("m2")
          // Fails to compile until InferenceRunner accepts List<BioacousticModel>.
          val runner = InferenceRunner(listOf(fake1, fake2), hopSeconds = 1f)

          runner.run(wav, null, null, 0L)

          val allStarts = (fake1.processedStartMs + fake2.processedStartMs).sorted()
          assertThat(allStarts).containsExactly(
              0L, 1_000L, 2_000L, 3_000L, 4_000L,
              5_000L, 6_000L, 7_000L, 8_000L, 9_000L,
          ).inOrder()
          assertThat(fake1.processedStartMs).containsNoneIn(fake2.processedStartMs)
      }

      @Test
      fun `parallel results are sorted by windowStartMs`() = runTest {
          val wav = writeSilentWav(durationSeconds = 12)
          val runner = InferenceRunner(
              listOf(TrackingFakeModel("m1"), TrackingFakeModel("m2")),
              hopSeconds = 1f,
          )

          val out = runner.run(wav, null, null, 0L)

          assertThat(out).hasSize(10)
          assertThat(out.map { it.startMs }).isInOrder()
      }

      @Test
      fun `parallel progress is non-decreasing and reaches 1`() = runTest {
          val wav = writeSilentWav(durationSeconds = 12)
          val runner = InferenceRunner(
              listOf(TrackingFakeModel("m1"), TrackingFakeModel("m2")),
              hopSeconds = 1f,
          )
          val progressValues = mutableListOf(0f)

          backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
              runner.progress.collect { progressValues += it }
          }
          runner.run(wav, null, null, 0L)

          for (i in 1 until progressValues.size) {
              assertThat(progressValues[i]).isAtLeast(progressValues[i - 1])
          }
          assertThat(progressValues.last()).isEqualTo(1.0f)
      }

      @Test
      fun `sequential path calls single model for every window`() = runTest {
          val wav = writeSilentWav(durationSeconds = 6) // (6−3)/1+1 = 4 windows
          val fake = TrackingFakeModel("solo")
          val runner = InferenceRunner(listOf(fake), hopSeconds = 1f)

          runner.run(wav, null, null, 0L)

          assertThat(fake.processedStartMs).containsExactly(0L, 1_000L, 2_000L, 3_000L).inOrder()
          assertThat(runner.progress.value).isEqualTo(1.0f)
      }

      @Test
      fun `failure in one parallel coroutine propagates and both models are closed`() = runTest {
          val wav = writeSilentWav(durationSeconds = 12)
          val normal = TrackingFakeModel("normal")

          class FailingModel : BioacousticModel {
              override val modelId = "failing"
              override val modelVersion = "0"
              override val expectedSampleRateHz = 48_000
              override val windowMs = 3_000L
              var closed = false
              override suspend fun load(modelFile: File, labelsFile: File) = Unit
              override suspend fun predict(
                  pcmFloat32: FloatArray, sampleRateHz: Int, latitude: Double?,
                  longitude: Double?, observedAtMillis: Long,
                  windowStartMs: Long, windowEndMs: Long,
              ): List<WindowPrediction> = throw RuntimeException("intentional failure")
              override fun close() { closed = true }
              override fun newInstance(): BioacousticModel = FailingModel()
          }

          val failing = FailingModel()
          val runner = InferenceRunner(listOf(normal, failing), hopSeconds = 1f)

          var thrown: Throwable? = null
          try {
              runner.run(wav, null, null, 0L)
          } catch (t: Throwable) {
              thrown = t
          }

          assertThat(thrown).isInstanceOf(RuntimeException::class.java)
          assertThat(normal.closed).isTrue()
          assertThat(failing.closed).isTrue()
      }
  }
  ```

- [ ] **Step 2: Run → expect compile error**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.ParallelInferenceRunnerTest"
  ```

  Expected: compilation error — `InferenceRunner` constructor does not accept `List<BioacousticModel>`.

- [ ] **Step 3: Rewrite `InferenceRunner.kt`**

  Read the current file first to get the exact `WavReader` implementation to copy verbatim.
  Replace the `InferenceRunner` class (lines 1 through the closing brace of the class,
  before `internal object WavReader`) with the following. Keep `WavReader` and its
  `readLeUint16`/`readLeUint32` helpers unchanged at the bottom of the file.

  ```kotlin
  package com.sound2inat.inference

  import android.util.Log
  import kotlinx.coroutines.async
  import kotlinx.coroutines.awaitAll
  import kotlinx.coroutines.coroutineScope
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import java.io.File
  import java.io.RandomAccessFile
  import java.util.concurrent.atomic.AtomicInteger

  /**
   * Slices a mono 16-bit WAV into fixed windows hopped by [hopSeconds], optionally
   * applies a YAMNet biological gate, and calls each model per window that passes the gate.
   *
   * Sequential path ([models].size == 1): existing per-window loop, unchanged behaviour.
   * Parallel path ([models].size > 1): windows split evenly across models and processed
   * in parallel coroutines; results merged and sorted by windowStartMs.
   *
   * [run] closes every model it receives (sequential: try/finally on models[0];
   * parallel: async finally blocks per model). Callers must NOT close models after [run].
   */
  class InferenceRunner(
      private val models: List<BioacousticModel>,
      private val hopSeconds: Float = 1f,
      private val yamNetGate: YamNetGate? = null,
      /**
       * When true, a DOWNRANK gate decision skips [BioacousticModel.predict] entirely.
       * When false (default), DOWNRANK is a soft post-filter that drops results only
       * when no prediction exceeds [HIGH_CONFIDENCE_OVERRIDE].
       */
      val hardGate: Boolean = false,
  ) {
      init { require(models.isNotEmpty()) { "models must not be empty" } }

      private val _progress = MutableStateFlow(0f)
      val progress: StateFlow<Float> = _progress

      suspend fun run(
          wavFile: File,
          latitude: Double?,
          longitude: Double?,
          observedAtMillis: Long,
      ): List<WindowPrediction> {
          _progress.value = 0f
          val model = models.first()
          val t0 = System.currentTimeMillis()
          val (rawSamples, nativeRate) = WavReader.readMono16(wavFile)
          val targetRate = model.expectedSampleRateHz
          val resampled = if (nativeRate == targetRate) {
              rawSamples
          } else {
              Resampler.resample(rawSamples, nativeRate, targetRate)
          }
          Log.d(
              "InferenceTiming",
              "${model.modelId}: WAV read+resample ${System.currentTimeMillis() - t0}ms " +
                  "(${rawSamples.size}→${resampled.size} samples, ${nativeRate}→${targetRate}Hz)",
          )

          val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }

          val windowSeconds = model.windowMs / MS_PER_SECOND
          val win = (windowSeconds * targetRate).toInt()
          val hop = (hopSeconds * targetRate).toInt()
          require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }
          val frames = if (normalized.size < win) 0 else 1 + (normalized.size - win) / hop
          if (frames == 0) {
              _progress.value = 1f
              return emptyList()
          }

          return if (models.size == 1) {
              runSequential(normalized, targetRate, frames, win, hop, model, latitude, longitude, observedAtMillis)
          } else {
              runParallel(normalized, targetRate, frames, win, hop, latitude, longitude, observedAtMillis)
          }
      }

      private suspend fun runSequential(
          normalized: FloatArray,
          targetRate: Int,
          frames: Int,
          win: Int,
          hop: Int,
          model: BioacousticModel,
          latitude: Double?,
          longitude: Double?,
          observedAtMillis: Long,
      ): List<WindowPrediction> {
          val out = ArrayList<WindowPrediction>(frames * 5)
          val loopStart = System.currentTimeMillis()
          var batchStart = loopStart
          try {
              for (f in 0 until frames) {
                  val s = f * hop
                  val window = normalized.copyOfRange(s, s + win)
                  _progress.value = (f + 1).toFloat() / frames
                  val gateResult = yamNetGate?.classify(window, targetRate)
                  if (hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) continue
                  val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
                  val endMs = ((s + win).toLong() * MS_PER_SECOND_LONG) / targetRate
                  val predictions = model.predict(
                      pcmFloat32 = window,
                      sampleRateHz = targetRate,
                      latitude = latitude,
                      longitude = longitude,
                      observedAtMillis = observedAtMillis,
                      windowStartMs = startMs,
                      windowEndMs = endMs,
                  )
                  if (f == 0 || (f + 1) % 50 == 0 || f == frames - 1) {
                      val now = System.currentTimeMillis()
                      Log.d(
                          "InferenceTiming",
                          "${model.modelId}: window ${f + 1}/$frames " +
                              "${now - batchStart}ms batch / ${now - loopStart}ms total",
                      )
                      batchStart = now
                  }
                  if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                      if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
                  }
                  out += predictions
              }
          } finally {
              model.close()
          }
          Log.d(
              "InferenceTiming",
              "${model.modelId}: loop done — $frames windows in ${System.currentTimeMillis() - loopStart}ms",
          )
          return out
      }

      private suspend fun runParallel(
          normalized: FloatArray,
          targetRate: Int,
          frames: Int,
          win: Int,
          hop: Int,
          latitude: Double?,
          longitude: Double?,
          observedAtMillis: Long,
      ): List<WindowPrediction> {
          val counter = AtomicInteger(0)
          val results = coroutineScope {
              models.indices.map { i ->
                  val chunkStart = i * frames / models.size
                  val chunkEnd = if (i == models.size - 1) frames else (i + 1) * frames / models.size
                  async {
                      val chunkOut = ArrayList<WindowPrediction>()
                      try {
                          for (f in chunkStart until chunkEnd) {
                              val s = f * hop
                              val window = normalized.copyOfRange(s, s + win)
                              val gateResult = yamNetGate?.classify(window, targetRate)
                              if (hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                                  _progress.value = counter.incrementAndGet().toFloat() / frames
                                  continue
                              }
                              val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
                              val endMs = ((s + win).toLong() * MS_PER_SECOND_LONG) / targetRate
                              val predictions = models[i].predict(
                                  pcmFloat32 = window,
                                  sampleRateHz = targetRate,
                                  latitude = latitude,
                                  longitude = longitude,
                                  observedAtMillis = observedAtMillis,
                                  windowStartMs = startMs,
                                  windowEndMs = endMs,
                              )
                              _progress.value = counter.incrementAndGet().toFloat() / frames
                              if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                                  if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
                              }
                              chunkOut += predictions
                          }
                      } finally {
                          models[i].close()
                      }
                      chunkOut
                  }
              }.awaitAll()
          }
          _progress.value = 1f
          return results.flatten().sortedBy { it.startMs }
      }

      private companion object {
          const val MS_PER_SECOND = 1_000f
          const val MS_PER_SECOND_LONG = 1_000L
          const val HIGH_CONFIDENCE_OVERRIDE = 0.7f
      }
  }
  ```

  Then copy the unchanged `internal object WavReader { ... }` from the original file verbatim.

- [ ] **Step 4: Run `ParallelInferenceRunnerTest` → PASS**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.ParallelInferenceRunnerTest"
  ```

  Expected: all 5 tests PASS.

- [ ] **Step 5: Update `InferenceRunnerTest.kt` call sites**

  Every `InferenceRunner(model, ...)` or `InferenceRunner(model)` must become
  `InferenceRunner(listOf(model), ...)`. There are four occurrences in the file
  (around lines 83, 103, 114, 141). Examples:

  - `InferenceRunner(model, hopSeconds = 1f)` → `InferenceRunner(listOf(model), hopSeconds = 1f)`
  - `InferenceRunner(model)` → `InferenceRunner(listOf(model))`
  - `InferenceRunner(model, hopSeconds = 1f, yamNetGate = alwaysDownrankGate)` →
    `InferenceRunner(listOf(model), hopSeconds = 1f, yamNetGate = alwaysDownrankGate)`

- [ ] **Step 6: Update `ProductionInferenceJob` call site in `InferenceUseCase.kt`**

  In `ProductionInferenceJob.run()` (around line 146), change:

  ```kotlin
  val runner = InferenceRunner(
      model,
      yamNetGate = activeGate,
  )
  ```

  to:

  ```kotlin
  val runner = InferenceRunner(
      listOf(model),
      yamNetGate = activeGate,
  )
  ```

- [ ] **Step 7: Run all inference tests → PASS**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.*"
  ```

  Expected: all tests PASS (including existing `InferenceRunnerTest` and `InferenceUseCaseTest`).

- [ ] **Step 8: Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/inference/InferenceRunner.kt \
    app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt \
    app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt \
    app/src/test/java/com/sound2inat/inference/ParallelInferenceRunnerTest.kt
  git commit -m "feat(inference): InferenceRunner parallel path — split windows across two model instances"
  ```

---

## Task 3: `ProductionPerchAnalysisJob` `parallelism` param + `DefaultInferenceUseCase` wiring

**Context:** `ProductionPerchAnalysisJob` currently loads a single Perch instance. This task adds
`parallelism: Int = 1`; when set to 2 it calls `perch.newInstance()` to create a second instance,
loads both, and passes them both to `InferenceRunner`. Since `InferenceRunner` now owns model
closing (Task 2), the old `perch.close()` in the `finally` block is removed and replaced by a
load-failure cleanup guard. `DefaultInferenceUseCase.perchReanalysis` is updated to `parallelism = 2`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/InferenceUseCaseTest.kt`

- [ ] **Step 1: Write failing test in `InferenceUseCaseTest.kt`**

  Read the current `InferenceUseCaseTest.kt` fully to find the `fakeSettings` and
  `fakeModelManager` helpers. Then add the following test to the class:

  ```kotlin
  @Test
  fun `ProductionPerchAnalysisJob parallelism=2 calls newInstance once and closes both`() = runTest {
      val modelFile = tmp.newFile("perch.tflite").apply { writeBytes(byteArrayOf(0)) }
      val labelsFile = tmp.newFile("perch_labels.csv").apply {
          // Row 0 = dataset tag (filtered); row 1 = species.
          writeText("inat2024_fsd50k\nRana temporaria\n")
      }
      // 12 s WAV at 32 kHz (Perch's native rate — no resampling needed).
      // frames = 1 + (12*32000 - 5*32000) / 32000 = 8 windows.
      val perchWav = run {
          val f = tmp.newFile("perch_silence.wav")
          val w = WavWriter(f, sampleRate = 32_000, channels = 1, bitsPerSample = 16)
          w.open()
          val total = 12 * 32_000
          val chunk = ShortArray(32_000)
          var written = 0
          while (written < total) {
              val n = minOf(chunk.size, total - written)
              w.writeShorts(chunk, 0, n)
              written += n
          }
          w.close()
          f
      }

      var newInstanceCalls = 0
      val allInstances = mutableListOf<PerchTracker>()

      class PerchTracker : BioacousticModel {
          override val modelId = ModelIds.PERCH
          override val modelVersion = "2"
          override val expectedSampleRateHz = 32_000
          override val windowMs = 5_000L
          var closed = false
          override suspend fun load(modelFile: File, labelsFile: File) = Unit
          override suspend fun predict(
              pcmFloat32: FloatArray, sampleRateHz: Int, latitude: Double?,
              longitude: Double?, observedAtMillis: Long,
              windowStartMs: Long, windowEndMs: Long,
          ) = listOf(
              WindowPrediction(windowStartMs, windowEndMs, "Rana temporaria", null, 0.5f, ModelIds.PERCH),
          )
          override fun close() { closed = true }
          override fun newInstance(): BioacousticModel {
              newInstanceCalls++
              return PerchTracker().also { allInstances += it }
          }
      }

      val root = PerchTracker().also { allInstances += it }
      val job = ProductionPerchAnalysisJob(
          models = listOf(root),
          modelManager = fakeModelManager(modelFile, labelsFile),
          settings = fakeSettings(),
          yamNetGate = null,
          parallelism = 2,   // fails to compile until parameter exists
      )

      val outcome = job.run(perchWav.absolutePath, null, null, 0L) {}

      assertThat(outcome).isInstanceOf(PerchAnalysisOutcome.Success::class.java)
      assertThat(newInstanceCalls).isEqualTo(1)
      assertThat(allInstances).hasSize(2)
      assertThat(allInstances.all { it.closed }).isTrue()
  }
  ```

  `WavWriter` must be imported — add to the top of `InferenceUseCaseTest.kt` if absent:
  ```kotlin
  import com.sound2inat.recorder.WavWriter
  ```

- [ ] **Step 2: Run → compile error expected**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.InferenceUseCaseTest.*parallelism*"
  ```

  Expected: compilation error — unknown parameter `parallelism` in `ProductionPerchAnalysisJob`.

- [ ] **Step 3: Rewrite `ProductionPerchAnalysisJob` in `InferenceUseCase.kt`**

  Replace the entire `ProductionPerchAnalysisJob` class with:

  ```kotlin
  internal class ProductionPerchAnalysisJob(
      private val models: List<BioacousticModel>,
      private val modelManager: ModelManager,
      private val settings: Settings,
      private val yamNetGate: YamNetGate?,
      private val hardGate: Boolean = false,
      private val parallelism: Int = 1,
  ) : PerchAnalysisJob {

      @Suppress("TooGenericExceptionCaught")
      override suspend fun run(
          audioPath: String,
          latitude: Double?,
          longitude: Double?,
          observedAtMillis: Long,
          onProgress: (Float) -> Unit,
      ): PerchAnalysisOutcome = withContext(Dispatchers.IO) {
          val perch = models.firstOrNull { it.modelId == ModelIds.PERCH }
              ?: return@withContext PerchAnalysisOutcome.NotInstalled
          val state = modelManager.stateFor(com.sound2inat.modelmanager.PerchV2.descriptor)
              as? ModelInstallState.Ready
              ?: return@withContext PerchAnalysisOutcome.NotInstalled
          try {
              val perchStartMs = System.currentTimeMillis()
              val instances = buildList {
                  add(perch)
                  repeat(parallelism - 1) { add(perch.newInstance()) }
              }
              // Load all instances; if any load() throws, close already-loaded ones first.
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
              val gate = if (hardGate || settings.yamNetGateEnabled.first()) yamNetGate else null
              val runner = InferenceRunner(
                  models = loaded,
                  yamNetGate = gate,
                  hardGate = hardGate,
              )
              val preds = coroutineScope {
                  val collector = launch {
                      runner.progress.collect { p -> onProgress(p) }
                  }
                  val result = runner.run(File(audioPath), latitude, longitude, observedAtMillis)
                  collector.cancel()
                  result
              }
              android.util.Log.i(
                  "InferenceTiming",
                  "Perch(parallelism=$parallelism): ${System.currentTimeMillis() - perchStartMs}ms total " +
                      "(load+inference, ${preds.size} raw preds)",
              )
              onProgress(1f)
              val minConf = settings.minConfidenceDisplay.first()
              val minWin = settings.minWindows.first()
              val aggregator = DetectionAggregator(minConfidence = minConf, minWindows = minWin)
              PerchAnalysisOutcome.Success(aggregator.aggregate(preds))
          } catch (t: Throwable) {
              android.util.Log.e("ProductionPerchAnalysisJob", "Perch run failed", t)
              PerchAnalysisOutcome.Failure(t.message ?: t::class.simpleName.orEmpty())
          }
          // InferenceRunner closes all models (sequential: try/finally; parallel: async finally).
          // No explicit perch.close() needed here.
      }
  }
  ```

- [ ] **Step 4: Update `DefaultInferenceUseCase.perchReanalysis` to `parallelism = 2`**

  In `DefaultInferenceUseCase`, change:

  ```kotlin
  override val perchReanalysis: PerchAnalysisJob = ProductionPerchAnalysisJob(
      models = models,
      modelManager = modelManager,
      settings = settings,
      yamNetGate = null,
  )
  ```

  to:

  ```kotlin
  override val perchReanalysis: PerchAnalysisJob = ProductionPerchAnalysisJob(
      models = models,
      modelManager = modelManager,
      settings = settings,
      yamNetGate = null,
      parallelism = 2,
  )
  ```

- [ ] **Step 5: Run `InferenceUseCaseTest` → PASS**

  ```
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.InferenceUseCaseTest"
  ```

  Expected: all tests PASS.

- [ ] **Step 6: Run the full test suite**

  ```
  ./gradlew :app:testDebugUnitTest
  ```

  Expected: all tests PASS — no regressions anywhere.

- [ ] **Step 7: Commit**

  ```bash
  git add \
    app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt \
    app/src/test/java/com/sound2inat/inference/InferenceUseCaseTest.kt
  git commit -m "feat(inference): ProductionPerchAnalysisJob parallelism=2 for perchReanalysis"
  ```

---

## Self-review checklist for the implementing agent

After completing all tasks, verify:

1. `BioacousticModel` interface declares `fun newInstance(): BioacousticModel` between `predict()` and `close()`.
2. `BirdNetTfliteModel.newInstance()` → `BirdNetTfliteModel(factory, threads)`.
3. `PerchTfliteModel.newInstance()` → `PerchTfliteModel(factory, threads)`.
4. `InferenceRunner` constructor takes `models: List<BioacousticModel>`, the `require(models.isNotEmpty())` guard is present, and the old single `model` parameter is gone.
5. Sequential path (`models.size == 1`) wraps the `for` loop in `try/finally { model.close() }`.
6. Parallel path (`models.size > 1`) has `async { try { ... } finally { models[i].close() } }` for each chunk.
7. Progress reaches `1f` in both paths (sequential: last frame sets it; parallel: explicit `_progress.value = 1f` after `coroutineScope`).
8. `ProductionInferenceJob` uses `InferenceRunner(listOf(model), yamNetGate = activeGate)` — no other changes.
9. `ProductionPerchAnalysisJob` has `parallelism: Int = 1`, the load-failure cleanup guard closes `loaded` instances on any `load()` exception, and there is no `perch.close()` outside that guard.
10. `DefaultInferenceUseCase.perchReanalysis` passes `parallelism = 2`.
11. All 5 `ParallelInferenceRunnerTest` cases pass.
12. `./gradlew :app:testDebugUnitTest` passes completely.
