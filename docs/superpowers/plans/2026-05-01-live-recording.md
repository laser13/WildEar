# Live Recording Screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **For each task:** before writing any code, re-read the task description and the spec section it implements. Look for unstated assumptions, off-by-one risks, type/signature mismatches with earlier tasks, and missing test cases. Flag any issue back to the controller before starting.

**Goal:** Превратить RecordingScreen в Merlin-style live экран — скроллящаяся спектрограмма + живой список карточек видов, BirdNET крутится во время записи.

**Architecture:** Recorder получает второй tap (`SharedFlow<FloatArray>`). Параллельно подключаются `LiveSpectrogram` (STFT → bitmap) и `LiveInferenceEngine` (HPF → spectral subtraction → YamNet → BirdNET, hop 1.5 s / win 3 s). Stop фиксирует live-детекции как финальные, draft создаётся со статусом `PENDING_REVIEW` минуя offline-инференс. Perch-on-demand добавляется как кнопка в Review.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, kotlinx.coroutines (Channel + SharedFlow), TFLite (BirdNET v2.4 + YAMNet), JUnit4 + Truth + Mockito-Kotlin, Robolectric (для UI-юнит-тестов).

**Spec:** [docs/superpowers/specs/2026-05-01-live-recording-design.md](../specs/2026-05-01-live-recording-design.md)

---

## Pre-flight

- [ ] **Step 0: Create dev branch from current**

```bash
git checkout -b feat/live-recording
git status   # clean working tree expected
```

---

## Task 1: Recorder audio tap

**Self-contained context:**
- Текущий [Recorder.kt](../../../app/src/main/java/com/sound2inat/recorder/Recorder.kt) пишет ShortArray → WAV и эмитит RMS history.
- Нужен второй output: `SharedFlow<FloatArray>` с теми же блоками, но конвертированными в `[-1, 1]`. Это будет питать LiveSpectrogram (Task 2) и LiveInferenceEngine (Task 3).
- WAV-write путь не ломаем — это только дополнительный sink.
- BUFFER_FRAMES = 4096 → при 48 kHz это ~85 ms на блок. Подходит.
- Конверсия `s / Short.MAX_VALUE.toFloat()` уже используется в `InferenceRunner.kt:47`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/recorder/Recorder.kt`
- Test: `app/src/test/java/com/sound2inat/recorder/DefaultRecorderTest.kt` (existing — extend)

- [ ] **Step 1: Read existing recorder test to match patterns**

```bash
cat app/src/test/java/com/sound2inat/recorder/DefaultRecorderTest.kt | head -80
```
Note the fake `AudioRecordSource` pattern and how `runTest` is used.

- [ ] **Step 2: Write failing test for FloatArray emission**

Add to `app/src/test/java/com/sound2inat/recorder/DefaultRecorderTest.kt`:

```kotlin
@Test
fun `audioBlocks emits float blocks scaled to -1..1`() = runTest {
    val source = FakeAudioRecordSource(
        blocks = listOf(
            shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, 16384),
        ),
    )
    val target = tempFile()
    val recorder = DefaultRecorder(source, externalScope = backgroundScope)
    val collected = mutableListOf<FloatArray>()
    val collectorJob = backgroundScope.launch {
        recorder.audioBlocks.collect { collected += it }
    }
    recorder.start(target)
    runCurrent()
    advanceUntilIdle()
    recorder.stop()
    collectorJob.cancel()

    assertThat(collected).hasSize(1)
    assertThat(collected[0][0]).isWithin(1e-4f).of(0f)
    assertThat(collected[0][1]).isWithin(1e-4f).of(1f)
    assertThat(collected[0][2]).isLessThan(-0.99f)
    assertThat(collected[0][3]).isWithin(1e-3f).of(16384f / Short.MAX_VALUE)
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.recorder.DefaultRecorderTest.audioBlocks*"
```
Expected: compile error — `Recorder` has no `audioBlocks` field.

- [ ] **Step 4: Add `audioBlocks` to Recorder interface and impl**

In `app/src/main/java/com/sound2inat/recorder/Recorder.kt`, replace the `Recorder` interface:

```kotlin
interface Recorder {
    val rmsLevel: StateFlow<Float>
    val rmsHistory: StateFlow<FloatArray>

    /**
     * Hot stream of raw PCM blocks scaled to [-1, 1], emitted as the recorder
     * reads from the audio source. Live consumers (spectrogram, BirdNET) collect
     * this in parallel with the WAV writer. Backed by a SharedFlow with
     * extraBufferCapacity = 8 and DROP_OLDEST overflow — slow consumers cannot
     * stall the recording. Empty after [start] until the first block arrives.
     */
    val audioBlocks: SharedFlow<FloatArray>

    /** Sample rate of [audioBlocks] (Hz). Same as the WAV file's sample rate. */
    val sampleRate: Int

    suspend fun start(target: File)
    suspend fun stop(): RecordingResult
    fun cancel()

    companion object {
        const val HISTORY_SIZE = 200
    }
}
```

In `DefaultRecorder`:

```kotlin
private val _audioBlocks = MutableSharedFlow<FloatArray>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
override val audioBlocks: SharedFlow<FloatArray> = _audioBlocks.asSharedFlow()

override val sampleRate: Int get() = source.sampleRate

private suspend fun pump() {
    val buf = ShortArray(BUFFER_FRAMES)
    while (true) {
        val n = source.read(buf, 0, buf.size)
        if (n <= 0) break
        writer?.writeShorts(buf, 0, n)
        // Emit float copy in parallel — separate array because the short
        // buffer is reused across iterations.
        val floatBlock = FloatArray(n) { i -> buf[i] / Short.MAX_VALUE.toFloat() }
        _audioBlocks.tryEmit(floatBlock)
        val rms = computeRms(buf, n)
        _rms.value = rms
        pushRms(rms)
    }
}
```

Add imports: `kotlinx.coroutines.channels.BufferOverflow`, `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.SharedFlow`, `kotlinx.coroutines.flow.asSharedFlow`.

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.recorder.DefaultRecorderTest.*"
```
Expected: all green.

- [ ] **Step 6: Run full test suite to catch regressions**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: green. Pay attention to RecordingViewModelTest — it may depend on Recorder mock and need the new fields stubbed.

- [ ] **Step 7: Build APK to confirm Hilt graph compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sound2inat/recorder/Recorder.kt \
        app/src/test/java/com/sound2inat/recorder/DefaultRecorderTest.kt
git commit -m "feat(recorder): expose audioBlocks SharedFlow for live consumers"
```

---

## Task 2: Live spectrogram pipeline

**Self-contained context:**
- STFT с FFT-size 2048, hop 512 на 48 kHz даёт ~93.75 колонок/сек, 1025 bin'ов на колонку.
- 10-секундное окно на экране = 938 колонок. Bitmap 938×256 (агрессивно даунсэмпленный по частоте) — ~960 KB ARGB.
- Для FFT повторно использовать pure-Kotlin Cooley-Tukey из `SpectralSubtractor.fftInPlace` (см. [AudioPreprocessor.kt:139-173](../../../app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt#L139-L173)) — вынести в общий файл.
- Окно Hann для гладкого спектра.
- dB-нормализация: `20*log10(magnitude + 1e-10)`, клиппинг в `[-80, 0] dB`, маппинг в `[0, 1]` для рендера.
- Y-ось: log-scale частот для визуальной плотности на низких частотах (где птицы). Биннинг 1025 → 256 — сжимать линейно сначала, log-mapping позже (упрощение для Task 2; усовершенствуем при необходимости).

**Files:**
- Create: `app/src/main/java/com/sound2inat/audio/Fft.kt`
- Create: `app/src/main/java/com/sound2inat/audio/Spectrogram.kt`
- Create: `app/src/main/java/com/sound2inat/audio/SpectrogramRingBuffer.kt`
- Create: `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt` (delegate FFT to new shared module)
- Test: `app/src/test/java/com/sound2inat/audio/SpectrogramTest.kt`
- Test: `app/src/test/java/com/sound2inat/audio/SpectrogramRingBufferTest.kt`

- [ ] **Step 1: Write failing test for shared FFT**

Create `app/src/test/java/com/sound2inat/audio/FftTest.kt`:

```kotlin
package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

