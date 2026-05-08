# Noise Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Before starting each task:** Read the full task, re-read the referenced spec section, and do a quick review — look for anything in the instructions that seems wrong, missing, or improvable. Raise concerns before writing code.

**Goal:** Add spectral noise subtraction + YAMNet biological gate + high-pass filter to the bioacoustic inference pipeline so that BirdNET and Perch receive cleaner audio input and noisy windows are skipped.

**Architecture:** New `AudioPreprocessor.kt` (HPF + SpectralSubtractor) and `YamNetTfliteGate.kt` (TFLite inference) are injected into `InferenceRunner` as optional null-able params. The full signal gets HPF before slicing; each window optionally gets spectral subtraction then YAMNet gate. YAMNet model is downloaded automatically (hidden ModelDescriptor) when any visible model is installed. Two Settings toggles control both features independently.

**Tech Stack:** Kotlin, Hilt DI, TFLite (`InterpreterFactory`/`InterpreterApi`), DataStore Preferences, Jetpack Compose, JUnit 4, Google Truth.

---

## File Map

### Create
| File | Purpose |
|------|---------|
| `app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt` | `highPassFilter()` + `SpectralSubtractor` |
| `app/src/main/java/com/sound2inat/inference/YamNetGate.kt` | `YamNetGate` fun interface |
| `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt` | TFLite impl of the gate |
| `app/src/test/java/com/sound2inat/inference/AudioPreprocessorTest.kt` | Unit tests for HPF + subtractor |
| `app/src/test/java/com/sound2inat/inference/YamNetGateTest.kt` | Unit tests for the gate |

### Modify
| File | Change |
|------|--------|
| `app/src/main/java/com/sound2inat/modelmanager/ModelDescriptor.kt` | Add `hidden: Boolean = false`; add `YamNetV1` descriptor |
| `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt` | Add `hiddenDescriptors` param; auto-install hidden after visible install |
| `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` | Apply HPF to full signal; wire subtractor + gate per window |
| `app/src/main/java/com/sound2inat/app/data/Settings.kt` | Add two new boolean prefs |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt` | Add two boolean fields |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt` | Flows, collectors, setters for new prefs |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt` | "Noise reduction" `SectionCard` |
| `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt` | `YamNetGate?` provider; update `ModelManager` with hidden descriptors |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | `ProductionInferenceJob` gets `yamNetGate`; creates `SpectralSubtractor` per run |
| `app/src/androidTest/java/com/sound2inat/app/TestModule.kt` | Provide `null` for `YamNetGate?` |
| `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt` | Add gate-skips-window test |
| `app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt` | Add noise reduction settings tests |

---

## Task 1: AudioPreprocessor — HPF + SpectralSubtractor + unit tests

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt`
- Create: `app/src/test/java/com/sound2inat/inference/AudioPreprocessorTest.kt`

**Context:** Pure JVM, no Android deps. `highPassFilter()` is a 2nd-order Butterworth IIR biquad (Audio EQ Cookbook formula). `SpectralSubtractor` maintains a per-instance noise power profile updated via EMA from quiet windows; applies power-domain subtraction with over-subtraction factor β and spectral floor γ. FFT is a pure-Kotlin Cooley-Tukey implementation — no external lib needed.

**Key design decisions:**
- `process()` snapshots `noiseProfile` BEFORE potentially updating it so the very first quiet window (when snapshot is null) returns unchanged — avoids subtracting a window from itself.
- `spectralSubtractor: SpectralSubtractor?` is NOT a Hilt singleton — `ProductionInferenceJob` creates a fresh instance per model per run (resets noise profile correctly). That wiring happens in Task 6.

