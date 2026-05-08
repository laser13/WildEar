# Audio Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Before starting any task:** read the task description fully and look for any inconsistencies, ambiguities, or errors. If you find any, report them before writing code.

**Goal:** Apply denoise + peak-normalization to the WAV file once after recording stops; add an audio source toggle (UNPROCESSED vs MIC+AGC) in Settings. All consumers — Review playback, re-analysis, iNat upload — read the same processed file.

**Architecture:** (1) Two new Settings keys + `AndroidAudioRecordSource` `preferRaw` constructor param, wired in DI. (2) New `AudioNormalizer` (peak-normalize utility) + `PostRecordingProcessor` (denoise→normalize pipeline) called from `DefaultRecordingController.stop()`. (3) Remove dead spectral subtraction code from the disk-based inference path (`InferenceRunner` + `InferenceUseCase`). (4) Settings UI: two new toggles + updated spectral subtitle + merged Noise Reduction section into Inference section.

**Tech Stack:** Kotlin coroutines, DataStore Preferences, Android `AudioRecord`, JVM JUnit tests (no Android dependency for audio utilities).

**Spec:** `docs/superpowers/specs/2026-05-08-audio-quality-design.md`

---

## File Map

| File | Action | Task |
|------|--------|------|
| `app/src/main/java/com/sound2inat/app/data/Settings.kt` | Add `audioSourceRaw`, `normalizeAudio` keys | 1 |
| `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt` | Add `preferRaw` constructor param | 1 |
| `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt` | Wire `preferRaw` from Settings | 1 |
| `app/src/main/java/com/sound2inat/inference/AudioNormalizer.kt` | New: peak-normalize + WAV write utility | 2 |
| `app/src/test/java/com/sound2inat/inference/AudioNormalizerTest.kt` | New: JVM unit tests | 2 |
| `app/src/main/java/com/sound2inat/inference/PostRecordingProcessor.kt` | New: denoise + normalize pipeline | 3 |
| `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt` | Add `processor` param; call it in `stop()` | 4 |
| `app/src/main/java/com/sound2inat/app/di/RecordingModule.kt` | Provide `PostRecordingProcessor` | 4 |
| `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` | Remove `spectralSubtractor`, `usePreprocessing` params | 5 |
| `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt` | Remove `spectralEnabled` / subtractor from disk jobs | 5 |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt` | Add `audioSourceRaw`, `normalizeAudio` fields | 6 |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt` | Add flows + write fns + init collectors | 6 |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt` | Add toggles; merge NoiseReduction into Inference | 6 |
| `app/src/main/res/values/strings.xml` | Add 2 new strings; update spectral subtitle | 6 |

---

### Task 1: Settings keys + AndroidAudioRecordSource preferRaw + DI wiring

**Context:** `Settings.kt` uses DataStore with a private `K` object for keys and a `Flow<Boolean>` per key. `AndroidAudioRecordSource` currently always calls `buildAudioRecord()` which tries UNPROCESSED, falls back to MIC. `SwappableModule` provides `AudioRecordSource` as a singleton `AndroidAudioRecordSource()`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/data/Settings.kt`
- Modify: `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`

- [ ] **Step 1: Read the three files before editing**

  Read:
  - `app/src/main/java/com/sound2inat/app/data/Settings.kt`
  - `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt`
  - `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`

- [ ] **Step 2: Add keys to Settings.kt**

  Inside `private object K { ... }`, add after `ALLOW_DELETE_UPLOADED`:
  ```kotlin
  val AUDIO_SOURCE_RAW = booleanPreferencesKey("audio_source_raw")
  val NORMALIZE_AUDIO = booleanPreferencesKey("normalize_audio")
  ```

  After the `allowDeleteUploaded` flow, add:
  ```kotlin
  val audioSourceRaw: Flow<Boolean> =
      ctx.dataStore.data.map { it[K.AUDIO_SOURCE_RAW] ?: true }
  val normalizeAudio: Flow<Boolean> =
      ctx.dataStore.data.map { it[K.NORMALIZE_AUDIO] ?: true }
  ```

  After `setAllowDeleteUploaded`, add:
  ```kotlin
  suspend fun setAudioSourceRaw(v: Boolean) { ctx.dataStore.edit { it[K.AUDIO_SOURCE_RAW] = v } }
  suspend fun setNormalizeAudio(v: Boolean) { ctx.dataStore.edit { it[K.NORMALIZE_AUDIO] = v } }
  ```