class FftTest {
    @Test
    fun `pure tone produces peak at expected bin`() {
        val n = 1024
        val sampleRate = 48_000
        val freqHz = 1000.0
        val signal = FloatArray(n * 2)  // interleaved real/imag, imag = 0
        for (i in 0 until n) {
            signal[2 * i] = cos(2.0 * PI * freqHz * i / sampleRate).toFloat()
        }
        Fft.inPlace(signal, n, inverse = false)
        val mag = FloatArray(n / 2 + 1) { i ->
            val re = signal[2 * i]; val im = signal[2 * i + 1]
            sqrt(re * re + im * im)
        }
        val peakBin = mag.indices.maxByOrNull { mag[it] }!!
        val expectedBin = (freqHz * n / sampleRate).toInt()
        assertThat(peakBin).isAnyOf(expectedBin - 1, expectedBin, expectedBin + 1)
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.audio.FftTest"
```
Expected: compile error — `Fft` not defined.

- [ ] **Step 3: Extract FFT into shared module**

Create `app/src/main/java/com/sound2inat/audio/Fft.kt`:

```kotlin
package com.sound2inat.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative Cooley-Tukey radix-2 FFT, in-place on interleaved real/imag float array.
 * Length `data.size` must be `2 * n` and `n` must be a power of two.
 *
 * For inverse, caller divides each output by `n` (not done here so callers can
 * skip the scaling when only relative magnitudes matter).
 */
object Fft {
    @Suppress("NestedBlockDepth", "LongMethod")
    fun inPlace(data: FloatArray, n: Int, inverse: Boolean) {
        require(data.size == 2 * n) { "data.size must be 2*n (got ${data.size}, n=$n)" }
        require(n > 0 && (n and (n - 1)) == 0) { "n must be a power of two (got $n)" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var tmp = data[2 * i]; data[2 * i] = data[2 * j]; data[2 * j] = tmp
                tmp = data[2 * i + 1]; data[2 * i + 1] = data[2 * j + 1]; data[2 * j + 1] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val wBaseRe = cos(ang); val wBaseIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0; var wIm = 0.0
                for (k in 0 until len / 2) {
                    val u = i + k; val v = i + k + len / 2
                    val uRe = data[2 * u]; val uIm = data[2 * u + 1]
                    val vRe = data[2 * v]; val vIm = data[2 * v + 1]
                    val tvRe = (vRe * wRe - vIm * wIm).toFloat()
                    val tvIm = (vRe * wIm + vIm * wRe).toFloat()
                    data[2 * u] = uRe + tvRe; data[2 * u + 1] = uIm + tvIm
                    data[2 * v] = uRe - tvRe; data[2 * v + 1] = uIm - tvIm
                    val nWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe; wRe = nWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
```

In `AudioPreprocessor.kt`, replace the private `fftInPlace` method with a delegation:

```kotlin
private fun fftInPlace(data: FloatArray, n: Int, inverse: Boolean) =
    com.sound2inat.audio.Fft.inPlace(data, n, inverse)
```

- [ ] **Step 4: Run FftTest + existing AudioPreprocessor tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.audio.FftTest" \
                                  --tests "com.sound2inat.inference.AudioPreprocessorTest"
```
Expected: green.

- [ ] **Step 5: Write failing tests for Spectrogram**

Create `app/src/test/java/com/sound2inat/audio/SpectrogramTest.kt`:

```kotlin
package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrogramTest {
    @Test
    fun `accumulates samples and emits one column per hop`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 256, sampleRateHz = 48_000)
        val cols = mutableListOf<FloatArray>()
        cols += s.process(FloatArray(255))   // not enough for first hop
        assertThat(cols.flatMap { it.toList() }).isEmpty()
        cols += s.process(FloatArray(1))     // total 256 → first hop boundary, but
        // first column needs full fftSize buffered → still empty
        cols += s.process(FloatArray(1024 - 256))  // total = 1024 → first column
        assertThat(cols.last()).hasLength(1024 / 2 + 1)
    }

    @Test
    fun `pure tone produces peak at expected frequency bin`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 1024, sampleRateHz = 48_000)
        val freqHz = 4000.0
        val signal = FloatArray(1024) { i ->
            sin(2.0 * PI * freqHz * i / 48_000).toFloat()
        }
        val cols = s.process(signal)
        assertThat(cols).hasSize(1)
        val peak = cols[0].indices.maxByOrNull { cols[0][it] }!!
        val expected = (freqHz * 1024 / 48_000).toInt()
        assertThat(peak).isAnyOf(expected - 1, expected, expected + 1)
    }

    @Test
    fun `dB normalisation clamps at silence floor`() {
        val s = Spectrogram(fftSize = 1024, hopSize = 1024, sampleRateHz = 48_000)
        val cols = s.process(FloatArray(1024))   // pure silence
        assertThat(cols).hasSize(1)
        // After Hann window + FFT of zeros + dB normalisation: every bin = floor.
        for (v in cols[0]) assertThat(v).isAtMost(Spectrogram.DB_FLOOR + 0.1f)
    }
}
```

- [ ] **Step 6: Confirm test fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.audio.SpectrogramTest"
```
Expected: compile error — Spectrogram not defined.

- [ ] **Step 7: Implement Spectrogram**

Create `app/src/main/java/com/sound2inat/audio/Spectrogram.kt`:

```kotlin
package com.sound2inat.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Streaming STFT. Buffers incoming samples internally; emits one dB-magnitude
 * column per [hopSize] samples consumed. Each column has [fftSize] / 2 + 1
 * non-redundant bins, in dB clamped to [[DB_FLOOR], 0].
 *
 * NOT thread-safe — one instance per audio stream.
 */
class Spectrogram(
    val fftSize: Int = 2048,
    val hopSize: Int = 512,
    val sampleRateHz: Int = 48_000,
) {
    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be power of 2" }
        require(hopSize in 1..fftSize)
    }

    private val window = FloatArray(fftSize) { i ->
        // Hann
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private val buffer = FloatArray(fftSize)
    private var filled = 0      // valid samples in [0, filled)
    private var pendingHop = 0  // samples accumulated since last column

    /** Adds samples; returns 0..N new dB columns produced. */
    fun process(block: FloatArray): List<FloatArray> {
        if (block.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(2)
        var idx = 0
        while (idx < block.size) {
            val needFill = (fftSize - filled).coerceAtMost(block.size - idx)
            if (needFill > 0) {
                System.arraycopy(block, idx, buffer, filled, needFill)
                filled += needFill
                idx += needFill
            }
            if (filled < fftSize) return out
            // After first full fill, every hopSize new samples produces a new column.
            if (pendingHop == 0 || pendingHop >= hopSize) {
                out += computeColumn()
                pendingHop = 0
                // Slide buffer left by hopSize for next column.
                System.arraycopy(buffer, hopSize, buffer, 0, fftSize - hopSize)
                filled = fftSize - hopSize
            } else {
                val take = (hopSize - pendingHop).coerceAtMost(block.size - idx)
                if (take == 0) return out
                pendingHop += take  // (samples were already copied in needFill above,
                // but pendingHop tracks cadence between columns)
            }
        }
        return out
    }

    private val scratch = FloatArray(fftSize * 2)

    private fun computeColumn(): FloatArray {
        scratch.fill(0f)
        for (i in 0 until fftSize) scratch[2 * i] = buffer[i] * window[i]
        Fft.inPlace(scratch, fftSize, inverse = false)
        val out = FloatArray(fftSize / 2 + 1)
        for (i in out.indices) {
            val re = scratch[2 * i]; val im = scratch[2 * i + 1]
            val mag = sqrt((re * re + im * im).toDouble()).toFloat()
            val db = 20f * (ln(mag + 1e-10f) / LN10).toFloat()
            out[i] = db.coerceIn(DB_FLOOR, 0f)
        }
        return out
    }

    companion object {
        const val DB_FLOOR = -80f
        private const val LN10 = 2.302585092994046f
    }
}
```

NOTE: The `process` cadence logic in the body has subtle interaction between `filled` and `pendingHop`. The first column needs `fftSize` samples; subsequent columns need only `hopSize` more. The buffer slide preserves overlap. Verify against tests — adjust if cadence is off.

- [ ] **Step 8: Run tests; iterate Spectrogram impl until green**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.audio.SpectrogramTest"
```

If `accumulates samples` test fails because of cadence subtlety, simplify the impl: track only `filled` and the rule "produce a column each time `filled == fftSize`, then slide by hopSize". Drop `pendingHop`.

- [ ] **Step 9: Write SpectrogramRingBuffer test**

Create `app/src/test/java/com/sound2inat/audio/SpectrogramRingBufferTest.kt`:

```kotlin
package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpectrogramRingBufferTest {
    @Test
    fun `appends columns up to capacity`() {
        val r = SpectrogramRingBuffer(capacity = 3, bins = 4)
        r.append(floatArrayOf(1f, 1f, 1f, 1f))
        r.append(floatArrayOf(2f, 2f, 2f, 2f))
        assertThat(r.size).isEqualTo(2)
        assertThat(r.column(0)[0]).isEqualTo(1f)
        assertThat(r.column(1)[0]).isEqualTo(2f)
    }

    @Test
    fun `overflow drops oldest`() {
        val r = SpectrogramRingBuffer(capacity = 3, bins = 4)
        for (k in 1..5) r.append(FloatArray(4) { k.toFloat() })
        assertThat(r.size).isEqualTo(3)
        assertThat(r.column(0)[0]).isEqualTo(3f)
        assertThat(r.column(2)[0]).isEqualTo(5f)
    }

    @Test
    fun `reject column with wrong bin count`() {
        val r = SpectrogramRingBuffer(capacity = 3, bins = 4)
        try {
            r.append(floatArrayOf(1f, 2f, 3f))
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
```

- [ ] **Step 10: Implement SpectrogramRingBuffer**

Create `app/src/main/java/com/sound2inat/audio/SpectrogramRingBuffer.kt`:

```kotlin
package com.sound2inat.audio

/**
 * Fixed-capacity ring buffer of dB columns, oldest-first when read by index.
 * Used by [com.sound2inat.app.ui.recording.LiveSpectrogramView] to render the
 * last N columns as a scrolling spectrogram.
 *
 * NOT thread-safe — synchronise externally if multiple threads append.
 */
class SpectrogramRingBuffer(
    val capacity: Int,
    val bins: Int,
) {
    private val data = Array(capacity) { FloatArray(bins) }
    private var head = 0   // index of next slot to write
    var size: Int = 0
        private set

    fun append(column: FloatArray) {
        require(column.size == bins) { "expected $bins bins, got ${column.size}" }
        System.arraycopy(column, 0, data[head], 0, bins)
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Read column by logical index 0..size-1, oldest first. */
    fun column(index: Int): FloatArray {
        require(index in 0 until size)
        val pos = (head - size + index + capacity) % capacity
        return data[pos]
    }
}
```

- [ ] **Step 11: Run Spectrogram + ring buffer tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.audio.*"
```
Expected: green.

- [ ] **Step 12: Implement LiveSpectrogramView (no automated test — manual visual check)**

Create `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`:

```kotlin
package com.sound2inat.app.ui.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.sound2inat.audio.Spectrogram
import com.sound2inat.audio.SpectrogramRingBuffer
import kotlinx.coroutines.flow.SharedFlow

private const val BITMAP_WIDTH_COLS = 940     // ~10 sec at 48k / hop 512
private const val BITMAP_HEIGHT_BINS = 256    // log-binned from 1025 to 256
private const val MAX_BINS = Spectrogram.let { 2048 / 2 + 1 }   // 1025

/**
 * Renders [audioBlocks] as a scrolling dB heatmap. Internally:
 *   1. STFT via [Spectrogram] (FFT 2048, hop 512).
 *   2. Each column is log-binned from 1025 -> 256 bins (low frequencies stretched).
 *   3. Bitmap shifted left by 1 column per new dB column; new column drawn at right edge.
 *
 * Color map: dark navy (DB_FLOOR) → cyan → yellow → white (0 dB).
 * Updates ~94 columns/sec at 48k input. Rendering happens on UI thread but
 * the heavy STFT runs in a coroutine on the parent scope.
 */
@Suppress("FunctionNaming")
@Composable
fun LiveSpectrogramView(
    audioBlocks: SharedFlow<FloatArray>,
    sampleRateHz: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember { Bitmap.createBitmap(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS, Bitmap.Config.ARGB_8888) }
    val ringBuffer = remember { SpectrogramRingBuffer(BITMAP_WIDTH_COLS, BITMAP_HEIGHT_BINS) }
    val spectrogram = remember(sampleRateHz) {
        Spectrogram(fftSize = 2048, hopSize = 512, sampleRateHz = sampleRateHz)
    }
    var revision by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }

    LaunchedEffect(audioBlocks) {
        audioBlocks.collect { block ->
            val columns = spectrogram.process(block)
            for (col in columns) {
                val downsampled = logBinDown(col, BITMAP_HEIGHT_BINS)
                ringBuffer.append(downsampled)
                drawColumnIntoBitmap(bitmap, ringBuffer)
            }
            if (columns.isNotEmpty()) revision++   // trigger recomposition
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap.recycle() }
    }

    // 'revision' read forces recomposition when bitmap mutates.
    @Suppress("UNUSED_EXPRESSION") revision
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

private fun logBinDown(src: FloatArray, outBins: Int): FloatArray {
    // Log-frequency mapping: low bins (where birds live) get more vertical space.
    val out = FloatArray(outBins)
    val srcMaxIdx = src.size - 1
    val logMin = 1.0   // skip DC
    val logMax = kotlin.math.ln(srcMaxIdx.toDouble())
    for (j in 0 until outBins) {
        val frac = j.toDouble() / (outBins - 1)
        val srcIdxLog = logMin + frac * (logMax - logMin)
        val srcIdx = kotlin.math.exp(srcIdxLog).toInt().coerceIn(1, srcMaxIdx)
        out[j] = src[srcIdx]
    }
    return out
}

private fun drawColumnIntoBitmap(bm: Bitmap, ring: SpectrogramRingBuffer) {
    val w = bm.width; val h = bm.height
    // Draw oldest→newest left→right. Cheaper than per-frame full repaint:
    // shift left by N pixels (where N = newly appended columns) and only draw
    // the new column. But for first version, do full repaint (simpler).
    val pixels = IntArray(w * h)
    val drawCols = ring.size.coerceAtMost(w)
    val xOffset = w - drawCols
    for (x in 0 until drawCols) {
        val col = ring.column(x)
        for (y in 0 until h) {
            val db = col[h - 1 - y]   // flip so high freqs on top
            pixels[y * w + (xOffset + x)] = dbToColor(db)
        }
    }
    bm.setPixels(pixels, 0, w, 0, 0, w, h)
}

private fun dbToColor(db: Float): Int {
    val t = ((db - Spectrogram.DB_FLOOR) / -Spectrogram.DB_FLOOR).coerceIn(0f, 1f)
    // Magma-ish: dark navy → red → yellow → white
    val r = (255 * t).toInt().coerceIn(0, 255)
    val g = (255 * (t * t)).toInt().coerceIn(0, 255)
    val b = (255 * (1 - (1 - t) * (1 - t))).toInt().coerceIn(0, 255)
    val a = if (db <= Spectrogram.DB_FLOOR + 1f) 0x60 else 0xFF
    return Color.argb(a, r, g, b)
}
```

NOTE: `drawColumnIntoBitmap` does full repaint on each call — fine for ~94 calls/sec at 940×256 = 240k pixels per call (~22 MPix/sec total). If profiling shows it's slow on dev devices, optimise to per-column shift in a follow-up. Don't preoptimise now.

- [ ] **Step 13: Build to confirm UI compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 14: Manual visual smoke (deferred until Task 4)**

The view isn't wired into any screen yet — that happens in Task 4. Skip live device check here; just confirm the file compiles.

- [ ] **Step 15: Commit**

```bash
git add app/src/main/java/com/sound2inat/audio/ \
        app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt \
        app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt \
        app/src/test/java/com/sound2inat/audio/
git commit -m "feat(audio): add Spectrogram, ring buffer, and LiveSpectrogramView"
```

---

## Task 3: LiveInferenceEngine

**Self-contained context:**
- Sliding window 3 sec, hop 1.5 sec → BirdNET expects 144_000 samples at 48 kHz.
- Pipeline per window: HPF (`highPassFilter`) → spectral subtraction (one `SpectralSubtractor` instance per recording) → YAMNet gate → BirdNET.
- Reuse [InferenceRunner.kt:60-77](../../../app/src/main/java/com/sound2inat/inference/InferenceRunner.kt#L60-L77) loop logic — but stream-driven instead of slicing a finished WAV.
- Backpressure: `Channel<Window>(capacity = 8, BufferOverflow.DROP_OLDEST)` + `backlog: StateFlow<Int>`. Worker logs warning if backlog > 3.
- Threshold filter is NOT applied here — emit all `WindowPrediction` raw; downstream `DetectionAggregator` filters.
- `WindowPrediction.startMs/endMs` measured from start-of-recording (not wall clock).
- On `stop()`: stop accepting new windows, drain queue with timeout 5 sec, then close.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`
- Test: `app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt`:

```kotlin
package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveInferenceEngineTest {

    private val sampleRateHz = 48_000
    private val windowSamples = sampleRateHz * 3   // 3 sec
    private val hopSamples = sampleRateHz * 3 / 2  // 1.5 sec

    private fun fakeModel(label: String, confidence: Float) = object : BioacousticModel {
        override val modelId = "fake"
        override val modelVersion = "0"
        override val expectedSampleRateHz = 48_000
        override val windowMs = 3_000L
        override suspend fun load(modelFile: java.io.File, labelsFile: java.io.File) {}
        override suspend fun predict(
            pcmFloat32: FloatArray, sampleRateHz: Int,
            latitude: Double?, longitude: Double?, observedAtMillis: Long,
            windowStartMs: Long, windowEndMs: Long,
        ): List<WindowPrediction> = listOf(
            WindowPrediction(
                startMs = windowStartMs, endMs = windowEndMs,
                taxonScientificName = label, taxonCommonName = null,
                confidence = confidence, source = modelId,
            ),
        )
        override fun close() {}
    }

    @Test
    fun `emits prediction per window when enough samples buffered`() = runTest(UnconfinedTestDispatcher()) {
        val model = fakeModel("Turdus merula", 0.9f)
        val engine = LiveInferenceEngine(
            model = model,
            yamNetGate = null,
            spectralSubtractor = SpectralSubtractor(),
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            hopSamples = hopSamples,
        )
        val collected = mutableListOf<WindowPrediction>()
        val collector = backgroundScope.launch { engine.predictions.toList(collected) }
        engine.start(backgroundScope)
        engine.feed(FloatArray(windowSamples) { 0.5f })
        runCurrent()
        engine.stop()

        assertThat(collected.size).isAtLeast(1)
        assertThat(collected[0].taxonScientificName).isEqualTo("Turdus merula")
        assertThat(collected[0].endMs - collected[0].startMs).isEqualTo(3_000L)
        collector.cancel()
    }

    @Test
    fun `subsequent windows hop by 1500ms`() = runTest(UnconfinedTestDispatcher()) {
        val model = fakeModel("X x", 0.5f)
        val engine = LiveInferenceEngine(
            model = model, yamNetGate = null, spectralSubtractor = SpectralSubtractor(),
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        val collected = mutableListOf<WindowPrediction>()
        val collector = backgroundScope.launch { engine.predictions.toList(collected) }
        engine.start(backgroundScope)
        engine.feed(FloatArray(windowSamples + hopSamples) { 0.1f })
        runCurrent()
        engine.stop()

        assertThat(collected).hasSize(2)
        assertThat(collected[1].startMs).isEqualTo(1_500L)
        assertThat(collected[1].endMs).isEqualTo(4_500L)
        collector.cancel()
    }

    @Test
    fun `gate rejection skips model call but still drains backlog`() = runTest(UnconfinedTestDispatcher()) {
        var called = 0
        val model = object : BioacousticModel by fakeModel("X", 0.5f) {
            override suspend fun predict(
                pcmFloat32: FloatArray, sampleRateHz: Int,
                latitude: Double?, longitude: Double?, observedAtMillis: Long,
                windowStartMs: Long, windowEndMs: Long,
            ): List<WindowPrediction> { called++; return emptyList() }
        }
        val rejectingGate = object : YamNetGate {
            override suspend fun isBiological(pcmFloat32: FloatArray, sampleRateHz: Int) = false
        }
        val engine = LiveInferenceEngine(
            model = model, yamNetGate = rejectingGate, spectralSubtractor = null,
            sampleRateHz = sampleRateHz, windowSamples = windowSamples, hopSamples = hopSamples,
        )
        engine.start(backgroundScope)
        engine.feed(FloatArray(windowSamples) { 0.1f })
        runCurrent()
        engine.stop()

        assertThat(called).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.LiveInferenceEngineTest"
```
Expected: compile error — `LiveInferenceEngine` not defined.

- [ ] **Step 3: Implement LiveInferenceEngine**

Create `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`:

```kotlin
package com.sound2inat.inference

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Streaming live inference: buffers float audio blocks, slices into 3-sec
 * windows hopped by 1.5 sec, applies HPF + spectral subtraction + YAMNet gate,
 * runs [model] (typically BirdNET) per surviving window. Emits raw
 * [WindowPrediction] without confidence threshold filtering — downstream
 * [DetectionAggregator] handles that.
 *
 * Backpressure: windows are queued via a [Channel] with capacity 8 and
 * DROP_OLDEST overflow — slow inference cannot stall recording or balloon
 * memory. [backlog] reports current queue depth.
 *
 * NOT thread-safe wrt the [feed] method — called only from the recorder pump.
 */
class LiveInferenceEngine(
    private val model: BioacousticModel,
    private val yamNetGate: YamNetGate?,
    private val spectralSubtractor: SpectralSubtractor?,
    private val sampleRateHz: Int = 48_000,
    private val windowSamples: Int = sampleRateHz * 3,
    private val hopSamples: Int = sampleRateHz * 3 / 2,
    private val applyHighPass: Boolean = true,
    private val queueCapacity: Int = 8,
) {
    private val _predictions = MutableSharedFlow<WindowPrediction>(extraBufferCapacity = 64)
    val predictions: SharedFlow<WindowPrediction> = _predictions.asSharedFlow()

    private val _backlog = MutableStateFlow(0)
    val backlog: StateFlow<Int> = _backlog.asStateFlow()

    private val queue = Channel<Window>(capacity = queueCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var workerJob: Job? = null

    private val ring = FloatArray(windowSamples + hopSamples)
    private var ringFilled = 0       // valid samples in [0, ringFilled)
    private var consumed = 0L        // total samples that have left the head of the ring
    private var nextEmitAt = 0L      // sample index at which we emit the next window
    private var stopped = false

    fun start(scope: CoroutineScope) {
        if (workerJob != null) return
        workerJob = scope.launch(Dispatchers.Default) { worker() }
    }

    /** Append a chunk of audio (float, normalized to [-1, 1]). Non-blocking. */
    fun feed(block: FloatArray) {
        if (stopped) return
        var idx = 0
        while (idx < block.size) {
            val space = ring.size - ringFilled
            if (space == 0) {
                // Slide ring left by hopSamples; should not happen often if
                // worker keeps up but defensively avoids overrun.
                System.arraycopy(ring, hopSamples, ring, 0, ring.size - hopSamples)
                ringFilled -= hopSamples
                consumed += hopSamples
                continue
            }
            val take = (block.size - idx).coerceAtMost(space)
            System.arraycopy(block, idx, ring, ringFilled, take)
            ringFilled += take
            idx += take
            tryEmitWindows()
        }
    }

    private fun tryEmitWindows() {
        while (consumed + ringFilled >= nextEmitAt + windowSamples) {
            val startSample = (nextEmitAt - consumed).toInt()
            require(startSample >= 0 && startSample + windowSamples <= ringFilled) {
                "window slice out of range: start=$startSample fill=$ringFilled"
            }
            val window = FloatArray(windowSamples)
            System.arraycopy(ring, startSample, window, 0, windowSamples)
            val startMs = nextEmitAt * 1_000L / sampleRateHz
            val endMs = (nextEmitAt + windowSamples) * 1_000L / sampleRateHz
            val dropped = queue.trySend(Window(window, startMs, endMs)).isFailure
            if (!dropped) _backlog.value = (_backlog.value + 1).coerceAtMost(queueCapacity)
            nextEmitAt += hopSamples

            // Slide ring forward by hopSamples (we don't need samples before nextEmitAt).
            val keep = (consumed + ringFilled - nextEmitAt).toInt().coerceAtLeast(0)
            if (keep > 0 && keep < ringFilled) {
                System.arraycopy(ring, ringFilled - keep, ring, 0, keep)
            }
            consumed = nextEmitAt - keep.coerceAtMost(ringFilled)
            ringFilled = keep.coerceAtMost(ring.size)
        }
    }

    private suspend fun worker() {
        for (window in queue) {
            _backlog.value = (_backlog.value - 1).coerceAtLeast(0)
            if (_backlog.value > BACKLOG_WARN) {
                Log.w(TAG, "Inference behind real time: backlog=${_backlog.value}")
            }
            runWindow(window)
            yield()
        }
    }

    private suspend fun runWindow(w: Window) {
        var samples = w.samples
        if (applyHighPass) samples = highPassFilter(samples, sampleRateHz)
        spectralSubtractor?.let { samples = it.process(samples) }
        if (yamNetGate?.isBiological(samples, sampleRateHz) == false) return
        val preds = model.predict(
            pcmFloat32 = samples,
            sampleRateHz = sampleRateHz,
            latitude = null, longitude = null, observedAtMillis = 0L,
            windowStartMs = w.startMs, windowEndMs = w.endMs,
        )
        for (p in preds) _predictions.tryEmit(p)
    }

    /** Stops accepting new windows, drains queue (≤ DRAIN_TIMEOUT_MS), cancels worker. */
    suspend fun stop() {
        if (stopped) return
        stopped = true
        queue.close()
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) { workerJob?.join() }
        workerJob?.cancel()
        workerJob = null
    }

    private data class Window(val samples: FloatArray, val startMs: Long, val endMs: Long)

    companion object {
        private const val TAG = "LiveInferenceEngine"
        private const val BACKLOG_WARN = 3
        private const val DRAIN_TIMEOUT_MS = 5_000L
    }
}
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.LiveInferenceEngineTest"
```
Expected: green. If failures around emission timing: walk through `tryEmitWindows` and the ring slide logic with a concrete sample stream — there may be off-by-one in `consumed`/`nextEmitAt` accounting.

- [ ] **Step 5: Run full inference test suite**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.*"
```
Expected: green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt \
        app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt
git commit -m "feat(inference): add LiveInferenceEngine for streaming BirdNET"
```

---

## Task 4: RecordingScreen rebuild + Stop flow

**Self-contained context:**
- This is the largest and most integration-heavy task. Combines:
  - VM extension (live cards, backlog, spectrogram flow)
  - DraftRepository — new entry point that creates draft already in `PENDING_REVIEW`
  - DetectionAggregator — incremental API (add `addWindow` returning current snapshot)
  - UI rewrite (Merlin-style layout)
  - Hilt providers for `LiveInferenceEngine` factory
- Existing `ProductionInferenceJob` in [ReviewViewModel.kt:681](../../../app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt#L681) stays intact as fallback for older drafts (`PENDING_INFERENCE`) and for `reanalyze()`.
- Settings.minConfidenceDisplay drives card filtering — re-use existing flow.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`
- Modify: `app/src/androidTest/java/com/sound2inat/app/TestModule.kt` (if Hilt needs test stub for engine factory)
- Test: `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt` (extend)
- Test: `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt` (extend)
- Test: `app/src/test/java/com/sound2inat/storage/DraftRepositoryTest.kt` (extend)

- [ ] **Step 1: DetectionAggregator — failing test for incremental API**

Add to `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt`:

```kotlin
@Test
fun `incremental addWindow yields same result as batch aggregate`() {
    val preds = listOf(
        WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"),
        WindowPrediction(1500L, 4500L, "Turdus merula", "Blackbird", 0.6f, "birdnet_v2_4"),
        WindowPrediction(0L, 3000L, "Erithacus rubecula", "Robin", 0.7f, "birdnet_v2_4"),
    )
    val batch = DetectionAggregator(minConfidence = 0.5f).aggregate(preds)
    val incremental = DetectionAggregator(minConfidence = 0.5f)
    var snapshot: List<AggregatedDetection> = emptyList()
    for (p in preds) snapshot = incremental.addWindow(p)
    assertThat(snapshot).containsExactlyElementsIn(batch).inOrder()
}

@Test
fun `addWindow filters below threshold and noise labels`() {
    val agg = DetectionAggregator(minConfidence = 0.5f)
    agg.addWindow(WindowPrediction(0L, 3000L, "Fireworks", null, 0.9f, "birdnet_v2_4"))
    agg.addWindow(WindowPrediction(0L, 3000L, "Turdus merula", null, 0.3f, "birdnet_v2_4"))
    val snap = agg.addWindow(WindowPrediction(1500L, 4500L, "Turdus merula", null, 0.7f, "birdnet_v2_4"))
    assertThat(snap).hasSize(1)
    assertThat(snap[0].taxonScientificName).isEqualTo("Turdus merula")
    assertThat(snap[0].detectedWindows).isEqualTo(1)
}
```

- [ ] **Step 2: Confirm test fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.DetectionAggregatorTest.*incremental*" \
                                  --tests "com.sound2inat.inference.DetectionAggregatorTest.*addWindow*"
```
Expected: compile error — `addWindow` not defined.

- [ ] **Step 3: Implement incremental API**

In `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`, add:

```kotlin
private val incremental = mutableListOf<WindowPrediction>()

/** Add one prediction; returns the current aggregated snapshot. */
fun addWindow(pred: WindowPrediction): List<AggregatedDetection> {
    incremental.add(pred)
    return aggregate(incremental)
}

/** Reset incremental state — used when starting a new recording. */
fun reset() {
    incremental.clear()
}
```

- [ ] **Step 4: Run aggregator tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.DetectionAggregatorTest"
```
Expected: green.

- [ ] **Step 5: DraftRepository — failing test for live-attach flow**

Add to `app/src/test/java/com/sound2inat/storage/DraftRepositoryTest.kt`:

```kotlin
@Test
fun `createWithDetections inserts draft and detections in PENDING_REVIEW`() {
    val repo = repo()
    val det = AggregatedDetection(
        taxonScientificName = "Turdus merula", taxonCommonName = "Blackbird",
        maxConfidence = 0.8f, detectedWindows = 2, firstSeenMs = 0L, lastSeenMs = 4500L,
    )
    repo.createWithDetections(
        id = "d1", audioPath = "/x.wav", recordedAtUtcMs = 1L, durationMs = 5_000L,
        latitude = 50.0, longitude = 14.0, accuracyMeters = 5f,
        modelId = "birdnet_v2_4", modelVersion = "2.4",
        detections = listOf(det),
    )
    val saved = repo.observeAll().first()
    assertThat(saved).hasSize(1)
    assertThat(saved[0].status).isEqualTo(DraftStatus.PENDING_REVIEW)
    assertThat(saved[0].modelId).isEqualTo("birdnet_v2_4")
    val withDet = repo.observeWithDetections("d1").first()
    assertThat(withDet.detections).hasSize(1)
    assertThat(withDet.detections[0].taxonScientificName).isEqualTo("Turdus merula")
}
```

- [ ] **Step 6: Confirm test fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.storage.DraftRepositoryTest.*createWithDetections*"
```
Expected: compile error.

- [ ] **Step 7: Add createWithDetections to DraftRepository**

In `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`, add new method:

```kotlin
@Suppress("LongParameterList")
fun createWithDetections(
    id: String,
    audioPath: String,
    recordedAtUtcMs: Long,
    durationMs: Long,
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Float?,
    modelId: String,
    modelVersion: String,
    detections: List<AggregatedDetection>,
) {
    val now = nowMs()
    drafts.insert(
        DraftEntity(
            id = id,
            audioPath = audioPath,
            recordedAtUtcMs = recordedAtUtcMs,
            durationMs = durationMs,
            latitude = latitude,
            longitude = longitude,
            locationAccuracyMeters = accuracyMeters,
            status = DraftStatus.PENDING_REVIEW,
            modelId = modelId,
            modelVersion = modelVersion,
            createdAtUtcMs = now,
            updatedAtUtcMs = now,
        ),
    )
    detections.insertAll(
        detections.map {
            DetectionEntity(
                draftId = id,
                taxonScientificName = it.taxonScientificName,
                taxonCommonName = it.taxonCommonName,
                maxConfidence = it.maxConfidence,
                detectedWindows = it.detectedWindows,
                firstSeenMs = it.firstSeenMs,
                lastSeenMs = it.lastSeenMs,
                isSelectedByUser = false,
                sources = SourceConfidences.encode(it.confidenceBySource),
            )
        },
    )
}
```

- [ ] **Step 8: Run repository test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.storage.DraftRepositoryTest"
```
Expected: green.

- [ ] **Step 9: Hilt — provide LiveInferenceEngine factory**

In `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`, add:

```kotlin
@Provides
fun provideLiveInferenceEngineFactory(
    bioModels: List<BioacousticModel>,
    yamGate: YamNetGate?,
): LiveInferenceEngineFactory = LiveInferenceEngineFactory { sampleRate ->
    val birdnet = bioModels.firstOrNull { it.modelId == "birdnet_v2_4" }
        ?: error("BirdNET model not bound — install before recording")
    LiveInferenceEngine(
        model = birdnet,
        yamNetGate = yamGate,
        spectralSubtractor = SpectralSubtractor(),
        sampleRateHz = sampleRate,
    )
}
```

Add the factory interface in the same file (or a sibling under inference/):

```kotlin
fun interface LiveInferenceEngineFactory {
    fun create(sampleRateHz: Int): LiveInferenceEngine
}
```

(Place `LiveInferenceEngineFactory` in `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt` next to the engine class so the import in SwappableModule stays clean.)

- [ ] **Step 10: TestModule stub for engine factory**

In `app/src/androidTest/java/com/sound2inat/app/TestModule.kt`, add a test override that returns a no-op factory (the e2e test covers WAV path, not live engine):

```kotlin
@Provides
fun provideLiveInferenceEngineFactory(): LiveInferenceEngineFactory? = null
```

If the production binding is non-nullable, change it to nullable in production too — VM will fall back to old offline pipeline when null.

- [ ] **Step 11: Extend RecordingUiState with live fields**

In `app/src/main/java/com/sound2inat/app/ui/recording/RecordingUiState.kt`, modify:

```kotlin
data class Recording(
    val elapsedMs: Long,
    val rms: Float,
    val gps: GpsStatus,
    val warningSoftLimit: Boolean,
    val liveCards: List<LiveCard> = emptyList(),
    val backlogWindows: Int = 0,
) : RecordingUiState
```

Add the model:

```kotlin
data class LiveCard(
    val scientificName: String,
    val commonName: String?,
    val count: Int,
    val peakConfidence: Float,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)
```

- [ ] **Step 12: Extend RecordingViewModel — failing test**

Add to `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt`:

```kotlin
@Test
fun `live cards accumulate from inference engine predictions`() = runTest(UnconfinedTestDispatcher()) {
    val fakeEngine = FakeLiveEngine()
    val vm = build(engineFactory = { fakeEngine })
    grant()
    vm.start()
    advanceUntilIdle()
    fakeEngine.emit(WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"))
    advanceUntilIdle()
    val cur = vm.state.value as RecordingUiState.Recording
    assertThat(cur.liveCards).hasSize(1)
    assertThat(cur.liveCards[0].scientificName).isEqualTo("Turdus merula")
}

@Test
fun `stop creates draft in PENDING_REVIEW with live detections`() = runTest(UnconfinedTestDispatcher()) {
    val fakeEngine = FakeLiveEngine()
    val vm = build(engineFactory = { fakeEngine })
    grant()
    vm.start()
    advanceUntilIdle()
    fakeEngine.emit(WindowPrediction(0L, 3000L, "Turdus merula", null, 0.8f, "birdnet_v2_4"))
    advanceUntilIdle()
    vm.stop()
    advanceUntilIdle()
    val saved = drafts.observeAll().first()
    assertThat(saved).hasSize(1)
    assertThat(saved[0].status).isEqualTo(DraftStatus.PENDING_REVIEW)
}
```

`FakeLiveEngine` is a simple doubles class (define in same test file) wrapping a MutableSharedFlow.

- [ ] **Step 13: Confirm test fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.recording.RecordingViewModelTest.*live*" \
                                  --tests "com.sound2inat.app.ui.recording.RecordingViewModelTest.*stop*"
```
Expected: compile error or wrong status.

- [ ] **Step 14: Implement live wiring in RecordingViewModel**

In `app/src/main/java/com/sound2inat/app/ui/recording/RecordingViewModel.kt`, add to constructor:

```kotlin
private val engineFactory: LiveInferenceEngineFactory? = null,
private val minConfidence: Flow<Float> = flowOf(0.25f),
```

Inside `start()`, after `recorder.start(target)`:

```kotlin
val engine = engineFactory?.create(recorder.sampleRate)
val aggregator = DetectionAggregator(minConfidence = minConfidence.first())
if (engine != null) {
    engine.start(viewModelScope)
    viewModelScope.launch {
        recorder.audioBlocks.collect { block -> engine.feed(block) }
    }
    viewModelScope.launch {
        engine.predictions.collect { pred ->
            val cards = aggregator.addWindow(pred).map { it.toLiveCard() }
            val cur = _state.value as? RecordingUiState.Recording ?: return@collect
            _state.value = cur.copy(liveCards = cards)
        }
    }
    viewModelScope.launch {
        engine.backlog.collect { n ->
            val cur = _state.value as? RecordingUiState.Recording ?: return@collect
            _state.value = cur.copy(backlogWindows = n)
        }
    }
    this.activeEngine = engine
    this.activeAggregator = aggregator
}
```

Add private fields `activeEngine`, `activeAggregator`. In `stopInternal`:

```kotlin
private suspend fun stopInternal() {
    val id = draftId ?: return
    val result = recorder.stop()
    activeEngine?.stop()
    val final = activeAggregator?.let { ag ->
        // aggregator already saw all predictions; rebuild snapshot from incremental list
        ag.snapshot()    // NEW exposed method, see DetectionAggregator changes below
    } ?: emptyList()
    cancelJobs()
    withContext(ioDispatcher) {
        if (final.isNotEmpty() && activeEngine != null) {
            drafts.createWithDetections(
                id = id, audioPath = result.audioPath,
                recordedAtUtcMs = recordingStartMs, durationMs = result.durationMs,
                latitude = fix?.latitude, longitude = fix?.longitude,
                accuracyMeters = fix?.accuracyMeters,
                modelId = "birdnet_v2_4", modelVersion = "2.4",
                detections = final,
            )
        } else {
            drafts.create(
                id = id, audioPath = result.audioPath,
                recordedAtUtcMs = recordingStartMs, durationMs = result.durationMs,
                latitude = fix?.latitude, longitude = fix?.longitude,
                accuracyMeters = fix?.accuracyMeters,
            )
        }
    }
    _state.value = RecordingUiState.Done(id)
}
```

Add `snapshot()` to `DetectionAggregator`:

```kotlin
fun snapshot(): List<AggregatedDetection> = aggregate(incremental)
```

Mapper (private extension in VM file):

```kotlin
private fun AggregatedDetection.toLiveCard() = LiveCard(
    scientificName = taxonScientificName,
    commonName = taxonCommonName,
    count = detectedWindows,
    peakConfidence = maxConfidence,
    firstSeenMs = firstSeenMs,
    lastSeenMs = lastSeenMs,
)
```

- [ ] **Step 15: Update RecordingViewModelHilt to inject the new providers**

```kotlin
@HiltViewModel
class RecordingViewModelHilt @Inject constructor(
    private val recorder: Recorder,
    private val location: LocationProvider,
    private val files: WavFileStore,
    private val drafts: DraftRepository,
    private val engineFactory: LiveInferenceEngineFactory?,
    private val settings: com.sound2inat.app.data.Settings,
) : ViewModel() {
    val factory = { perms: PermissionsController ->
        RecordingViewModel(
            perms = perms, recorder = recorder, location = location,
            files = files, drafts = drafts,
            engineFactory = engineFactory,
            minConfidence = settings.minConfidenceDisplay,
        )
    }
}
```

- [ ] **Step 16: Run VM tests; iterate until green**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.recording.RecordingViewModelTest"
```

If failures: trace through which `Flow.collect`s run on which dispatcher. The VM uses `viewModelScope`; tests inject `UnconfinedTestDispatcher`. Make sure `engine.start(scope)` uses that scope when test injects it (override `Dispatchers.Default` if needed).

- [ ] **Step 17: Rewrite RecordingScreen UI (Merlin layout)**

Replace `RecordingBody` in `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`:

```kotlin
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun RecordingBody(
    s: RecordingUiState.Recording,
    audioBlocks: SharedFlow<FloatArray>,
    sampleRateHz: Int,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatElapsed(s.elapsedMs), style = MaterialTheme.typography.titleLarge)
            Text(
                gpsLabel(s.gps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiveSpectrogramView(
            audioBlocks = audioBlocks,
            sampleRateHz = sampleRateHz,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)   // top half
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        if (s.backlogWindows > 0) {
            Text(
                "Analysis catching up… (${s.backlogWindows})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),   // bottom half
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(s.liveCards, key = { it.scientificName }) { card ->
                LiveCardRow(card)
            }
        }
        if (s.warningSoftLimit) {
            Text(
                "Long recording — auto-stop at 10:00",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FilledIconButton(
            onClick = onStop,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(STOP_BUTTON_DP.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(STOP_ICON_DP.dp)) }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun LiveCardRow(card: LiveCard) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(card.commonName ?: card.scientificName, style = MaterialTheme.typography.bodyLarge)
                if (card.commonName != null) {
                    Text(card.scientificName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    "×${card.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                "%d%%".format((card.peakConfidence * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun gpsLabel(gps: GpsStatus): String = when (gps) {
    is GpsStatus.Acquiring -> "GPS: acquiring…"
    is GpsStatus.Fix -> "GPS: %.4f, %.4f".format(gps.latitude, gps.longitude)
    is GpsStatus.NoFix -> "no GPS"
}
```

Update the `RecordingScreen` body to pass audioBlocks/sampleRate from VM:

```kotlin
is RecordingUiState.Recording -> RecordingBody(
    s = s,
    audioBlocks = vm.audioBlocks,    // NEW
    sampleRateHz = vm.sampleRateHz,  // NEW
    onStop = { vm.stop() },
)
```

Expose those in `RecordingViewModel`:

```kotlin
val audioBlocks: SharedFlow<FloatArray> = recorder.audioBlocks
val sampleRateHz: Int get() = recorder.sampleRate
```

Remove the old `LiveWaveform` composable (replaced by spectrogram).

- [ ] **Step 18: Build APK**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 19: Run full unit test suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: green.

- [ ] **Step 20: Manual device smoke-test**

Install APK, record 30 sec near a known bird (or play a YouTube bird sound through speakers).

Verify:
1. Spectrogram scrolls smoothly (no rubberbanding, no obvious frame drops).
2. Within ~3 sec, at least one `LiveCard` appears.
3. The `LiveCard` count `×N` increments as the same species sings again.
4. After Stop, ReviewScreen opens **immediately** (no "Analyzing…" spinner).
5. Detections in Review match the live list.
6. `adb logcat | grep LiveInferenceEngine` shows no backlog warnings on a recent device.

Capture any frame drops or backlog warnings; if recurring, file a follow-up but don't block the commit.

- [ ] **Step 21: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt \
        app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt \
        app/src/main/java/com/sound2inat/storage/DraftRepository.kt \
        app/src/main/java/com/sound2inat/app/di/SwappableModule.kt \
        app/src/main/java/com/sound2inat/app/ui/recording/ \
        app/src/androidTest/java/com/sound2inat/app/TestModule.kt \
        app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt \
        app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt \
        app/src/test/java/com/sound2inat/storage/DraftRepositoryTest.kt
git commit -m "feat(recording): Merlin-style live spectrogram + live BirdNET detection"
```

---

## Task 5: Analyze with Perch (Review-screen on-demand)

**Self-contained context:**
- Live engine runs only BirdNET. Perch v2 needs to run on the saved WAV after Stop.
- Reuse [InferenceRunner](../../../app/src/main/java/com/sound2inat/inference/InferenceRunner.kt) — it already supports any `BioacousticModel`.
- Existing `ProductionInferenceJob` in [ReviewViewModel.kt:681](../../../app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt#L681) loops over all installed models and merges. We need a focused variant that runs only Perch.
- Result merging: take existing detections from DB + new Perch detections, re-aggregate, re-attach. Use `DraftRepository.attachDetections` (already exists, sets `PENDING_REVIEW`).
- UI: button visible in Review when (a) Perch model is installed, (b) draft has no Perch detections yet (check `confidenceBySource` keys).

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

- [ ] **Step 1: Write failing test for analyzeWithPerch**

Add to `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`:

```kotlin
@Test
fun `analyzeWithPerch runs only Perch model and merges results`() = runTest(UnconfinedTestDispatcher()) {
    val vm = buildVm(
        existingDetections = listOf(
            detection("Turdus merula", 0.8f, source = "birdnet_v2_4"),
        ),
        installedModels = listOf(birdnetModel(), perchModelEmitting("Rana temporaria", 0.7f)),
    )
    advanceUntilIdle()
    vm.analyzeWithPerch()
    advanceUntilIdle()
    val state = vm.state.value
    val species = state.detections.map { it.taxonScientificName }
    assertThat(species).containsAtLeast("Turdus merula", "Rana temporaria")
    assertThat(state.perchProgress).isNull()
}

@Test
fun `analyzeWithPerch is hidden when Perch already analysed`() = runTest(UnconfinedTestDispatcher()) {
    val vm = buildVm(
        existingDetections = listOf(
            detection("Turdus merula", 0.8f, source = "birdnet_v2_4"),
            detection("Rana temporaria", 0.7f, source = "perch_v2"),
        ),
        installedModels = listOf(birdnetModel(), perchModelEmitting("X", 0.5f)),
    )
    advanceUntilIdle()
    assertThat(vm.state.value.canAnalyzeWithPerch).isFalse()
}
```

(Helpers `buildVm`, `birdnetModel`, `perchModelEmitting`, `detection` either exist or extend the existing test infrastructure. Look at [ReviewViewModelTest.kt](../../../app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt) for the existing patterns and reuse them.)

- [ ] **Step 2: Confirm test fails**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.review.ReviewViewModelTest.*Perch*"
```
Expected: compile error.

- [ ] **Step 3: Add Perch state to ReviewUiState**

In `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`, add fields:

```kotlin
val canAnalyzeWithPerch: Boolean = false,
val perchProgress: Float? = null,
```

- [ ] **Step 4: Compute canAnalyzeWithPerch**

In `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`, where the state is built from DB+models, add:

```kotlin
private fun computeCanAnalyzeWithPerch(
    detections: List<DetectionEntity>,
    installedModelIds: Set<String>,
): Boolean {
    if ("perch_v2" !in installedModelIds) return false
    val anyPerchDetection = detections.any { det ->
        SourceConfidences.decode(det.sources).containsKey("perch_v2")
    }
    return !anyPerchDetection
}
```

Wire it into `ReviewUiState` construction.

- [ ] **Step 5: Implement analyzeWithPerch in ReviewViewModel**

```kotlin
fun analyzeWithPerch() {
    val draftId = currentDraftId ?: return
    if (!_state.value.canAnalyzeWithPerch) return
    val perch = bioModels.firstOrNull { it.modelId == "perch_v2" } ?: return
    viewModelScope.launch {
        _state.value = _state.value.copy(perchProgress = 0f)
        try {
            val draft = draftRepo.observeWithDetections(draftId).first()
            val runner = InferenceRunner(
                model = perch,
                spectralSubtractor = if (settings.spectralSubtractionEnabled.first()) SpectralSubtractor() else null,
                yamNetGate = yamNetGate,
            )
            launch {
                runner.progress.collect { p ->
                    _state.value = _state.value.copy(perchProgress = p)
                }
            }
            val perchPreds = runner.run(
                wavFile = File(draft.draft.audioPath),
                latitude = draft.draft.latitude,
                longitude = draft.draft.longitude,
                observedAtMillis = draft.draft.recordedAtUtcMs,
            )
            val existingPreds = draft.detections.flatMap { det ->
                SourceConfidences.decode(det.sources).map { (src, conf) ->
                    WindowPrediction(
                        startMs = det.firstSeenMs, endMs = det.lastSeenMs,
                        taxonScientificName = det.taxonScientificName,
                        taxonCommonName = det.taxonCommonName,
                        confidence = conf, source = src,
                    )
                }
            }
            val agg = DetectionAggregator(minConfidence = settings.minConfidenceDisplay.first())
                .aggregate(existingPreds + perchPreds)
            draftRepo.attachDetections(
                draftId = draftId,
                modelId = "${draft.draft.modelId},perch_v2",
                modelVersion = draft.draft.modelVersion ?: "live+perch",
                items = agg,
            )
        } finally {
            _state.value = _state.value.copy(perchProgress = null)
        }
    }
}
```

- [ ] **Step 6: Run review VM tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.review.ReviewViewModelTest"
```
Expected: green.

- [ ] **Step 7: Add UI button in ReviewScreen**

Locate where the toolbar / actions are rendered in [ReviewScreen.kt](../../../app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt) and add:

```kotlin
if (state.canAnalyzeWithPerch) {
    Button(
        onClick = { vm.analyzeWithPerch() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("Analyze with Perch (frogs, insects, mammals)")
    }
}
state.perchProgress?.let { p ->
    LinearProgressIndicator(
        progress = { p },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Text(
        "Perch analysing… ${(p * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
```

- [ ] **Step 8: Build APK**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Manual device smoke**

Install APK, record a recording (let live BirdNET fill cards), open Review.
Expected:
- "Analyze with Perch" button visible (only if Perch model installed).
- Tap → progress bar runs, eventually finishes.
- New Perch-only species (frogs/insects) appear in the detections list.
- Button disappears after success.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ \
        app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt
git commit -m "feat(review): on-demand Perch analysis button"
```

---

## Final verification

- [ ] **Step F1: Full unit test suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: green across all modules.

- [ ] **Step F2: Lint / format checks (project-standard)**

```bash
./gradlew :app:detekt :app:lint
```
(Run whichever the project uses — check `app/build.gradle.kts` for detekt/ktlint plugins.) Fix any reports.

- [ ] **Step F3: Final manual end-to-end**

Record → Review (verify live cards == final) → submit to iNat. Catches any breakage in the Stop flow that unit tests missed.

- [ ] **Step F4: PR**

Use the existing PR template / format. Title: `feat: live recording screen with Merlin-style spectrogram and live BirdNET`. Body: bullet list of the 5 task headlines + manual test results.