---

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/inference/AudioPreprocessorTest.kt`:

```kotlin
package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AudioPreprocessorTest {

    private val sampleRate = 8_000

    private fun powerAtFreq(samples: FloatArray, freqHz: Double): Double {
        var re = 0.0; var im = 0.0
        val n = samples.size
        for (k in 0 until n) {
            val angle = 2 * PI * freqHz * k / sampleRate
            re += samples[k] * cos(angle)
            im += samples[k] * sin(angle)
        }
        return (re * re + im * im) / (n.toLong() * n)
    }

    @Test
    fun `highPassFilter attenuates 100 Hz by more than 13 dB at 250 Hz cutoff`() {
        val n = sampleRate  // 1 second of audio
        val signal = FloatArray(n) { k ->
            val t = k.toDouble() / sampleRate
            (sin(2 * PI * 100 * t) * 0.5 + sin(2 * PI * 1000 * t) * 0.5).toFloat()
        }
        val filtered = highPassFilter(signal, sampleRate, cutoffHz = 250)

        val origPow100 = powerAtFreq(signal, 100.0)
        val origPow1000 = powerAtFreq(signal, 1000.0)
        val filtPow100 = powerAtFreq(filtered, 100.0)
        val filtPow1000 = powerAtFreq(filtered, 1000.0)

        // 100 Hz is 2.5x below 250 Hz cutoff — 2nd-order Butterworth gives ~16 dB attenuation
        assertThat(filtPow100 / origPow100).isLessThan(0.05)
        // 1000 Hz is 4x above cutoff — should pass with less than ~1.5 dB loss
        assertThat(filtPow1000 / origPow1000).isGreaterThan(0.7)
    }

    @Test
    fun `SpectralSubtractor returns window unchanged when no noise profile`() {
        val sub = SpectralSubtractor()
        // RMS = 0.3 > 0.01 quiet threshold — noisy window, no profile update, profile stays null
        val window = FloatArray(1024) { 0.3f }
        val result = sub.process(window)
        assertThat(result.toList()).isEqualTo(window.toList())
    }

    @Test
    fun `SpectralSubtractor first quiet window returns unchanged (profile snapshot was null)`() {
        val sub = SpectralSubtractor()
        // RMS = 0.008 < 0.01 — quiet; profile snapshot is null before update, so window returned as-is
        val quiet = FloatArray(1024) { 0.008f }
        val result = sub.process(quiet)
        assertThat(result.toList()).isEqualTo(quiet.toList())
    }

    @Test
    fun `SpectralSubtractor reduces RMS after noise profile is established`() {
        val sub = SpectralSubtractor()
        sub.process(FloatArray(1024) { 0.008f })  // first quiet window — establishes profile
        val loud = FloatArray(1024) { 0.015f }    // RMS 0.015 > 0.01 — not quiet, gets subtracted
        val result = sub.process(loud)

        val rmsIn = sqrt(loud.fold(0.0) { a, s -> a + s.toDouble() * s } / loud.size)
        val rmsOut = sqrt(result.fold(0.0) { a, s -> a + s.toDouble() * s } / result.size)
        assertThat(rmsOut).isLessThan(rmsIn)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.AudioPreprocessorTest" -x lint 2>&1 | tail -30
```

Expected: compilation error (`highPassFilter` not found, `SpectralSubtractor` not found).

- [ ] **Step 3: Implement `AudioPreprocessor.kt`**

Create `app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt`:

```kotlin
package com.sound2inat.inference

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2nd-order Butterworth high-pass biquad filter (Audio EQ Cookbook formula).
 * Cutoff 250 Hz applied to the full resampled signal before window slicing.
 * Removes wind rumble, traffic sub-bass, and other sub-250 Hz interference.
 */
fun highPassFilter(samples: FloatArray, sampleRateHz: Int, cutoffHz: Int = 250): FloatArray {
    val w0 = 2.0 * PI * cutoffHz / sampleRateHz
    val cosW0 = cos(w0)
    val alpha = sin(w0) * sqrt(0.5)   // sin(w0)/(2*Q), Q=1/sqrt(2) for Butterworth
    val a0 = 1.0 + alpha
    val nb0 = ((1.0 + cosW0) / 2.0 / a0).toFloat()
    val nb1 = (-(1.0 + cosW0) / a0).toFloat()
    val nb2 = nb0
    val na1 = (-2.0 * cosW0 / a0).toFloat()
    val na2 = ((1.0 - alpha) / a0).toFloat()

    val out = FloatArray(samples.size)
    var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
    for (i in samples.indices) {
        val x0 = samples[i]
        val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
        out[i] = y0
        x2 = x1; x1 = x0; y2 = y1; y1 = y0
    }
    return out
}

/**
 * Adaptive spectral subtraction noise reducer.
 *
 * Quiet windows (RMS < 0.01) update an EMA noise power profile (α=0.1).
 * Louder windows have the profile subtracted in the power domain with
 * over-subtraction factor β=1.5 and spectral floor γ=0.002.
 *
 * The profile snapshot is taken BEFORE the update so the first quiet window
 * (when snapshot == null) returns unchanged — avoids subtracting a frame
 * from itself.
 *
 * NOT thread-safe — create one instance per InferenceRunner run.
 */
@Suppress("TooManyFunctions")
class SpectralSubtractor {

    private var noiseProfile: FloatArray? = null

    fun process(window: FloatArray): FloatArray {
        val rms = sqrt(window.fold(0.0) { acc, s -> acc + s.toDouble() * s } / window.size).toFloat()
        val profileSnapshot = noiseProfile

        val fftSize = nextPow2(window.size)
        val spectrum = FloatArray(fftSize * 2)
        for (i in window.indices) spectrum[2 * i] = window[i]   // zero-pad imag parts stay 0
        fftInPlace(spectrum, fftSize, inverse = false)

        if (rms < RMS_QUIET_THRESHOLD) {
            val power = FloatArray(fftSize / 2 + 1) { i ->
                val re = spectrum[2 * i]; val im = spectrum[2 * i + 1]
                re * re + im * im
            }
            val existing = noiseProfile
            noiseProfile = if (existing == null) power
                           else FloatArray(power.size) { i -> ALPHA * power[i] + (1f - ALPHA) * existing[i] }
        }

        val profile = profileSnapshot ?: return window

        val outSpec = FloatArray(spectrum.size)
        for (i in 0..fftSize / 2) {
            val re = spectrum[2 * i]; val im = spectrum[2 * i + 1]
            val powerIn = re * re + im * im
            val powerOut = max(powerIn - BETA * profile[i], GAMMA * powerIn)
            val scale = if (powerIn > 1e-20f) sqrt(powerOut / powerIn) else 0f
            outSpec[2 * i] = re * scale; outSpec[2 * i + 1] = im * scale
        }
        // Conjugate symmetry so IFFT produces a real signal
        for (i in 1 until fftSize / 2) {
            outSpec[2 * (fftSize - i)] = outSpec[2 * i]
            outSpec[2 * (fftSize - i) + 1] = -outSpec[2 * i + 1]
        }
        fftInPlace(outSpec, fftSize, inverse = true)
        val norm = 1f / fftSize
        return FloatArray(window.size) { i -> outSpec[2 * i] * norm }
    }

    private fun nextPow2(n: Int): Int {
        var p = 1; while (p < n) p = p shl 1; return p
    }

    @Suppress("NestedBlockDepth", "LongMethod")
    private fun fftInPlace(data: FloatArray, n: Int, inverse: Boolean) {
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
        // Caller is responsible for dividing by n for IFFT — done in process()
    }

    companion object {
        private const val RMS_QUIET_THRESHOLD = 0.01f
        private const val ALPHA = 0.1f
        private const val BETA = 1.5f
        private const val GAMMA = 0.002f
    }
}
```

- [ ] **Step 4: Run tests — all green**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.AudioPreprocessorTest" -x lint 2>&1 | tail -20
```

Expected: `4 tests completed, 0 failed`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/AudioPreprocessor.kt \
        app/src/test/java/com/sound2inat/inference/AudioPreprocessorTest.kt
git commit -m "$(cat <<'EOF'
feat(inference): add high-pass filter and spectral subtractor

2nd-order Butterworth IIR biquad at 250 Hz for rumble removal.
Adaptive EMA noise profile from quiet windows with power-domain
spectral subtraction (β=1.5 over-subtraction, γ=0.002 floor).
Pure-Kotlin Cooley-Tukey FFT — no external library needed.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: YamNetGate interface + YamNetTfliteGate + unit tests

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/YamNetGate.kt`
- Create: `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`
- Create: `app/src/test/java/com/sound2inat/inference/YamNetGateTest.kt`

**Context:** `YamNetGate` is a `fun interface` with a single `suspend fun isBiological(...)` returning `false` only when the window is clearly non-biological (top-1 class is a noise class AND bio score < 0.15). `YamNetTfliteGate` lazily loads the YAMNet model on first call (protected by `Mutex`); on any exception it returns `true` (fail-open). Class index resolution is done by parsing the labels CSV at load time (display names → indices) so no hardcoded indices are needed. Resampling to 16 kHz uses linear interpolation (same quality as the existing `Resampler`).

**Dependencies:** `ModelManager` (already exists), `InterpreterFactory` (already exists), `YamNetV1.descriptor` (added in Task 3). The test creates both via subclassing/fake factories.

**Note:** `YamNetV1` won't exist until Task 3. Either do Task 3 first OR temporarily use a local placeholder descriptor in `YamNetTfliteGate` and replace it in Task 3. Recommended: do Task 3 before Task 2, or declare `YamNetV1.descriptor` with a stub SHA to unblock compilation.

---

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/inference/YamNetGateTest.kt`:

```kotlin
package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.modelmanager.ModelDescriptor
import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YamNetGateTest {
    @get:Rule val tmp = TemporaryFolder()

    // Minimal CSV: header + 521 rows. Bio=index 100 ("Bird"), Noise=index 0 ("Speech").
    private val labels521 = buildString {
        appendLine("index,mid,display_name")
        appendLine("0,/m/09x0r,Speech")
        appendLine("1,/m/abc,Engine")
        for (i in 2..99) appendLine("$i,/m/x$i,Other$i")
        appendLine("100,/m/015p6,Bird")
        appendLine("101,/m/03vt0,Frog")
        for (i in 102..520) appendLine("$i,/m/x$i,Dummy$i")
    }

    private fun buildGate(probs: FloatArray): YamNetTfliteGate {
        val modelFile = tmp.newFile("yamnet.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("yamnet.labels.csv").apply { writeText(labels521) }
        val fakeFactory = object : InterpreterFactory {
            override fun create(m: File, threads: Int): InterpreterApi = object : InterpreterApi {
                override val outputTensorCount = 1
                override fun getOutputShape(i: Int) = intArrayOf(1, probs.size)
                override fun run(input: Any, output: Any) {
                    @Suppress("UNCHECKED_CAST")
                    probs.copyInto((output as Array<FloatArray>)[0])
                }
                override fun runForMultipleOutputs(input: Any, outputs: Map<Int, Any>) = Unit
                override fun close() {}
            }
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(d: ModelDescriptor) =
                ModelInstallState.Ready(modelFile, labelsFile)
            override suspend fun install(d: ModelDescriptor, emit: (ModelInstallState) -> Unit) {}
            override fun remove(d: ModelDescriptor) {}
        }
        return YamNetTfliteGate(fakeFactory, fakeManager)
    }

    @Test
    fun `returns false when speech is top class and bio score below 0_15`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.90f    // Speech (noise) is top class
        probs[100] = 0.05f  // Bird bio score < 0.15
        assertThat(buildGate(probs).isBiological(FloatArray(16_000), 16_000)).isFalse()
    }

    @Test
    fun `returns true when bird score is above threshold`() = runTest {
        val probs = FloatArray(521)
        probs[100] = 0.80f  // Bird high bio score
        probs[0] = 0.10f    // Speech low
        assertThat(buildGate(probs).isBiological(FloatArray(16_000), 16_000)).isTrue()
    }

    @Test
    fun `returns true when top is noise but bio score is at or above threshold`() = runTest {
        val probs = FloatArray(521)
        probs[0] = 0.70f    // Speech top
        probs[100] = 0.20f  // Bird bio score >= 0.15 → still biological
        assertThat(buildGate(probs).isBiological(FloatArray(16_000), 16_000)).isTrue()
    }

    @Test
    fun `returns true on interpreter creation exception — fail-open`() = runTest {
        val modelFile = tmp.newFile("boom.tflite").apply { writeBytes(byteArrayOf(0)) }
        val labelsFile = tmp.newFile("boom.csv").apply { writeText(labels521) }
        val throwingFactory = object : InterpreterFactory {
            override fun create(m: File, threads: Int): InterpreterApi = error("deliberate failure")
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(d: ModelDescriptor) =
                ModelInstallState.Ready(modelFile, labelsFile)
            override suspend fun install(d: ModelDescriptor, emit: (ModelInstallState) -> Unit) {}
            override fun remove(d: ModelDescriptor) {}
        }
        val gate = YamNetTfliteGate(throwingFactory, fakeManager)
        assertThat(gate.isBiological(FloatArray(16_000), 16_000)).isTrue()
    }

    @Test
    fun `returns true when model not yet installed — fail-open`() = runTest {
        val fakeFactory = object : InterpreterFactory {
            override fun create(m: File, threads: Int): InterpreterApi = error("should not be called")
        }
        val fakeManager = object : ModelManager(tmp.root, OkHttpClient()) {
            override suspend fun stateFor(d: ModelDescriptor) = ModelInstallState.NotInstalled
            override suspend fun install(d: ModelDescriptor, emit: (ModelInstallState) -> Unit) {}
            override fun remove(d: ModelDescriptor) {}
        }
        val gate = YamNetTfliteGate(fakeFactory, fakeManager)
        assertThat(gate.isBiological(FloatArray(16_000), 16_000)).isTrue()
    }
}
```

- [ ] **Step 2: Run tests — verify compile error**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.YamNetGateTest" -x lint 2>&1 | tail -20
```

Expected: compile error (`YamNetGate`, `YamNetTfliteGate` not found).

- [ ] **Step 3: Create `YamNetGate.kt` (the interface)**

Create `app/src/main/java/com/sound2inat/inference/YamNetGate.kt`:

```kotlin
package com.sound2inat.inference

fun interface YamNetGate {
    /** Returns false if the window is clearly non-biological and should be skipped. */
    suspend fun isBiological(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean
}
```

- [ ] **Step 4: Create `YamNetTfliteGate.kt`**

Create `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`:

```kotlin
package com.sound2inat.inference

import com.sound2inat.modelmanager.ModelInstallState
import com.sound2inat.modelmanager.ModelManager
import com.sound2inat.modelmanager.YamNetV1
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * YAMNet-based biological gate. Resamples each window to 16 kHz, splits into
 * 0.975 s frames, runs YAMNet TFLite, and returns false only when the maximum
 * biological class score across all frames is < 0.15 AND the top-1 class in
 * that frame is a known noise class. Fail-open: any error returns true.
 */
class YamNetTfliteGate(
    private val factory: InterpreterFactory,
    private val modelManager: ModelManager,
) : YamNetGate {

    private val mutex = Mutex()
    private var interpreter: InterpreterApi? = null
    private var bioIndices: Set<Int> = emptySet()
    private var noiseIndices: Set<Int> = emptySet()

    override suspend fun isBiological(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean =
        runCatching { isBiologicalInternal(pcmFloat32, sampleRateHz) }.getOrDefault(true)

    @Suppress("ReturnCount")
    private suspend fun isBiologicalInternal(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean {
        ensureLoaded()
        val interp = interpreter ?: return true  // model not installed yet
        val resampled = resampleTo16k(pcmFloat32, sampleRateHz)

        var maxBioScore = 0f
        var anyNoiseTopWithLowBio = false
        var frameStart = 0
        while (frameStart < resampled.size) {
            val frame = FloatArray(YAMNET_FRAME_SIZE)  // zero-padded if last frame is short
            val end = (frameStart + YAMNET_FRAME_SIZE).coerceAtMost(resampled.size)
            resampled.copyInto(frame, destinationOffset = 0, startIndex = frameStart, endIndex = end)

            val input = arrayOf(frame)
            val output = arrayOf(FloatArray(CLASS_COUNT))
            interp.run(input, output)
            val probs = output[0]

            val bioScore = bioIndices.maxOfOrNull { probs[it] } ?: 0f
            val topClass = probs.indices.maxByOrNull { probs[it] } ?: 0
            if (bioScore > maxBioScore) maxBioScore = bioScore
            if (topClass in noiseIndices && bioScore < BIO_THRESHOLD) anyNoiseTopWithLowBio = true
            frameStart += YAMNET_FRAME_SIZE
        }
        return !(maxBioScore < BIO_THRESHOLD && anyNoiseTopWithLowBio)
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (interpreter != null) return@withLock
        val state = modelManager.stateFor(YamNetV1.descriptor) as? ModelInstallState.Ready
            ?: return@withLock
        val classMap = parseClassMap(state.labelsFile)
        bioIndices = BIOLOGICAL_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        noiseIndices = NOISE_DISPLAY_NAMES.mapNotNull { classMap[it] }.toSet()
        interpreter = factory.create(state.modelFile, threads = 1)
    }

    private fun parseClassMap(file: File): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            for (line in lines.drop(1)) {  // skip header: index,mid,display_name
                val parts = line.split(",", limit = 3)
                if (parts.size == 3) {
                    val idx = parts[0].trim().toIntOrNull() ?: continue
                    result[parts[2].trim()] = idx
                }
            }
        }
        return result
    }

    private fun resampleTo16k(input: FloatArray, fromRate: Int): FloatArray {
        if (fromRate == YAMNET_SAMPLE_RATE || input.size < 2) return input
        val ratio = fromRate.toDouble() / YAMNET_SAMPLE_RATE
        val outLen = ((input.size - 1) / ratio).toInt() + 1
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            input[i0] + (input[i1] - input[i0]) * frac
        }
    }

    companion object {
        private const val YAMNET_SAMPLE_RATE = 16_000
        private const val YAMNET_FRAME_SIZE = 15_600  // 0.975 s at 16 kHz
        private const val CLASS_COUNT = 521
        private const val BIO_THRESHOLD = 0.15f

        // Display names from yamnet_class_map.csv — verify against downloaded CSV if gate misfires
        private val BIOLOGICAL_DISPLAY_NAMES = setOf(
            "Animal", "Wild animals",
            "Bird", "Bird vocalization, bird call, bird song", "Chirp, tweet", "Squawk",
            "Pigeon, dove", "Cooing", "Crow", "Caw", "Owl", "Hoot", "Bird flight, flapping wings",
            "Frog", "Croak",
            "Insect", "Cricket", "Bee, wasp, etc.",
            "Rodents, rats, mice",
            "Silence",
        )
        private val NOISE_DISPLAY_NAMES = setOf(
            "Speech", "Engine", "Motor vehicle (road)", "Car", "Motorcycle", "Truck",
            "Rail transport", "Boat, Water vehicle", "Aircraft",
            "Mechanical fan, air conditioning fan", "Wind", "Rain", "Thunder", "Music",
        )
    }
}
```

**Note:** `YamNetV1` is declared in Task 3. If implementing Task 2 before Task 3, add a temporary stub in `ModelDescriptor.kt`:
```kotlin
object YamNetV1 {
    val descriptor = ModelDescriptor(
        id = "yamnet_v1", displayName = "YAMNet v1", version = "1",
        modelUrl = "", labelsUrl = "", modelSha256 = "", labelsSha256 = "",
        license = "Apache 2.0", sizeBytes = 0L, sampleRateHz = 16_000,
        windowMs = 975L, labelsFormat = LabelsFormat.BirdNetUnderscore, hidden = true,
    )
}
```
Replace with the real descriptor in Task 3.

- [ ] **Step 5: Run tests — all green**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.YamNetGateTest" -x lint 2>&1 | tail -20
```

Expected: `5 tests completed, 0 failed`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/YamNetGate.kt \
        app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt \
        app/src/test/java/com/sound2inat/inference/YamNetGateTest.kt
git commit -m "$(cat <<'EOF'
feat(inference): add YamNetGate interface and TFLite implementation

Biological gate that runs YAMNet on each 0.975 s frame, aggregating
the max bio score (Bird/Frog/Insect/Animal) across frames. Skips the
window only when bio score < 0.15 AND top-1 class is a known noise
class. Fail-open: any error or missing model passes window through.
Class indices resolved from yamnet_class_map.csv at load time.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: ModelDescriptor hidden flag + YamNetV1 + ModelManager auto-install

**Files:**
- Modify: `app/src/main/java/com/sound2inat/modelmanager/ModelDescriptor.kt`
- Modify: `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`

**Context:** `ModelDescriptor` gets a `hidden: Boolean = false` field. Hidden models don't appear in the Settings UI. `YamNetV1` is a hidden descriptor downloaded automatically alongside the first visible model install. `ModelManager` gains an optional `hiddenDescriptors` constructor param; after a successful non-hidden install it silently installs any hidden models not yet on disk. `FakeModelManager` in `TestModule.kt` already overrides `install()` so the auto-install path is never triggered in e2e tests.

**SHA-256 note:** The real SHA values for the YAMNet model and labels CSV must be computed before release. Step 2 of this task includes the shell commands to do so. Use placeholder strings until then — they cause no test failures (tests use `FakeModelManager` and never verify SHA).

---

- [ ] **Step 1: Add `hidden` field to `ModelDescriptor` and add `YamNetV1`**

Open `app/src/main/java/com/sound2inat/modelmanager/ModelDescriptor.kt`.

Add `val hidden: Boolean = false` at the end of the `data class ModelDescriptor(...)` parameter list (after `labelsFormat`).

Then add the `YamNetV1` object at the bottom of the file:

```kotlin
object YamNetV1 {
    val descriptor = ModelDescriptor(
        id = "yamnet_v1",
        displayName = "YAMNet v1",
        version = "1",
        modelUrl = "https://storage.googleapis.com/tfhub-lite-models/google/lite-model/" +
            "yamnet/classification/tflite/v1.tflite",
        labelsUrl = "https://raw.githubusercontent.com/tensorflow/models/master/" +
            "research/audioset/yamnet/yamnet_class_map.csv",
        // Compute these by running the shell commands in Step 2 and update before shipping.
        modelSha256 = "PLACEHOLDER_COMPUTE_SHA256",
        labelsSha256 = "PLACEHOLDER_COMPUTE_SHA256",
        license = "Apache 2.0",
        sizeBytes = 3_887_000L,
        sampleRateHz = 16_000,
        windowMs = 975L,
        labelsFormat = LabelsFormat.BirdNetUnderscore,  // unused — gate parses CSV directly
        hidden = true,
    )
}
```

Verify the file still compiles:
```bash
./gradlew :app:compileDebugKotlin -x lint 2>&1 | tail -10
```

- [ ] **Step 2: Compute SHA-256 hashes for YAMNet files (do once, update descriptor)**

Run (requires `curl` and `sha256sum` / `shasum`):
```bash
curl -L "https://storage.googleapis.com/tfhub-lite-models/google/lite-model/yamnet/classification/tflite/v1.tflite" \
  -o /tmp/yamnet.tflite && sha256sum /tmp/yamnet.tflite

curl -L "https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv" \
  -o /tmp/yamnet_labels.csv && sha256sum /tmp/yamnet_labels.csv
```

Copy the hex strings (64 chars each) into `YamNetV1.descriptor.modelSha256` and `labelsSha256`.

Also verify the class map contains expected biological display names used in `YamNetTfliteGate.BIOLOGICAL_DISPLAY_NAMES` — if any name doesn't match, update the set in `YamNetTfliteGate.kt`.

- [ ] **Step 3: Update `ModelManager` to add `hiddenDescriptors` and auto-install**

Open `app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt`.

Add `private val hiddenDescriptors: List<ModelDescriptor> = emptyList()` as the 4th constructor parameter (after `ioDispatcher`):

```kotlin
open class ModelManager(
    private val filesDir: File,
    private val http: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hiddenDescriptors: List<ModelDescriptor> = emptyList(),
) {
```

In the `install()` method, add the auto-install block immediately after the `emit(ModelInstallState.Ready(mFinal, lFinal))` line (still inside the `try` block):

```kotlin
emit(ModelInstallState.Ready(mFinal, lFinal))
// Silently install hidden companion models (e.g. YAMNet) after any visible model succeeds.
if (!descriptor.hidden) {
    for (hidden in hiddenDescriptors) {
        if (stateFor(hidden) !is ModelInstallState.Ready) {
            install(hidden) { /* progress silently ignored */ }
        }
    }
}
```

Compile check:
```bash
./gradlew :app:compileDebugKotlin -x lint 2>&1 | tail -10
```

- [ ] **Step 4: Run all unit tests — no regressions**

```bash
./gradlew :app:testDebugUnitTest -x lint 2>&1 | tail -20
```

Expected: all green (existing `ModelManagerTest` should still pass since `hiddenDescriptors` defaults to empty).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/modelmanager/ModelDescriptor.kt \
        app/src/main/java/com/sound2inat/modelmanager/ModelManager.kt
git commit -m "$(cat <<'EOF'
feat(modelmanager): add hidden model support and YamNetV1 descriptor

ModelDescriptor.hidden=true marks models that are downloaded
automatically but not shown in Settings. ModelManager.install()
now silently installs all hiddenDescriptors after a successful
visible-model install. YamNetV1 (~3.7 MB) uses this mechanism.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: InferenceRunner pipeline wiring + gate-skips-window test

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt`

**Context:** `InferenceRunner` gains two optional params: `spectralSubtractor: SpectralSubtractor?` and `yamNetGate: YamNetGate?`. The full signal (after resampling) is converted to float and passed through `highPassFilter()` before slicing. Inside the window loop: subtractor is applied first, then gate is checked; if gate returns `false` the window is skipped. Progress is updated even for skipped windows so the progress bar remains smooth.

---

- [ ] **Step 1: Add gate-skips-window test to `InferenceRunnerTest.kt`**

Open `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt`.

Add the following test after the existing three tests (inside `class InferenceRunnerTest`):

```kotlin
@Test
fun `gate returning false skips all windows — no model predictions`() = runTest {
    val wav = writeSilentWav(durationSeconds = 5)
    val model = RecordingFakeModel()
    val alwaysSkipGate = YamNetGate { _, _ -> false }
    val runner = InferenceRunner(model, hopSeconds = 1f, yamNetGate = alwaysSkipGate)

    val out = runner.run(wav, latitude = null, longitude = null, observedAtMillis = 0L)

    assertThat(out).isEmpty()
    assertThat(model.calls).isEmpty()
    assertThat(runner.progress.value).isEqualTo(1.0f)
}
```

Run it to confirm it fails (method signature doesn't exist yet):
```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.InferenceRunnerTest.gate*" -x lint 2>&1 | tail -15
```

- [ ] **Step 2: Rewrite `InferenceRunner.kt`**

Replace the entire content of `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` with:

```kotlin
package com.sound2inat.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Slices a mono 16-bit WAV into fixed windows hopped by [hopSeconds], optionally
 * applies spectral subtraction and a YAMNet biological gate, and calls [model]
 * per window that passes the gate.
 *
 * Pipeline per run:
 *   1. Read + resample to model rate (ShortArray).
 *   2. Normalize to [-1, 1] and apply high-pass filter to full signal (FloatArray).
 *   3. Per window: SpectralSubtractor → YamNetGate check → model.predict().
 *
 * Pass null for [spectralSubtractor] or [yamNetGate] to skip those steps.
 * Fail-open: the gate's own error handling returns true on any exception.
 */
class InferenceRunner(
    private val model: BioacousticModel,
    private val hopSeconds: Float = 1f,
    private val spectralSubtractor: SpectralSubtractor? = null,
    private val yamNetGate: YamNetGate? = null,
) {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    suspend fun run(
        wavFile: File,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        _progress.value = 0f
        val (rawSamples, nativeRate) = WavReader.readMono16(wavFile)
        val targetRate = model.expectedSampleRateHz
        val resampled = if (nativeRate == targetRate) rawSamples
                        else Resampler.resample(rawSamples, nativeRate, targetRate)

        // Normalize and apply high-pass filter to full signal before slicing.
        val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }
        val filtered = highPassFilter(normalized, targetRate)

        val windowSeconds = model.windowMs / MS_PER_SECOND
        val win = (windowSeconds * targetRate).toInt()
        val hop = (hopSeconds * targetRate).toInt()
        require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }
        val frames = if (filtered.size < win) 0 else 1 + (filtered.size - win) / hop
        if (frames == 0) {
            _progress.value = 1f
            return emptyList()
        }
        val out = ArrayList<WindowPrediction>(frames * 5)
        for (f in 0 until frames) {
            val s = f * hop
            var window = filtered.copyOfRange(s, s + win)
            window = spectralSubtractor?.process(window) ?: window
            _progress.value = (f + 1).toFloat() / frames
            if (yamNetGate?.isBiological(window, targetRate) == false) continue
            val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
            val endMs = ((s + win).toLong() * MS_PER_SECOND_LONG) / targetRate
            out += model.predict(
                pcmFloat32 = window,
                sampleRateHz = targetRate,
                latitude = latitude,
                longitude = longitude,
                observedAtMillis = observedAtMillis,
                windowStartMs = startMs,
                windowEndMs = endMs,
            )
        }
        return out
    }

    private companion object {
        const val MS_PER_SECOND = 1_000f
        const val MS_PER_SECOND_LONG = 1_000L
    }
}