- [ ] **Step 3: Add preferRaw constructor param to AndroidAudioRecordSource**

  Change class declaration:
  ```kotlin
  class AndroidAudioRecordSource(
      private val preferRaw: suspend () -> Boolean = { true },
  ) : AudioRecordSource {
  ```

  Change `start()` to call `preferRaw()` and pass the result:
  ```kotlin
  override suspend fun start() {
      record = buildAudioRecord(sampleRate, bufBytes, preferRaw())
      record!!.startRecording()
      stopped = false
  }
  ```

  Change `buildAudioRecord` signature and logic:
  ```kotlin
  @SuppressLint("MissingPermission")
  internal fun buildAudioRecord(sampleRate: Int, bufferSize: Int, useRaw: Boolean = true): AudioRecord {
      if (useRaw) {
          runCatching {
              val ar = audioRecordFactory(
                  MediaRecorder.AudioSource.UNPROCESSED,
                  sampleRate,
                  bufferSize,
              )
              if (ar.state == AudioRecord.STATE_INITIALIZED) return ar
              ar.release()
          }
      }
      return audioRecordFactory(
          MediaRecorder.AudioSource.MIC,
          sampleRate,
          bufferSize,
      )
  }
  ```

- [ ] **Step 4: Wire preferRaw in SwappableModule**

  Change `provideAudioSource` to accept `Settings` and pass `preferRaw`:
  ```kotlin
  @Provides @Singleton
  fun provideAudioSource(settings: Settings): AudioRecordSource =
      AndroidAudioRecordSource(preferRaw = { settings.audioSourceRaw.first() })
  ```

- [ ] **Step 5: Verify compilation**

  Run:
  ```bash
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -40
  ```
  Expected: BUILD SUCCESSFUL with 0 errors.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/data/Settings.kt \
          app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt \
          app/src/main/java/com/sound2inat/app/di/SwappableModule.kt
  git commit -m "feat: add audioSourceRaw/normalizeAudio settings; wire preferRaw in AudioRecordSource"
  ```

---

### Task 2: AudioNormalizer utility

**Context:** Need a pure JVM utility that (a) peak-normalizes a `ShortArray` in memory and (b) reads a mono 16-bit WAV, normalizes it, and writes the result to a destination file. The WAV format is the same simple 44-byte header format used by `WavReader` in `InferenceRunner.kt`. `PostRecordingProcessor` (Task 3) will also need to write WAVs after denoising, so `writeWav` is marked `internal` for reuse within the package.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/AudioNormalizer.kt`
- Create: `app/src/test/java/com/sound2inat/inference/AudioNormalizerTest.kt`

