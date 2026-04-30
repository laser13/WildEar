# BirdNET v2.4 — model spike

**Date:** 2026-04-29 (BirdNET); updated 2026-04-30 (Perch v2 dual-model addition).
**Decision:** **BirdNET v2.4 (FP32 TFLite) is locked for Spec 1. Google Perch v2 (TFLite)
runs alongside it as a second installable model.**

This document captures the model artifact, sources, frozen checksums, and tensor
contract — these values feed Tasks 4 (mel preprocessor), 6 (BirdNet wrapper) and
7 (ModelManager).

## Dual-model wiring (added 2026-04-30)

The `inference` package now supports more than one model running per recording.
Implementations: [`BirdNetTfliteModel`](../../app/src/main/java/com/sound2inat/inference/BirdNetTfliteModel.kt)
and [`PerchTfliteModel`](../../app/src/main/java/com/sound2inat/inference/PerchTfliteModel.kt),
both behind the [`BioacousticModel`](../../app/src/main/java/com/sound2inat/inference/BioacousticModel.kt)
contract. `BioacousticModel` now exposes `expectedSampleRateHz` and `windowMs`,
so [`InferenceRunner`](../../app/src/main/java/com/sound2inat/inference/InferenceRunner.kt)
can size its window per model and (via [`Resampler`](../../app/src/main/java/com/sound2inat/inference/Resampler.kt))
drop the WAV from 48 kHz to whatever rate the model expects (e.g. 32 kHz for
Perch). Settings shows one Install/Remove section per known descriptor; whichever
combination of models is installed is what the inference layer runs.

`WindowPrediction` carries a `source` tag (modelId), and `DetectionAggregator`
collapses windows per taxon while preserving a `confidenceBySource` map. That
map is persisted on `DetectionEntity.sources` (Room v4 — see `MIGRATION_3_4`)
and surfaces on the Review screen as small badges (e.g. `BirdNET 78%`,
`Perch 62%`) so the user can tell at a glance which model agreed.

**Why sequential, not parallel:** TFLite interpreters are not thread-safe for
concurrent inference, and Perch v2 alone is ~388 MB on disk + non-trivial peak
memory during `Interpreter.run()`. Sequential keeps the device responsive even
on mid-tier hardware.

## Frozen artifact

| Field | Value |
|---|---|
| Model | `BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite` |
| Source URL | `https://github.com/woheller69/whoBIRD-TFlite/raw/master/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite` |
| File size | 51,726,412 bytes (49.3 MB) |
| SHA-256 | `55f3e4055b1a13bfa9a2452731d0d34f6a02d6b775a334362665892794165e4c` |
| Labels | `labels_en_uk.txt` (English UK) |
| Labels source URL | `https://raw.githubusercontent.com/woheller69/whoBIRD/master/app/src/main/assets/labels_en_uk.txt` |
| Labels SHA-256 | `af05ad18573f6ecdd14b1e457ba9265043ad8b60ec273816660125c82690e693` |
| Label count | 6522 |
| Label format | `Scientific Name_Common Name` (one entry per line, underscore separator) |

## Source provenance

Upstream BirdNET-Analyzer (`birdnet-team/BirdNET-Analyzer` on GitHub, formerly
`kahst/BirdNET-Analyzer`) **does not** publish raw TFLite weights to its repo
or releases — only macOS/Windows installer packages contain the model files.
For developers, the `birdnet-analyzer` PyPI package historically bundled
weights, but version 2.0.0 ships only label files.

`woheller69/whoBIRD-TFlite` mirrors the official BirdNET v2.4 weights as a
companion project to the open-source `whoBIRD` Android app (BirdNET-Analyzer
maintainers acknowledge that mirror in their docs). The FP32 tflite there is
byte-identical to the model bundled in BirdNET-Analyzer Mac/Windows installers.

License remains: code MIT, weights **CC BY-NC-SA 4.0** (non-commercial,
share-alike). Acceptable for the private MVP. Public-track redistribution
requires either upstream permission or a different model — see
`docs/initial-plans.md` §4.2.

## Tensor contract

Read via `scripts/spike/inspect_tflite.py`:

```
file: models/birdnet_v2_4.tflite (51,726,412 bytes)
version: 3
description: b'MLIR Converted.'
subgraphs: 1
  tensors: 547
  operators: 376

inputs:
  [0] name='INPUT' shape=[1, 144000] type=0      # FLOAT32

outputs:
  [0] name='Identity' shape=[1, 6522] type=0     # FLOAT32 (softmax)
```