/**
 * Reads a mono 16-bit PCM WAV produced by `recorder.WavWriter` (clean 44-byte
 * header, fmt chunk size 16, PCM data chunk immediately after). Not a general
 * RIFF parser — extra chunks (e.g. LIST/INFO) are NOT supported.
 */
internal object WavReader {
    fun readMono16(file: File): Pair<ShortArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44).also { raf.readFully(it) }
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                "Not a WAV file"
            }
            val ch = readLeUint16(header, 22)
            val sr = readLeUint32(header, 24)
            val bits = readLeUint16(header, 34)
            require(ch == 1 && bits == 16) { "Mono 16-bit PCM only (got ch=$ch bits=$bits)" }
            val dataSize = readLeUint32(header, 40)
            val raw = ByteArray(dataSize)
            raf.readFully(raw)
            val samples = ShortArray(dataSize / 2)
            for (i in samples.indices) {
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }
            return samples to sr
        }
    }

    private fun readLeUint16(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    private fun readLeUint32(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or
            ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or
            ((buf[o + 3].toInt() and 0xFF) shl 24)
}
```

- [ ] **Step 3: Run all InferenceRunner tests — all green**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.InferenceRunnerTest" -x lint 2>&1 | tail -20
```

Expected: `4 tests completed, 0 failed` (3 original + 1 new gate test).

- [ ] **Step 4: Run full unit test suite — no regressions**

```bash
./gradlew :app:testDebugUnitTest -x lint 2>&1 | tail -20
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/InferenceRunner.kt \
        app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt
git commit -m "$(cat <<'EOF'
feat(inference): wire HPF, spectral subtractor, and YAMNet gate into pipeline

High-pass filter (250 Hz) applied to full resampled signal before slicing.
Per window: optional SpectralSubtractor then optional YamNetGate check;
gate=false skips the window entirely. Progress updates even for skipped
windows. SpectralSubtractor and YamNetGate are null by default (opt-in).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Settings + UI — noise reduction toggles + SettingsViewModelTest

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/data/Settings.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt`

**Context:** Two new DataStore boolean prefs default to `true`. `SettingsUiState` gets two new `Boolean` fields. `SettingsViewModel` gets new flow/setter pairs (same pattern as `regionalFilterEnabled`). `SettingsScreen` adds a `NoiseReductionSection` composable between the "Inference" and "Regional filter" cards. The `SettingsViewModelTest.build()` helper and constructor grow two new optional params.

---

- [ ] **Step 1: Update `Settings.kt`**

Open `app/src/main/java/com/sound2inat/app/data/Settings.kt`.

Add to the `K` object:
```kotlin
val SPECTRAL_SUBTRACTION_ENABLED = booleanPreferencesKey("spectral_subtraction_enabled")
val YAMNET_GATE_ENABLED = booleanPreferencesKey("yamnet_gate_enabled")
```

Add two new flows after `val minWindows`:
```kotlin
val spectralSubtractionEnabled: Flow<Boolean> =
    ctx.dataStore.data.map { it[K.SPECTRAL_SUBTRACTION_ENABLED] ?: true }
val yamNetGateEnabled: Flow<Boolean> =
    ctx.dataStore.data.map { it[K.YAMNET_GATE_ENABLED] ?: true }
```

Add two new setters after `setMinWindows`:
```kotlin
suspend fun setSpectralSubtractionEnabled(v: Boolean) {
    ctx.dataStore.edit { it[K.SPECTRAL_SUBTRACTION_ENABLED] = v }
}
suspend fun setYamNetGateEnabled(v: Boolean) {
    ctx.dataStore.edit { it[K.YAMNET_GATE_ENABLED] = v }
}
```

- [ ] **Step 2: Update `SettingsUiState.kt`**

Open `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt`.

Add two fields to `SettingsUiState` after `minWindows`:
```kotlin
val spectralSubtractionEnabled: Boolean = true,
val yamNetGateEnabled: Boolean = true,
```

- [ ] **Step 3: Update `SettingsViewModel.kt`**

Open `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`.

**3a.** Add four parameters to the `SettingsViewModel` constructor after `writeMinWindows`:
```kotlin
private val spectralSubtractionEnabledFlow: Flow<Boolean>,
private val writeSpectralSubtractionEnabled: suspend (Boolean) -> Unit,
private val yamNetGateEnabledFlow: Flow<Boolean>,
private val writeYamNetGateEnabled: suspend (Boolean) -> Unit,
```

**3b.** Add two collectors in `init` (after the `minWindowsFlow` collector):
```kotlin
scope.launch {
    spectralSubtractionEnabledFlow.collect { v ->
        _state.value = _state.value.copy(spectralSubtractionEnabled = v)
    }
}
scope.launch {
    yamNetGateEnabledFlow.collect { v ->
        _state.value = _state.value.copy(yamNetGateEnabled = v)
    }
}
```

**3c.** Add two public setter functions after `setMinWindows`:
```kotlin
fun setSpectralSubtractionEnabled(v: Boolean) { scope.launch { writeSpectralSubtractionEnabled(v) } }
fun setYamNetGateEnabled(v: Boolean) { scope.launch { writeYamNetGateEnabled(v) } }
```

**3d.** Wire in `SettingsViewModelHilt.delegate` — add after `writeMinWindows`:
```kotlin
spectralSubtractionEnabledFlow = settings.spectralSubtractionEnabled,
writeSpectralSubtractionEnabled = { settings.setSpectralSubtractionEnabled(it) },
yamNetGateEnabledFlow = settings.yamNetGateEnabled,
writeYamNetGateEnabled = { settings.setYamNetGateEnabled(it) },
```

- [ ] **Step 4: Update `SettingsScreen.kt`**

Open `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt`.

**4a.** Add a new `SectionCard` in the main `Column` between `"Inference"` and `"Regional filter"`:
```kotlin
SectionCard(title = "Noise reduction") {
    NoiseReductionSection(state, vm)
}
```

**4b.** Add the `NoiseReductionSection` composable (private) after `InferenceSection`:
```kotlin
@Suppress("FunctionNaming")
@Composable
private fun NoiseReductionSection(state: SettingsUiState, vm: SettingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Spectral noise reduction")
        Switch(
            checked = state.spectralSubtractionEnabled,
            onCheckedChange = { vm.setSpectralSubtractionEnabled(it) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("YAMNet biological gate")
        Switch(
            checked = state.yamNetGateEnabled,
            onCheckedChange = { vm.setYamNetGateEnabled(it) },
        )
    }
}
```

- [ ] **Step 5: Update `SettingsViewModelTest.kt`**

Open `app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt`.

**5a.** Add two new tests after the `setMinWindows` test:
```kotlin
@Test
fun `setSpectralSubtractionEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
    val captured = mutableListOf<Boolean>()
    val flow = MutableStateFlow(true)
    val vm = build(
        initial = ModelInstallState.NotInstalled,
        spectralSubtractionEnabledFlow = flow,
        writeSpectralSubtractionEnabled = { captured += it; flow.value = it },
        scope = backgroundScope,
    )
    vm.setSpectralSubtractionEnabled(false)
    assertThat(captured).containsExactly(false)
    assertThat(vm.state.value.spectralSubtractionEnabled).isFalse()
}