- [ ] **Step 1: Write the failing test first**

  Create `app/src/test/java/com/sound2inat/inference/AudioNormalizerTest.kt`:
  ```kotlin
  package com.sound2inat.inference

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Rule
  import org.junit.Test
  import org.junit.rules.TemporaryFolder
  import java.io.File

  class AudioNormalizerTest {

      @get:Rule val tmp = TemporaryFolder()

      // Helpers
      private fun wavFile(samples: ShortArray, sampleRate: Int = 44100): File {
          val f = tmp.newFile("in.wav")
          writeWav(samples, sampleRate, f)
          return f
      }

      @Test
      fun `normalizeSamples scales peak to Short MAX`() {
          val input = shortArrayOf(0, 100, -200, 50)
          val result = AudioNormalizer.normalizeSamples(input)
          assertEquals(Short.MAX_VALUE.toInt(), kotlin.math.abs(result[2].toInt()))
      }

      @Test
      fun `normalizeSamples passes through silence unchanged`() {
          val input = ShortArray(10) { 0 }
          val result = AudioNormalizer.normalizeSamples(input)
          assertTrue(result.all { it == 0.toShort() })
      }

      @Test
      fun `normalizeSamples does not clip already-at-max signal`() {
          val input = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
          val result = AudioNormalizer.normalizeSamples(input)
          assertEquals(Short.MAX_VALUE.toInt(), result[0].toInt())
          // Short.MIN_VALUE scaled by MAX/MAX = Short.MIN_VALUE, but coerced to MIN_VALUE
          assertTrue(result[1] <= 0)
      }

      @Test
      fun `normalizeFile produces WAV with peak at Short MAX`() {
          val src = wavFile(shortArrayOf(0, 1000, -500, 200))
          val dst = tmp.newFile("out.wav")
          AudioNormalizer.normalizeFile(src, dst)
          val (samples, _) = readWavForTest(dst)
          assertEquals(Short.MAX_VALUE.toInt(), samples.maxOf { kotlin.math.abs(it.toInt()) })
      }

      @Test
      fun `normalizeFile passes through silent WAV unchanged`() {
          val src = wavFile(ShortArray(100) { 0 })
          val dst = tmp.newFile("out.wav")
          AudioNormalizer.normalizeFile(src, dst)
          val (samples, _) = readWavForTest(dst)
          assertTrue(samples.all { it == 0.toShort() })
      }

      /** Read WAV samples using the same logic as WavReader for round-trip verification. */
      private fun readWavForTest(f: File): Pair<ShortArray, Int> {
          val bytes = f.readBytes()
          val sampleRate = (bytes[24].toInt() and 0xFF) or
              ((bytes[25].toInt() and 0xFF) shl 8) or
              ((bytes[26].toInt() and 0xFF) shl 16) or
              ((bytes[27].toInt() and 0xFF) shl 24)
          val dataSize = (bytes[40].toInt() and 0xFF) or
              ((bytes[41].toInt() and 0xFF) shl 8) or
              ((bytes[42].toInt() and 0xFF) shl 16) or
              ((bytes[43].toInt() and 0xFF) shl 24)
          val samples = ShortArray(dataSize / 2) { i ->
              val lo = bytes[44 + 2 * i].toInt() and 0xFF
              val hi = bytes[44 + 2 * i + 1].toInt()
              ((hi shl 8) or lo).toShort()
          }
          return samples to sampleRate
      }
  }
  ```

- [ ] **Step 2: Run test to verify it fails**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.AudioNormalizerTest" 2>&1 | tail -20
  ```
  Expected: FAILED — `AudioNormalizer` does not exist yet.

- [ ] **Step 3: Create AudioNormalizer.kt**

  Create `app/src/main/java/com/sound2inat/inference/AudioNormalizer.kt`:
  ```kotlin
  package com.sound2inat.inference

  import java.io.File
  import java.io.RandomAccessFile
  import kotlin.math.abs

  object AudioNormalizer {

      /** Peak-normalize [samples] in memory. Silence (all zeros) is returned unchanged. */
      fun normalizeSamples(samples: ShortArray): ShortArray {
          val peak = samples.maxOfOrNull { abs(it.toInt()) } ?: 0
          if (peak == 0) return samples
          val scale = Short.MAX_VALUE.toFloat() / peak
          return ShortArray(samples.size) { i ->
              (samples[i] * scale).toInt()
                  .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                  .toShort()
          }
      }

      /**
       * Read [src] as mono 16-bit PCM WAV, peak-normalize, write to [dst].
       * Silence (peak == 0) is copied verbatim. [dst] may equal [src] only if
       * the caller guarantees no concurrent readers; for atomic in-place
       * replacement use a temp file + rename.
       */
      fun normalizeFile(src: File, dst: File) {
          val (samples, sampleRate) = WavReader.readMono16(src)
          writeWav(normalizeSamples(samples), sampleRate, dst)
      }
  }

  /**
   * Write [samples] as a mono 16-bit PCM WAV to [dst].
   * Used internally by [AudioNormalizer] and [PostRecordingProcessor].
   */
  internal fun writeWav(samples: ShortArray, sampleRateHz: Int, dst: File) {
      val dataSize = samples.size * 2
      RandomAccessFile(dst, "rw").use { raf ->
          raf.setLength(0)
          raf.write(buildWavHeader(sampleRateHz, dataSize))
          val buf = ByteArray(dataSize)
          for (i in samples.indices) {
              val s = samples[i].toInt()
              buf[2 * i] = (s and 0xFF).toByte()
              buf[2 * i + 1] = ((s ushr 8) and 0xFF).toByte()
          }
          raf.write(buf)
      }
  }

  private fun buildWavHeader(sampleRateHz: Int, dataSize: Int): ByteArray {
      val byteRate = sampleRateHz * 2 // mono 16-bit
      return ByteArray(44).also { h ->
          h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte()
          h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
          leInt(h, 4, dataSize + 36)
          h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte()
          h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
          h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte()
          h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
          leInt(h, 16, 16)             // fmt chunk size
          leShort(h, 20, 1)            // PCM
          leShort(h, 22, 1)            // mono
          leInt(h, 24, sampleRateHz)
          leInt(h, 28, byteRate)
          leShort(h, 32, 2)            // block align (1 ch * 2 bytes)
          leShort(h, 34, 16)           // bits per sample
          h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte()
          h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
          leInt(h, 40, dataSize)
      }
  }

  private fun leInt(buf: ByteArray, off: Int, v: Int) {
      buf[off] = (v and 0xFF).toByte()
      buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
      buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
      buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
  }

  private fun leShort(buf: ByteArray, off: Int, v: Int) {
      buf[off] = (v and 0xFF).toByte()
      buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
  }
  ```

- [ ] **Step 4: Run tests — expect PASS**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.AudioNormalizerTest" 2>&1 | tail -20
  ```
  Expected: 5 tests PASSED.