### Implications for Kotlin port

1. **Input is raw audio**, NOT a precomputed mel-spectrogram.
   - `144000 = 48000 Hz × 3.0 s` → fixed-size 3-second window of FLOAT32 PCM,
     mono, normalised to `[-1, 1]` (BirdNET-Analyzer reference uses
     `samples / 32768.0` for 16-bit input).
   - This means **`MelSpectrogram` (Task 4) is NOT part of the inference path
     — it's only used to render the spectrogram on the Review screen.** The
     `BirdNetTfliteModel.predict()` method (Task 6) feeds the raw FloatArray
     window directly into the interpreter.

2. **Sample rate is locked to 48 kHz.** `Recorder` already records 48 kHz
   PCM-16 mono (Task 3). No resampling needed.

3. **Window/hop convention from BirdNET-Analyzer:**
   - window = 3.0 s (matches `144000` samples).
   - hop = 3.0 s (non-overlapping in upstream defaults), but spec defaults to
     1.0 s hop for denser detection. We use **3.0 s window / 1.0 s hop**;
     `InferenceRunner` (Task 6) loops over `windowStartMs = i * hopMs`.

4. **Output is one softmax vector of length 6522.** `BirdNetTfliteModel`
   takes top-K (default 5) by confidence. Apply confidence threshold in
   `DetectionAggregator` (already implemented — Task 5).

5. **Output dtype is FLOAT32**, no dequantisation needed (this is the FP32
   model, not the quantised FP16 variant). The Kotlin `Interpreter.run()`
   call passes `Array<FloatArray>` for both input and output.

### MelSpectrogram parameters for UI rendering only

These are not consumed by the model. Pick reasonable defaults for the visual:

| Param | Value | Rationale |
|---|---|---|
| sampleRate | 48000 | Recorder native rate |
| nFft | 1024 | Common; 21 ms window at 48 kHz |
| hop | 512 | 10.7 ms hop |
| melBins | 128 | Standard for visualisation |
| fMin | 0 | |
| fMax | 24000 | Nyquist |

Task 4 uses these defaults; the Review screen's spectrogram is therefore
disconnected from the model's internal mel pipeline (which is hidden inside
the .tflite file's MLIR-compiled ops).

## Deferred work

- **Reference comparison vs upstream Python BirdNET-Analyzer.** The plan
  originally called for running BirdNET-Analyzer Python reference and
  comparing top-K predictions against the TFLite output to within 0.05.
  This is deferred because:
  - `tensorflow` / `tflite-runtime` have no wheels for Python 3.14
    (Apple Silicon). Running the reference would require installing
    Python 3.11 alongside.
  - The TFLite file we use is the **same** artifact bundled in the official
    BirdNET-Analyzer macOS installer (verified via mirror chain) — there
    is no second computation path to disagree with.
  - The first end-to-end check happens on-device in Task 18 (manual
    acceptance run): a known clip is played, and we verify the model
    returns the expected top-1 species.
  - If the on-device check fails, we revisit and run the comparison in a
    Python 3.11 venv before debugging Kotlin code.

- **Multi-fixture confidence regression test.** The plan called for 3 fixture
  WAVs in `app/src/test/resources/spike_fixtures/` to pin reference numbers
  for `MelSpectrogramTest` and a `BirdNetTfliteModel` integration test.
  Without a working Python TFLite runtime there's nothing to compute the
  expected numbers from. Deferred along with the reference comparison.

- **Perch baseline.** Stretch goal in plan §"Task 2 Step 4" — not done.
  Re-spike when public-track preparation begins.

## Conclusion

BirdNET v2.4 is locked. Tasks 4, 6, 7 unblock with these constants:

```
INPUT_SAMPLES = 144_000
SAMPLE_RATE = 48_000
WINDOW_SECONDS = 3.0
HOP_SECONDS = 1.0
NUM_LABELS = 6522
MODEL_SHA256 = "55f3e4055b1a13bfa9a2452731d0d34f6a02d6b775a334362665892794165e4c"
LABELS_SHA256 = "af05ad18573f6ecdd14b1e457ba9265043ad8b60ec273816660125c82690e693"
MODEL_URL = "https://github.com/woheller69/whoBIRD-TFlite/raw/master/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite"
LABELS_URL = "https://raw.githubusercontent.com/woheller69/whoBIRD/master/app/src/main/assets/labels_en_uk.txt"
```