@Test
fun `setYamNetGateEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
    val captured = mutableListOf<Boolean>()
    val flow = MutableStateFlow(true)
    val vm = build(
        initial = ModelInstallState.NotInstalled,
        yamNetGateEnabledFlow = flow,
        writeYamNetGateEnabled = { captured += it; flow.value = it },
        scope = backgroundScope,
    )
    vm.setYamNetGateEnabled(false)
    assertThat(captured).containsExactly(false)
    assertThat(vm.state.value.yamNetGateEnabled).isFalse()
}
```

**5b.** Add four new optional parameters to the `build()` helper (after `writeMinWindows`):
```kotlin
spectralSubtractionEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
writeSpectralSubtractionEnabled: suspend (Boolean) -> Unit = {},
yamNetGateEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
writeYamNetGateEnabled: suspend (Boolean) -> Unit = {},
```

**5c.** Wire them into the `SettingsViewModel(...)` call inside `build()`:
```kotlin
spectralSubtractionEnabledFlow = spectralSubtractionEnabledFlow,
writeSpectralSubtractionEnabled = writeSpectralSubtractionEnabled,
yamNetGateEnabledFlow = yamNetGateEnabledFlow,
writeYamNetGateEnabled = writeYamNetGateEnabled,
```

**5d.** Update the `multiple descriptors` test to pass the new required params (they use defaults, so just ensure the `SettingsViewModel(...)` call inside that test also includes the new params with default values):
The test builds `SettingsViewModel` directly — add the four new params with MutableStateFlow defaults:
```kotlin
spectralSubtractionEnabledFlow = MutableStateFlow(true),
writeSpectralSubtractionEnabled = {},
yamNetGateEnabledFlow = MutableStateFlow(true),
writeYamNetGateEnabled = {},
```