- [ ] **Step 5: Compile check**

  ```bash
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/inference/AudioNormalizer.kt \
          app/src/test/java/com/sound2inat/inference/AudioNormalizerTest.kt
  git commit -m "feat: add AudioNormalizer peak-normalize utility with WAV write helper"
  ```

---

### Task 3: PostRecordingProcessor

**Context:** After `recorder.stop()` returns the WAV path in `DefaultRecordingController.stop()`, we need to optionally (a) spectrally denoise and (b) peak-normalize the WAV in-place. `denoiseFull(FloatArray, Int)` in `AudioPreprocessor.kt` does high-pass + spectral subtraction in 1-second chunks — use it directly. `writeWav` from Task 2 writes the result. The operation must be atomic: write to a `.tmp` file, then rename, so the original is never left in a corrupt state.

**Processing order (spec §2):** denoising first, then normalization. Both are optional and read from Settings.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/PostRecordingProcessor.kt`

- [ ] **Step 1: Create PostRecordingProcessor.kt**

  ```kotlin
  package com.sound2inat.inference

  import android.util.Log
  import com.sound2inat.app.data.Settings
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.flow.first
  import kotlinx.coroutines.withContext
  import java.io.File

  class PostRecordingProcessor(
      private val settings: Settings,
  ) {
      /**
       * Applies active post-recording settings to [wavFile] in-place:
       * 1. Spectral denoising (if [Settings.spectralSubtractionEnabled]).
       * 2. Peak normalization (if [Settings.normalizeAudio]).
       * Writes to a temp file, replaces original atomically on success.
       * On failure: logs the error and leaves the original intact.
       */
      suspend fun process(wavFile: File) = withContext(Dispatchers.IO) {
          val spectralEnabled = settings.spectralSubtractionEnabled.first()
          val normalizeEnabled = settings.normalizeAudio.first()
          if (!spectralEnabled && !normalizeEnabled) return@withContext

          runCatching {
              val (rawShorts, sampleRate) = WavReader.readMono16(wavFile)

              var shorts = rawShorts

              if (spectralEnabled) {
                  val floats = FloatArray(rawShorts.size) { i ->
                      rawShorts[i] / Short.MAX_VALUE.toFloat()
                  }
                  val denoised = denoiseFull(floats, sampleRate)
                  shorts = ShortArray(denoised.size) { i ->
                      (denoised[i] * Short.MAX_VALUE).toInt()
                          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                          .toShort()
                  }
              }

              if (normalizeEnabled) {
                  shorts = AudioNormalizer.normalizeSamples(shorts)
              }

              val tmp = File(wavFile.parent, "${wavFile.nameWithoutExtension}.processing.tmp")
              writeWav(shorts, sampleRate, tmp)
              if (!tmp.renameTo(wavFile)) {
                  // renameTo can fail across filesystems; fallback to copy+delete
                  tmp.copyTo(wavFile, overwrite = true)
                  tmp.delete()
              }
          }.onFailure { e ->
              Log.e(TAG, "Post-recording processing failed for ${wavFile.name}; leaving original intact", e)
          }
      }

      private companion object {
          const val TAG = "PostRecordingProcessor"
      }
  }
  ```

- [ ] **Step 2: Compile check**

  ```bash
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -20
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/inference/PostRecordingProcessor.kt
  git commit -m "feat: add PostRecordingProcessor — denoise + normalize WAV after recording"
  ```

---

### Task 4: Wire PostRecordingProcessor into DefaultRecordingController + Hilt

**Context:** `DefaultRecordingController.stop()` (line 224 in `RecordingController.kt`) currently calls `recorder.stop()` → gets `result.audioPath` → calls `drafts.create()` or `drafts.createWithDetections()`. `PostRecordingProcessor.process(wavFile)` must be called AFTER `recorder.stop()` and BEFORE `drafts.create()` so the draft always points to the processed file. `PostRecordingProcessor` is `null`-safe (passes through when neither setting is enabled), so it's passed as nullable. `RecordingModule` provides `RecordingController` and must now inject `PostRecordingProcessor`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/RecordingModule.kt`

