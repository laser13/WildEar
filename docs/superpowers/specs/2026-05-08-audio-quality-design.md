# Audio Quality — Source Toggle, Normalization, Post-Recording Denoise

**Date:** 2026-05-08
**Scope:** Three coordinated improvements to audio quality across the recording, review, and iNat-upload pipelines.

---

## Problem

1. **Quiet recordings.** `UNPROCESSED` source skips AGC; recordings are often very quiet — hard to hear in Review and potentially degrading model detection quality.
2. **No post-recording processing.** Normalization and denoising are applied during inference only (per-window); the saved WAV is always raw. Playback, iNat clips, and re-analysis all start from a quiet, noisy file.
3. **Inconsistent pipeline.** Processing applied during inference is invisible to the user and differs from what ends up in iNat.

## Goal

One WAV file per recording. Settings (source, normalize, denoise) are applied **once after recording stops**. All consumers — Review playback, re-analysis, iNat upload — read the same processed file. Live detection is unaffected (reads directly from the microphone).

---

## Design

### 1. Audio Source Toggle

**New setting:** `audioSourceRaw: Flow<Boolean>`, default `true` (UNPROCESSED = current behaviour).

`AndroidAudioRecordSource` receives a `preferRaw: suspend () -> Boolean` constructor parameter. At `start()`, it calls `preferRaw()` and passes the result to `buildAudioRecord()`:
- `true` → tries `UNPROCESSED`, falls back to `MIC` (current logic)
- `false` → goes straight to `MIC` (with Android AGC / noise suppression)

`SwappableModule` wires `AndroidAudioRecordSource(preferRaw = { settings.audioSourceRaw.first() })`.

Settings UI: new toggle in the **Inference** section — **"Raw audio source"** / subtitle *"Skips AGC and noise suppression. May improve model accuracy."* Default: on.

---

### 2. Post-Recording Processing Pipeline

After recording stops and the WAV is finalised on disk, `DefaultRecordingController` triggers a `PostRecordingProcessor` that applies active settings to the WAV file **in-place** (or write → rename for atomicity).

**Processing order (both optional, controlled by Settings):**

1. **Spectral denoising** (`spectralSubtractionEnabled`): iterate WAV samples in fixed windows, apply `SpectralSubtractor.process()` per window, write output. The EMA noise profile trains progressively across the file — same algorithm as live mode, just applied offline.
2. **Peak normalization** (`normalizeAudio`): find peak amplitude across all samples, scale every sample by `Short.MAX_VALUE / peak`. Silence (peak = 0) is passed through unchanged.

**`AudioNormalizer` utility** (new, in `inference` module):
```kotlin
object AudioNormalizer {
    fun normalizeFile(src: File, dst: File)  // reads WAV, peak-normalizes, writes WAV
}
```

**`PostRecordingProcessor`** (new, in `inference` or `app` module):
```kotlin
class PostRecordingProcessor(
    private val settings: Settings,
) {
    suspend fun process(wavFile: File)
    // 1. if spectralSubtractionEnabled: apply SpectralSubtractor window-by-window
    // 2. if normalizeAudio: apply AudioNormalizer
    // Writes to a temp file, replaces original atomically on success.
    // On failure: logs error, leaves original intact.
}
```

`DefaultRecordingController` calls `processor.process(wavFile)` after the WAV is written and before the draft is marked ready for inference.

**Consequence for inference pipeline:** since the WAV is already denoised, `ProductionInferenceJob` no longer creates `SpectralSubtractor` for re-analysis. The `spectralEnabled` check is removed from `ProductionInferenceJob.run()`. `InferenceRunner.usePreprocessing` and `spectralSubtractor` parameters become unused and are removed.

**Live mode:** unaffected. `LiveInferenceEngine` still receives `SpectralSubtractor` from `provideLiveInferenceEngineFactory` when `spectralSubtractionEnabled` is true — that path reads directly from the microphone buffer, not from disk.

---

### 3. Review Playback

Because the WAV on disk is now processed (normalized, denoised), `ReviewViewModel` plays `state.audioPath` directly — no temp file needed. `ensureVisuals()` no longer generates a separate normalized copy.

The spectrogram and waveform visuals are generated from the same processed WAV (already the case — `SpectrogramRenderer` reads `audioPath`).

---

### 4. iNat Upload

`INatSubmitter.cropPerSpecies()` trims from `srcAudio` (the processed WAV on disk) — no changes needed. The uploaded clip is automatically normalized and denoised because the source file already is.

---

## New Settings Keys

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `audioSourceRaw` | Boolean | `true` | `true` = UNPROCESSED, `false` = MIC+AGC |
| `normalizeAudio` | Boolean | `true` | Peak-normalize WAV after recording |

`spectralSubtractionEnabled` (existing) now additionally controls post-recording denoising. Label in Settings UI updated to *"Spectral noise reduction (live + post-recording)"*.

---

## Settings UI Changes

Section: **Inference** (existing)

| Row | Type | New? |
|-----|------|------|
| Raw audio source | Toggle | ✅ new |
| Normalize recorded audio | Toggle | ✅ new |
| Spectral noise reduction | Toggle | existing — update subtitle |
| YAMNet biological gate | Toggle | existing |

---

## Files

| File | Action |
|------|--------|
| `app/src/main/java/com/sound2inat/app/data/Settings.kt` | Add `audioSourceRaw`, `normalizeAudio` keys |
| `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt` | Add `preferRaw` param |
| `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt` | Wire `preferRaw` from Settings |
| `app/src/main/java/com/sound2inat/inference/AudioNormalizer.kt` | New: peak-normalize utility |
| `app/src/main/java/com/sound2inat/inference/PostRecordingProcessor.kt` | New: denoise + normalize pipeline |
| `app/src/main/java/com/sound2inat/app/recording/DefaultRecordingController.kt` | Call `PostRecordingProcessor` after WAV finalised |
| `app/src/main/java/com/sound2inat/inference/InferenceUseCase.kt` | Remove `spectralEnabled` / `SpectralSubtractor` from `ProductionInferenceJob` |
| `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt` | Remove `spectralSubtractor` and `usePreprocessing` params |
| `app/src/main/res/values/strings.xml` | Add strings for two new Settings rows; update spectral subtitle |
| `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt` | Add two new toggle rows |

---

## Non-Goals

- Programmatic live normalization (MIC + AGC covers this)
- Separate raw + processed WAV files on disk (one file only)
- Re-processing old recordings when settings change (applies to new recordings only)
- Loudness normalization (LUFS) — peak normalization is sufficient