- [ ] **Step 6: Run all Settings-related tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.settings.*" -x lint 2>&1 | tail -20
```

Expected: all green (including 2 new tests).

- [ ] **Step 7: Run full unit test suite — no regressions**

```bash
./gradlew :app:testDebugUnitTest -x lint 2>&1 | tail -20
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/data/Settings.kt \
        app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt \
        app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt \
        app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt \
        app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(settings): add noise reduction toggles (spectral subtraction + YAMNet gate)

Two new DataStore boolean prefs default to true. Settings screen gains
a "Noise reduction" card between Inference and Regional filter with
independent switches for each feature.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: DI wiring + ProductionInferenceJob integration + TestModule null injection

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/androidTest/java/com/sound2inat/app/TestModule.kt`

**Context:**

`YamNetGate?` is provided as a nullable singleton from `SwappableModule` so `TestSwappableModule` can override it with `null`. `SpectralSubtractor` is NOT injected — `ProductionInferenceJob` creates a fresh instance per InferenceRunner call so the noise profile resets between models and runs. `ProductionInferenceJob` reads `settings.spectralSubtractionEnabled` and `settings.yamNetGateEnabled` at runtime to decide whether to use each component.

`SwappableModule.provideModelManager()` is updated to pass `hiddenDescriptors = listOf(YamNetV1.descriptor)` so YAMNet is auto-downloaded in production.

`TestSwappableModule` provides `yamNetGate(): YamNetGate? = null` and the `ModelManager` override already extends `FakeModelManager` (which overrides `install()` — the hidden auto-install never runs in tests). `SpectralSubtractor` runs silently on the test's silent WAV, but `FakeBioacousticModel.predict()` ignores PCM content so this causes no test failures.

---

- [ ] **Step 1: Update `SwappableModule.kt`**

Open `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`.

**1a.** Add imports at the top:
```kotlin
import com.sound2inat.inference.YamNetGate
import com.sound2inat.inference.YamNetTfliteGate
import com.sound2inat.modelmanager.YamNetV1
```

**1b.** Replace the `provideModelManager` function to pass `hiddenDescriptors`:
```kotlin
@Provides @Singleton
fun provideModelManager(
    @ApplicationContext ctx: Context,
    http: OkHttpClient,
): ModelManager = ModelManager(ctx.filesDir, http, hiddenDescriptors = listOf(YamNetV1.descriptor))
```

**1c.** Add a new provider for `YamNetGate?` (nullable — TestSwappableModule can return null):
```kotlin
@Provides @Singleton
fun provideYamNetGate(factory: InterpreterFactory, manager: ModelManager): YamNetGate =
    YamNetTfliteGate(factory, manager)