- [ ] **Step 1: Read both files**

  Read:
  - `app/src/main/java/com/sound2inat/app/recording/RecordingController.kt`
  - `app/src/main/java/com/sound2inat/app/di/RecordingModule.kt`

- [ ] **Step 2: Add processor param to DefaultRecordingController**

  Add import at top of `RecordingController.kt`:
  ```kotlin
  import com.sound2inat.inference.PostRecordingProcessor
  import java.io.File
  ```

  Add `processor` param to `DefaultRecordingController` constructor (after `ioDispatcher`):
  ```kotlin
  private val processor: PostRecordingProcessor? = null,
  ```

  In `stop()`, call `processor?.process(File(result.audioPath))` after `cancelJobs()` and before the `drafts.create*()` calls:
  ```kotlin
  override suspend fun stop() {
      val id = draftId ?: return
      val engine = activeEngine
      val finalDetections: List<AggregatedDetection> = if (engine != null) {
          engine.stop()
          activeAggregator?.snapshot().orEmpty()
      } else {
          emptyList()
      }
      val result = recorder.stop()
      cancelJobs()
      processor?.process(File(result.audioPath))   // ← add this line
      if (engine != null && finalDetections.isNotEmpty()) {
          drafts.createWithDetections(
              ...
          )
      } else {
          drafts.create(
              ...
          )
      }
      ...
  }
  ```

  > **Important:** Do NOT add `import java.io.File` if `File` is already imported. Check existing imports first.

- [ ] **Step 3: Update RecordingModule to inject PostRecordingProcessor**

  Add import in `RecordingModule.kt`:
  ```kotlin
  import com.sound2inat.inference.PostRecordingProcessor
  ```

  Add a new `@Provides @Singleton` function before `provideRecordingController`:
  ```kotlin
  @Provides @Singleton
  fun providePostRecordingProcessor(settings: Settings): PostRecordingProcessor =
      PostRecordingProcessor(settings)
  ```

  Update `provideRecordingController` to accept and pass `PostRecordingProcessor`:
  ```kotlin
  @Provides @Singleton
  @Suppress("LongParameterList")
  fun provideRecordingController(
      applicationScope: CoroutineScope,
      recorder: Recorder,
      location: LocationProvider,
      files: WavFileStore,
      drafts: DraftRepository,
      engineFactory: LiveInferenceEngineFactory?,
      regionFilter: RegionFilter,
      settings: Settings,
      processor: PostRecordingProcessor,
  ): RecordingController = DefaultRecordingController(
      applicationScope = applicationScope,
      recorder = recorder,
      location = location,
      files = files,
      drafts = drafts,
      engineFactory = engineFactory,
      minConfidence = settings.minConfidenceDisplay,
      regionFilter = regionFilter,
      regionFilterEnabled = settings.regionalFilterEnabled,
      regionRadiusKm = settings.regionRadiusKm,
      processor = processor,
  )
  ```

- [ ] **Step 4: Compile check**

  ```bash
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run unit tests to catch regressions**

  ```bash
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -30
  ```
  Expected: all tests PASS (or pre-existing failures only).

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/recording/RecordingController.kt \
          app/src/main/java/com/sound2inat/app/di/RecordingModule.kt
  git commit -m "feat: call PostRecordingProcessor in DefaultRecordingController.stop() after WAV finalised"
  ```

---

### Task 5: Remove dead spectral subtraction code from InferenceRunner + InferenceUseCase

