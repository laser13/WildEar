# Noise Reduction Design

**Date:** 2026-04-30
**Status:** Approved

## Problem

BirdNET and Perch produce false positives when recordings contain background noise (motors,
wind, traffic) mixed with biological sounds. Two orthogonal improvements are needed:

1. **Spectral subtraction** — reduce stationary background noise from the audio signal itself,
   so both BirdNET and Perch receive cleaner input.
2. **YAMNet gate** — skip windows that are clearly non-biological (engine, speech, wind),
   reducing false positives and saving inference time.

These are independent: spectral subtraction improves signal quality; YAMNet decides whether
to run the expensive models at all.

## Pipeline

```
WAV
  ↓
High-pass filter (always on, 250 Hz cutoff) — applied to full signal before slicing
  ↓
Slice into windows (3 s for BirdNET, 5 s for Perch, 1 s hop)
  ↓ per window:
SpectralSubtractor (if enabled)
  · tihoe okno (RMS < threshold) → update noise profile (EMA, α=0.1)
  · gromkoe okno → FFT → subtract profile → IFFT
  ↓
YamNetGate (if enabled)
  · resample window to 16 kHz
  · run YAMNet TFLite → 521-class probabilities
  · "biological" score = max(Bird + Frog + Insect + Animal classes)
  · if biological score < 0.15 AND top-1 is noise class → skip window
  · otherwise → continue
  ↓
BioacousticModel.predict() (BirdNET / Perch)
  ↓
WindowPrediction[]
```

Fail-open: on any YAMNet or preprocessor error, the window passes through unchanged.

## High-Pass Filter

**File:** `inference/AudioPreprocessor.kt`

2nd-order Butterworth IIR biquad. Cutoff: 250 Hz (configurable constant, not user-exposed).
Applied to the full resampled signal (before slicing) to avoid filter edge effects at
window boundaries.

```kotlin
fun highPassFilter(samples: FloatArray, sampleRateHz: Int, cutoffHz: Int = 250): FloatArray
```

Implementation: bilinear transform of analogue Butterworth prototype. No external library
needed — ~30 lines of arithmetic.

## Spectral Subtraction

**File:** `inference/AudioPreprocessor.kt`

```kotlin
class SpectralSubtractor {
    fun process(window: FloatArray): FloatArray
}
```

**Adaptive noise profile:**
- Per window, compute RMS.
- If `rms < RMS_QUIET_THRESHOLD` (quiet window) → update noise profile via EMA:
  `profile = α * |FFT(window)|² + (1-α) * profile`, α = 0.1
- profile starts as null; on first quiet window it is initialized to the spectrum directly.
- If profile is null (no quiet window seen yet) → return window unchanged.

**Subtraction:**
- Compute `|X(f)|²` of the current window.
- `|Y(f)|² = max(|X(f)|² − β * profile(f), γ * |X(f)|²)`
  - β = 1.5 (over-subtraction factor), γ = 0.002 (spectral floor)
- Reconstruct: `Y(f) = |Y(f)| * exp(j * angle(X(f)))`
- IFFT → output window.

FFT size: next power of two ≥ window length. Standard Cooley-Tukey FFT implemented in Kotlin
(no Android-specific API needed for unit testing).

## YAMNet Gate

**Files:** `inference/YamNetGate.kt`, `inference/YamNetTfliteGate.kt`

```kotlin
fun interface YamNetGate {
    /** Returns false if the window should be skipped (clearly non-biological). */
    suspend fun isBiological(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean
}
```

**`YamNetTfliteGate`:**
- Loads `yamnet.tflite` from disk (downloaded by ModelManager).
- Input shape: `[1, 15600]` float32, mono PCM at 16 kHz (0.975 s per frame).
- For windows longer than 0.975 s: split into frames, aggregate by max biological score.
- Resamples input internally using existing `Resampler.resample()`.
- Output: `[1, 521]` class probabilities.

**Biological classes** (pass): indices for Bird, Frog, Insect, Animal, Wild animals,
Rodents/rats, and Silence (unknown/ambient with no dominant noise signal).