```

Note: Hilt injects the return type `YamNetGate` (non-null here in SwappableModule). The test module overrides this to return null by declaring the return type as `YamNetGate?`. See Step 2 for how to handle this.

Actually, since Hilt doesn't natively support nullable overrides unless both sites use the same type, use the pattern:

```kotlin
@Provides @Singleton
fun provideYamNetGate(factory: InterpreterFactory, manager: ModelManager): YamNetGate? =
    YamNetTfliteGate(factory, manager)
```

And inject as `YamNetGate?` in `ReviewViewModelHilt`.

- [ ] **Step 2: Update `TestModule.kt` — inject null for YamNetGate**

Open `app/src/androidTest/java/com/sound2inat/app/TestModule.kt`.

Add import: `import com.sound2inat.inference.YamNetGate`

Add the following provider inside `TestSwappableModule`:
```kotlin
@Provides @Singleton
fun provideYamNetGate(): YamNetGate? = null
```

This overrides `SwappableModule.provideYamNetGate()` in instrumented tests, ensuring no YAMNet inference runs (the gate is null, so `yamNetGate?.isBiological(...)` is never called).

- [ ] **Step 3: Update `ReviewViewModel.kt` — wire yamNetGate into ProductionInferenceJob**

Open `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`.

**3a.** Add import:
```kotlin
import com.sound2inat.inference.SpectralSubtractor
import com.sound2inat.inference.YamNetGate
```

**3b.** Add `yamNetGate: YamNetGate?` to `ReviewViewModelHilt`'s `@Inject constructor`:
```kotlin
@HiltViewModel
@Suppress("LongParameterList")
class ReviewViewModelHilt @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repo: DraftRepository,
    private val models: List<@JvmSuppressWildcards BioacousticModel>,
    private val descriptors: List<@JvmSuppressWildcards ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val submitter: INatSubmitter,
    private val inatObservationsDao: InatObservationDao,
    private val inatClient: INaturalistClient,
    private val regionFilter: RegionFilter,
    private val yamNetGate: YamNetGate?,         // ← add this
) : ViewModel() {
```

**3c.** Update the `ProductionInferenceJob(...)` instantiation in `ReviewViewModelHilt`:
```kotlin
inference = ProductionInferenceJob(models, descriptors, modelManager, settings, regionFilter, yamNetGate),
```

**3d.** Update `ProductionInferenceJob` class — add `yamNetGate: YamNetGate?` parameter:
```kotlin
private class ProductionInferenceJob(
    private val models: List<BioacousticModel>,
    private val descriptors: List<ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val regionFilter: RegionFilter,
    private val yamNetGate: YamNetGate?,          // ← add this
) : InferenceJob {
```

**3e.** In `ProductionInferenceJob.run()`, add settings checks and pass subtractor/gate to `InferenceRunner`. Find the line:
```kotlin
val runner = InferenceRunner(model)
```
Replace it with:
```kotlin
val spectralEnabled = settings.spectralSubtractionEnabled.first()
val yamNetEnabled = settings.yamNetGateEnabled.first()
val subtractor = if (spectralEnabled) SpectralSubtractor() else null
val activeGate = if (yamNetEnabled) yamNetGate else null
val runner = InferenceRunner(model, spectralSubtractor = subtractor, yamNetGate = activeGate)
```

- [ ] **Step 4: Compile check**

```bash
./gradlew :app:compileDebugKotlin -x lint 2>&1 | tail -15
```

Expected: clean build. Fix any Hilt-related nullable injection errors if they appear (e.g., add `@JvmSuppressWildcards` or `javax.annotation.Nullable` annotations if needed by the Dagger version in use).

- [ ] **Step 5: Run full unit test suite**

```bash
./gradlew :app:testDebugUnitTest -x lint 2>&1 | tail -20
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/di/SwappableModule.kt \
        app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt \
        app/src/androidTest/java/com/sound2inat/app/TestModule.kt
git commit -m "$(cat <<'EOF'
feat(di): wire YamNetGate and SpectralSubtractor into inference pipeline

SwappableModule provides YamNetTfliteGate as nullable singleton; TestModule
injects null to bypass YAMNet in e2e tests. ProductionInferenceJob creates
a fresh SpectralSubtractor per model per run (noise profile resets correctly).
Both features respect their Settings toggles at inference time.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Post-implementation checklist

After all 6 tasks are committed:

- [ ] Run the full unit test suite one final time: `./gradlew :app:testDebugUnitTest -x lint`
- [ ] Build the debug APK: `./gradlew :app:assembleDebug -x lint`
- [ ] Verify `YamNetV1.descriptor.modelSha256` and `labelsSha256` have been set to real hashes (Task 3 Step 2), not the placeholder strings.
- [ ] On a real device: install BirdNET → verify YAMNet auto-downloads in the background.
- [ ] Record a noisy environment (traffic/wind) and a bird recording — confirm gate reduces false positives.