**Context:** `InferenceRunner` has `spectralSubtractor` and `usePreprocessing` params that are currently **never set to true** in the production disk-based inference path — `ProductionInferenceJob` and `ProductionPerchAnalysisJob` both pass `spectralSubtractor` but leave `usePreprocessing = false` (the default), so no preprocessing is applied. Now that `PostRecordingProcessor` handles denoising at the file level, this dead code can be removed. `LiveInferenceEngine` has its own separate `usePreprocessing` / `spectralSubtractor` — do NOT touch those.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt`

- [ ] **Step 1: Read both files**

  Read both files in full before making any changes.

- [ ] **Step 2: Simplify InferenceRunner**

  Remove from the constructor:
  - `spectralSubtractor: SpectralSubtractor? = null`
  - `usePreprocessing: Boolean = false`

  Remove from `run()`:
  ```kotlin
  // Remove this:
  val prepared = if (usePreprocessing) highPassFilter(normalized, targetRate) else normalized
  // Replace references to `prepared` with `normalized`

  // Remove this line in the per-window loop:
  window = if (usePreprocessing) spectralSubtractor?.process(window) ?: window else window
  ```

  Update the Kdoc at the top of `InferenceRunner` to remove references to `usePreprocessing` / preprocessing. The simplified pipeline is: read → resample → normalize to [-1,1] → slice windows → YAMNet gate → model.predict().

  The `highPassFilter` function is still used by `LiveInferenceEngine` and `denoiseFull` — do NOT remove it from `AudioPreprocessor.kt`.

- [ ] **Step 3: Simplify InferenceUseCase — ProductionInferenceJob**

  In `ProductionInferenceJob.run()`:
  - Remove: `val spectralEnabled = settings.spectralSubtractionEnabled.first()`
  - Remove: `val subtractor = if (spectralEnabled) SpectralSubtractor() else null`
  - Remove: the `// Fresh SpectralSubtractor per model…` comment block
  - Remove: `spectralSubtractor = subtractor` from the `InferenceRunner(...)` constructor call

  The `InferenceRunner(...)` call should now look like:
  ```kotlin
  val runner = InferenceRunner(
      model,
      yamNetGate = activeGate,
  )
  ```

- [ ] **Step 4: Simplify InferenceUseCase — ProductionPerchAnalysisJob**

  In `ProductionPerchAnalysisJob.run()`:
  - Remove: `val subtractor = if (settings.spectralSubtractionEnabled.first()) { SpectralSubtractor() } else { null }`
  - Remove: `spectralSubtractor = subtractor` from the `InferenceRunner(...)` constructor call

  The `InferenceRunner(...)` call should now look like:
  ```kotlin
  val runner = InferenceRunner(
      model = perch,
      yamNetGate = gate,
      hardGate = hardGate,
  )
  ```

- [ ] **Step 5: Remove unused imports**

  In `InferenceUseCase.kt`, remove `import com.sound2inat.inference.SpectralSubtractor` if it's now unused (check — `SpectralSubtractor` might still be referenced via other code in the file).

- [ ] **Step 6: Compile check**

  ```bash
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -30
  ```
  Expected: BUILD SUCCESSFUL. If there are errors about `SpectralSubtractor` being unused, remove the import.

- [ ] **Step 7: Run unit tests**

  ```bash
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -30
  ```
  Look for tests that construct `InferenceRunner` with `spectralSubtractor` or `usePreprocessing` — update those test call sites to remove the now-deleted params.