**Noise classes** (gate): Engine, Motor vehicle, Car, Motorcycle, Truck, Rail transport,
Boat, Aircraft, Mechanical fan, HVAC, Wind, Rain, Thunderstorm, Music, Speech.

**Decision rule:**
```
val bioScore = biologicalClassIndices.maxOf { probabilities[it] }
val topClass = probabilities.indices.maxByOrNull { probabilities[it] }
val isNoiseTop = topClass in noiseClassIndices
skip = bioScore < 0.15 && isNoiseTop
```

Fail-open: any exception or model not loaded → `isBiological = true`.

## YAMNet Model Download

YAMNet is not a detection model — it does not appear in the Settings → Models list.

**Descriptor:** `KnownModels.YamNetV1` with `hidden = true` flag added to `ModelDescriptor`.
`ModelManager` downloads hidden models automatically when any non-hidden model is installed.
If only YAMNet is installed (edge case) the gate is ready but detection models show nothing.

**Source:**
- URL: TF Hub lite-model yamnet/classification/tflite v1 (~3.7 MB)
- Labels: yamnet_class_map.csv (~25 KB, 521 rows: `index,mid,display_name`)
- SHA-256 hash pinned in descriptor.

## Settings Changes

```kotlin
val spectralSubtractionEnabled: Flow<Boolean>  // default: true
val yamNetGateEnabled: Flow<Boolean>            // default: true
```

Keys: `spectral_subtraction_enabled`, `yamnet_gate_enabled`.

**SettingsScreen:** New `SectionCard("Noise reduction")` between "Inference" and
"Regional filter":

```
Noise reduction
  [Switch] Spectral noise reduction
  [Switch] YAMNet biological gate
             ↳ "Requires YAMNet model (3.7 MB, downloaded automatically)"
               shown only when YAMNet model not yet installed
```

## InferenceRunner Changes

Constructor adds two optional parameters:

```kotlin
class InferenceRunner(
    private val model: BioacousticModel,
    private val hopSeconds: Float = 1f,
    private val spectralSubtractor: SpectralSubtractor? = null,
    private val yamNetGate: YamNetGate? = null,
)
```

`run()` changes:
1. After resampling, before slicing: apply `highPassFilter()` to the full signal.
2. Inside the window loop:
   ```kotlin
   var window = rawWindow
   window = spectralSubtractor?.process(window) ?: window
   if (yamNetGate?.isBiological(window, targetRate) == false) continue
   out += model.predict(pcmFloat32 = window, ...)
   ```

## DI Wiring

**`AppModule.kt`** — new providers:
```kotlin
@Provides @Singleton
fun provideSpectralSubtractor(settings: Settings): SpectralSubtractor? // null if disabled

@Provides @Singleton
fun provideYamNetGate(settings: Settings, factory: InterpreterFactory, manager: ModelManager): YamNetGate?
```

`ProductionInferenceJob` passes `spectralSubtractor` and `yamNetGate` to each `InferenceRunner`.

**`SwappableModule` / test module:** inject `null` for both to bypass preprocessing in e2e tests.

## Testing

| Test | Location |
|------|----------|
| `highPassFilter` attenuates below cutoff | `AudioPreprocessorTest.kt` |
| `SpectralSubtractor` reduces RMS on synthetic noise | `AudioPreprocessorTest.kt` |
| Quiet window updates noise profile; noisy window does not | `AudioPreprocessorTest.kt` |
| `YamNetTfliteGate` returns false for noise-top window below bio threshold | `YamNetGateTest.kt` |
| `YamNetTfliteGate` returns true on exception (fail-open) | `YamNetGateTest.kt` |
| `InferenceRunner` skips window when gate returns false | `InferenceRunnerTest.kt` |

## Out of Scope

- RNNoise / DeepFilterNet (neural noise enhancement — different problem)
- Per-species YAMNet confidence weighting in DetectionAggregator
- Exposing HPF cutoff or spectral subtraction parameters to the user
- YAMNet-based detection (as a BioacousticModel, not just a gate)