- [ ] **Step 8: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/inference/InferenceRunner.kt \
          app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt
  git commit -m "refactor: remove dead spectralSubtractor/usePreprocessing from disk-based InferenceRunner path"
  ```

---

### Task 6: Settings UI — new toggles + merged Noise Reduction into Inference section + strings

**Context:**

Current UI layout (relevant sections):
- `InferenceSection`: min confidence slider + min windows slider
- `NoiseReductionSection`: spectral toggle + YAMNet toggle (separate `SectionCard` with `section_noise_reduction` title)

Spec wants all four toggles plus the two sliders in one "Inference" section:
- Raw audio source (new)
- Normalize recorded audio (new)
- Spectral noise reduction (existing, update subtitle to indicate post-recording)
- YAMNet biological gate (existing)

**Plan:** Merge `NoiseReductionSection` content into `InferenceSection`; remove the separate Noise Reduction `SectionCard`; add two new toggle rows at the top of `InferenceSection`. Update `SettingsUiState`, `SettingsViewModel`, `SettingsViewModelHilt` delegate, and `strings.xml`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Read all four files**

  Read:
  - `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt`
  - `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`
  - `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt`
  - `app/src/main/res/values/strings.xml`

- [ ] **Step 2: Update SettingsUiState.kt**

  Add two new fields to `SettingsUiState` with correct defaults:
  ```kotlin
  data class SettingsUiState(
      // ... existing fields ...
      val audioSourceRaw: Boolean = true,
      val normalizeAudio: Boolean = true,
      // ... rest of existing fields ...
  )
  ```

- [ ] **Step 3: Update SettingsViewModel constructor + init + setters**

  Add four new constructor params (after existing ones, before `externalScope`):
  ```kotlin
  private val audioSourceRawFlow: Flow<Boolean>,
  private val writeAudioSourceRaw: suspend (Boolean) -> Unit,
  private val normalizeAudioFlow: Flow<Boolean>,
  private val writeNormalizeAudio: suspend (Boolean) -> Unit,
  ```

  Add two `init` collectors (after `themeModeFlow` collector):
  ```kotlin
  scope.launch {
      audioSourceRawFlow.collect { v ->
          _state.value = _state.value.copy(audioSourceRaw = v)
      }
  }
  scope.launch {
      normalizeAudioFlow.collect { v ->
          _state.value = _state.value.copy(normalizeAudio = v)
      }
  }
  ```

  Add two public setter fns (after `setThemeMode`):
  ```kotlin
  fun setAudioSourceRaw(v: Boolean) { scope.launch { writeAudioSourceRaw(v) } }
  fun setNormalizeAudio(v: Boolean) { scope.launch { writeNormalizeAudio(v) } }
  ```

  Update `SettingsViewModelHilt.delegate` construction — add to the `SettingsViewModel(...)` call:
  ```kotlin
  audioSourceRawFlow = settings.audioSourceRaw,
  writeAudioSourceRaw = { settings.setAudioSourceRaw(it) },
  normalizeAudioFlow = settings.normalizeAudio,
  writeNormalizeAudio = { settings.setNormalizeAudio(it) },
  ```

- [ ] **Step 4: Add string resources to strings.xml**

  Add after the existing spectral/yamnet strings:
  ```xml
  <string name="label_audio_source_raw">Raw audio source</string>
  <string name="label_audio_source_raw_sub">Skips AGC and noise suppression</string>
  <string name="label_normalize_audio">Normalize recorded audio</string>
  <string name="label_normalize_audio_sub">Peak-normalizes the WAV after recording stops</string>
  <string name="label_spectral_noise_reduction_sub">Applies spectral denoising live and post-recording</string>
  ```

- [ ] **Step 5: Update SettingsScreen.kt**

  **5a.** Remove the separate Noise Reduction section card (find the `SectionCard(title = stringResource(R.string.section_noise_reduction))` block and delete it entirely including its `NoiseReductionSection` call).

  **5b.** Expand `InferenceSection` to include all four toggles, using a helper composable `ToggleRow`:

  ```kotlin
  @Suppress("FunctionNaming")
  @Composable
  private fun ToggleRow(label: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
          Column(modifier = Modifier.weight(1f)) {
              Text(label)
              if (subtitle != null) {
                  Text(subtitle, style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
          }
          Switch(checked = checked, onCheckedChange = onCheckedChange)
      }
  }
  ```

  Replace the existing `InferenceSection` function body to include all four toggles above the sliders:

  ```kotlin
  @Suppress("FunctionNaming")
  @Composable
  private fun InferenceSection(state: SettingsUiState, vm: SettingsViewModel) {
      ToggleRow(
          label = stringResource(R.string.label_audio_source_raw),
          subtitle = stringResource(R.string.label_audio_source_raw_sub),
          checked = state.audioSourceRaw,
          onCheckedChange = { vm.setAudioSourceRaw(it) },
      )
      ToggleRow(
          label = stringResource(R.string.label_normalize_audio),
          subtitle = stringResource(R.string.label_normalize_audio_sub),
          checked = state.normalizeAudio,
          onCheckedChange = { vm.setNormalizeAudio(it) },
      )
      ToggleRow(
          label = stringResource(R.string.label_spectral_noise_reduction),
          subtitle = stringResource(R.string.label_spectral_noise_reduction_sub),
          checked = state.spectralSubtractionEnabled,
          onCheckedChange = { vm.setSpectralSubtractionEnabled(it) },
      )
      ToggleRow(
          label = stringResource(R.string.label_yamnet_gate),
          checked = state.yamNetGateEnabled,
          onCheckedChange = { vm.setYamNetGateEnabled(it) },
      )
      HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
      Text(stringResource(R.string.label_min_confidence, "%.2f".format(state.minConfidenceDisplay)))
      Slider(
          value = state.minConfidenceDisplay,
          onValueChange = { vm.setMinConfidence(it) },
          valueRange = MIN_CONF..MAX_CONF,
      )
      Text(stringResource(R.string.label_min_windows, state.minWindows))
      Slider(
          value = state.minWindows.toFloat(),
          onValueChange = { vm.setMinWindows(it.toInt().coerceIn(MIN_MIN_WINDOWS, MAX_MIN_WINDOWS)) },
          valueRange = MIN_MIN_WINDOWS.toFloat()..MAX_MIN_WINDOWS.toFloat(),
          steps = MAX_MIN_WINDOWS - MIN_MIN_WINDOWS - 1,
      )
  }
  ```

  Delete or empty the `NoiseReductionSection` function (remove entirely since it's no longer called).

  > **Note:** `MIN_CONF`, `MAX_CONF`, `MIN_MIN_WINDOWS`, `MAX_MIN_WINDOWS` must already exist as private constants in the file. If they don't, check where they are defined and reference them correctly.

- [ ] **Step 6: Compile check**

  ```bash
  ./gradlew :app:compileDebugKotlin 2>&1 | tail -40
  ```
  Expected: BUILD SUCCESSFUL. Fix any unresolved references or unused import warnings.

- [ ] **Step 7: Full unit test run**

  ```bash
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -30
  ```
  Expected: all tests PASS. If `SettingsViewModel` tests exist and construct the ViewModel directly, add the four new params with default no-ops:
  ```kotlin
  audioSourceRawFlow = kotlinx.coroutines.flow.flowOf(true),
  writeAudioSourceRaw = {},
  normalizeAudioFlow = kotlinx.coroutines.flow.flowOf(true),
  writeNormalizeAudio = {},
  ```

- [ ] **Step 8: Commit**

  ```bash
  git add app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt \
          app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt \
          app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt \
          app/src/main/res/values/strings.xml
  git commit -m "feat: Settings UI — audio source + normalize toggles; merge noise reduction into inference section"
  ```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Covered by |
|-----------------|-----------|
| `audioSourceRaw` setting, default `true` | Task 1 |
| `AndroidAudioRecordSource(preferRaw)` param | Task 1 |
| `SwappableModule` wires `preferRaw` from Settings | Task 1 |
| Settings UI: Raw audio source toggle in Inference | Task 6 |
| `AudioNormalizer.normalizeFile(src, dst)` | Task 2 |
| `normalizeAudio` setting, default `true` | Task 1 |
| Settings UI: Normalize audio toggle | Task 6 |
| `PostRecordingProcessor.process(wavFile)` — denoise then normalize | Task 3 |
| `DefaultRecordingController.stop()` calls processor between recorder.stop() and drafts.create() | Task 4 |
| Spectral denoising in `ProductionInferenceJob` removed | Task 5 |
| `InferenceRunner.spectralSubtractor` + `usePreprocessing` removed | Task 5 |
| Spectral subtitle updated ("live + post-recording") | Task 6 |
| Review playback unchanged (WAV on disk is already processed) | No change needed |
| iNat upload unchanged (reads same processed WAV) | No change needed |
| Live inference unchanged — `LiveInferenceEngine` keeps its `spectralSubtractor` | Task 5 scope is limited to `InferenceRunner` / `InferenceUseCase` |

**No placeholders found.**

**Type consistency:** `writeWav(samples: ShortArray, sampleRateHz: Int, dst: File)` defined in Task 2; used in Task 3 (`PostRecordingProcessor`). `WavReader.readMono16(file: File): Pair<ShortArray, Int>` is `internal` in the inference package — accessible from both `AudioNormalizer` and `PostRecordingProcessor` since they're in the same package. ✓

**Potential issue — `renameTo` atomicity:** On Android, `File.renameTo()` between the same filesystem is atomic. The fallback `copyTo + delete` handles the cross-filesystem edge case. ✓

**Potential issue — `SettingsViewModel` constructor params:** The constructor is very long (20+ params). Adding 4 more params is consistent with existing style. All existing tests that construct `SettingsViewModel` directly will need the 4 new params added. The step mentions this. ✓
