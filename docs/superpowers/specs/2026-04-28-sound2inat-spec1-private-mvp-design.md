# Spec 1 — Private MVP design

**Date:** 2026-04-28
**Project:** sound2inat
**Status:** approved (brainstorming output, ready for plan)
**Seed document:** [`docs/initial-plans.md`](../../initial-plans.md)
**Target track:** Plan A (private) with architectural set-up for future Plan B (public)

---

## 1. Goal

Deliver a sideloaded Android APK that:

1. Records bird/wildlife sound in the field with GPS and timestamp.
2. Classifies the recording **post-hoc** (after Stop) using BirdNET TFLite on-device.
3. Lets the user review detected species on a screen with audio playback, waveform, and a coloured spectrogram with per-detection overlays.
4. Saves the result locally as a draft.

Upload to iNaturalist is **out of scope for Spec 1** — that is Spec 2 (`Spec 2: iNat OAuth + upload`, separate document later).

The primary user is the project owner (single user, single device). Distribution is `adb install`. No Play Store, no F-Droid, no public release from this spec.

## 2. Decisions taken in brainstorming

These decisions are inputs to the implementation plan; do not re-litigate without explicit cause.

| # | Decision | Reason |
|---|---|---|
| D1 | Track B with private prototype first (Plan A first, but architecture compatible with future public version) | Avoids rewrite later; matches the recommended path in `initial-plans.md` §6. |
| D2 | Spec 1 covers A1 + A2 + post-hoc inference + A4 review + local drafts. **No upload, no live inference.** | β split from brainstorming. Keeps Spec 1 testable end-to-end without external dependencies (no iNat OAuth registration yet). |
| D3 | Inference is **post-hoc** (after Stop), not live during recording | Live UI introduces foreground-service complexity, MIUI quirks, and concurrency that is not necessary for human-review-then-upload workflow. Live becomes a later spec; the `BioacousticModel` interface stays the same, so live is an additive change. |
| D4 | First model is **BirdNET / TFLite** | Mature for birds, public TFLite weights, existing Android references (`whoBIRD`). Perch is research-track and adds risk in Spec 1. License (CC BY-NC-SA 4.0 weights) is acceptable for private APK; recorded in `docs/LICENSE_NOTES.md`. |
| D5 | Model is **downloaded after install**, not bundled | Smaller APK; matches the public-track `ModelManager` design from `initial-plans.md` §4.4.2; replaceable URL/license without re-releasing the app. |
| D6 | Home + Drafts list + Review + Settings (UX variant 1) | Drafts list is needed in Spec 2 anyway; persistence implements offline-first. |
| D7 | Review screen includes **waveform + mel-spectrogram + per-detection overlays** (level 2) | Reuses the spectrogram preprocessor that already runs for the model; the overlay is the analytic value of the screen. |
| D8 | Native Kotlin + Jetpack Compose | Best Android control, simplest path for Hilt/Room/Compose stack. |
| D9 | Hilt for DI; Room for storage; DataStore for settings | Standard Android stack with the lowest boilerplate per outcome. |
| D10 | `minSdk = 28`, `targetSdk = 35`, `compileSdk = 35` | Covers ≥95% of devices, including the test device (Poco X3, Android 12). |
| D11 | TDD where there is algorithmic logic; pragmatic post-hoc tests for UI | Avoids over-investing in Compose tests while keeping inference, recorder, and aggregator unit-tested. |

## 3. Module structure

Logical modules (Java packages in a single `:app` Gradle module — no multi-module split in Spec 1):

```
com.sound2inat.app          UI (Compose), ViewModels, navigation, Hilt graph
com.sound2inat.recorder     AudioRecord wrapper, WAV writer
com.sound2inat.inference    BioacousticModel interface
                            BirdNetTfliteModel implementation
                            mel-spectrogram preprocessor
                            DetectionAggregator
com.sound2inat.modelmanager Download, SHA-256 verify, atomic install, state
com.sound2inat.storage      Room (DAOs, DB), WAV file ops
com.sound2inat.location     FusedLocationProvider wrapper
```

**Boundary rule.** Nothing outside `com.sound2inat.app` may import Compose, `Activity`, or `Application`. Where access to `Context` is required, take it through narrow constructor parameters (e.g., `filesDir: File`). This makes future extraction of `core/*` into separate Gradle modules a mechanical refactor.

## 4. End-to-end data flow (Spec 1)

```
[Home] tap Record
    ↓
LocationProvider.getCurrentLocation(timeoutMs = 15_000, fallback = lastKnown)
    ↓
Recorder.start():
    AudioRecord (PCM 16-bit, 48 kHz, mono) → WAV writer → filesDir/recordings/<uuid>.wav
    ↓
[Recording] elapsed time, RMS level meter, GPS status, Stop button
    ↓
Recorder.stop(): close WAV, return RecordingResult
    ↓
DraftRepository.create(RecordingResult) → DraftStatus.PENDING_INFERENCE
    ↓
[Review] opens immediately, shows progress
    ↓
InferenceRunner (in-process coroutine on review screen):
    BioacousticModel.predict() iterating 3-second windows with 1-second hop
    ↓
DetectionAggregator: group by taxon → max confidence, count, first/last seen
    ↓
DraftRepository.attachDetections(draftId, detections) → DraftStatus.PENDING_REVIEW
    ↓
[Review] renders waveform + spectrogram + species list with overlays
    ↓
User selects taxa, taps Save
    ↓
DraftRepository.markReviewed(draftId, selectedTaxa) → DraftStatus.REVIEWED
    ↓
[Home] draft visible at top of list
```

**Key invariants.**

- Recorder and inference are decoupled: recorder writes a file, inference reads a file. Live inference (future spec) replaces the file source with a rolling buffer; the model and aggregator are unchanged.
- Inference runs **on the foreground Review screen** in Spec 1, not in a service. The review screen displays progress (`Analyzing… X%`). The user is expected to keep the screen open during analysis (≤20 s on a 30 s recording). Backgrounding cancels inference; the draft remains `PENDING_INFERENCE` and can be retried.
- A wake-lock is held while inference runs to prevent the device sleeping during the analysis countdown.
- A draft is never lost mid-flow: row + WAV are written **before** inference starts. If anything crashes, the draft is recoverable on next launch.

## 5. Data model

### 5.1 Files on disk

- `filesDir/recordings/<uuid>.wav` — raw 16-bit mono 48 kHz PCM, ~5.5 MB/min.
- `filesDir/spectrograms/<draftId>.png` — pre-rendered mel-spectrogram cache. Path is derived from `draftId` (no DB column); regenerated lazily if missing; deleted together with the draft row by the repository on `delete`.
- `filesDir/models/birdnet_v<X>.tflite` — installed model. Sibling `metadata.json` with `{model_id, version, sha256, source_url, license, species_count, downloaded_at}`.
- `filesDir/models/<id>.partial` — temporary download file; renamed atomically after SHA-256 verification.

WAV is uncompressed in Spec 1. The 5/10-minute recording cap (see §7) keeps a single recording under ~55 MB.

### 5.2 Room schema (version 1)

```kotlin
@Entity(tableName = "drafts")
data class DraftObservation(
    @PrimaryKey val id: String,                  // UUID generated on Recorder.start
    val audioPath: String,                       // absolute path inside filesDir
    val recordedAtUtcMs: Long,
    val durationMs: Long,
    val latitude: Double?,                       // null if no GPS fix within 15 s
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
    val status: DraftStatus,
    val modelId: String?,                        // null until inference runs
    val modelVersion: String?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

enum class DraftStatus { PENDING_INFERENCE, PENDING_REVIEW, REVIEWED }

@Entity(
    tableName = "detections",
    foreignKeys = [ForeignKey(
        entity = DraftObservation::class,
        parentColumns = ["id"],
        childColumns = ["draftId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("draftId")],
)
data class Detection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val draftId: String,
    val taxonScientificName: String,             // primary key for future iNat resolution
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,                       // relative to recording start
    val lastSeenMs: Long,
    val isSelectedByUser: Boolean,
)
```

Schema export is enabled in Gradle so future migrations have a baseline.

### 5.3 Settings (DataStore)

| Key | Default | Notes |
|---|---|---|
| `min_confidence_display` | `0.25` | Filters which detections are shown on Review by default. |
| `top_k` | `5` | Per-window top-K kept by the model wrapper. |
| `inference_window_seconds` | `3.0` | BirdNET-native window. |
| `inference_hop_seconds` | `1.0` | |
| `model_id` | `birdnet_v2_4` | Active model id; only one option in Spec 1. |
| `region_hint_lat` / `region_hint_lon` | `null` | Optional override for BirdNET location filter. If null, draft's GPS is used. |

## 6. UI

Single-Activity, Compose Navigation. Four screens: **Home**, **Recording**, **Review**, **Settings**.

### 6.1 Home

- Large `Record` button (disabled if model not installed, with hint pointing to Settings).
- Drafts list, latest first, showing date, duration, top-1 species, status badge.
- Tapping a draft opens Review (regardless of status).
- Settings cog in the top bar.

### 6.2 Recording

- Large timer.
- Live RMS level meter (drawn from the most recent `AudioRecord` buffer).
- GPS status: `Acquiring…` → coordinates with accuracy. After 15 s without fix: warning banner; recording continues.
- Stop button transitions to Review immediately.
- Cancel asks for confirmation, deletes the partial WAV, returns Home.

### 6.3 Review

- Header: timestamp, GPS coordinates (or "no location"), Delete button in app bar.
- Audio player (play/pause, seek bar).
- Waveform stack on top, **mel-spectrogram** below, both share the same time axis. Single play-cursor moves over both.
- **Detection overlays** drawn on the spectrogram: one coloured rectangle per detected window per species. Tap on a rectangle → highlights that species in the list and seeks the player to the start of the window. Tap on a species → all its rectangles flash.
- Species list, sorted by `maxConfidence` descending: scientific name, common name, max conf, window count, first–last seen, checkbox bound to `isSelectedByUser`.
- Save button: persists the current selection, sets status to `REVIEWED`, returns Home.
- While inference is running, the species list area is replaced by a progress bar and `Analyzing… X%`. Cancel inference is allowed; cancelling leaves status `PENDING_INFERENCE`.

### 6.4 Settings

- Model section: active model name + version, install state, file size on disk, license string, `Reinstall` and `Remove` buttons. If not installed: a single `Install model` CTA showing the license before download.
- Inference section: `Top K`, `Min display confidence` sliders.
- About: app version + open-source notices.

### 6.5 Permissions

`RECORD_AUDIO` and `ACCESS_FINE_LOCATION` are mandatory. First Record tap opens an in-app explanation bottom sheet, then the system dialog. If denied, the bottom sheet returns with deep-link instructions to system Settings. `INTERNET` is declared in the manifest for model download. `POST_NOTIFICATIONS` is **not** required in Spec 1 (no foreground service, no app notifications) and is **not** declared.

UI strings: **English only** in Spec 1. All strings live in `strings.xml`; no hardcoded text in Composables.

## 7. Recording, inference, and rendering

### 7.1 Recording

- `AudioRecord`, source `MIC`, format `ENCODING_PCM_16BIT`, channel `MONO`, sample rate **48 000 Hz** (BirdNET expects resampling to 48 kHz; recording natively avoids a resampler in the inference path).
- Buffer size: `max(AudioRecord.getMinBufferSize(...) * 2, 4096)` frames. Reads in a dedicated thread, writes WAV via a `BufferedOutputStream`. WAV header is patched on close with the final data size.
- Duration limits: **soft 5 min** (UI warning), **hard 10 min** (auto-stop with toast).
- On `AudioRecord.read()` error (permission revoked mid-recording, hardware glitch): stop recording, mark draft as `PENDING_INFERENCE` with whatever audio was captured, surface a snackbar.

### 7.2 Inference

- Mel-spectrogram preprocessor: 48 kHz → STFT (`n_fft = 1024`, `hop = 512`), **mel** filterbank parameters per BirdNET-Analyzer reference. Implementation in Kotlin using the JTransforms library for FFT. Output tensor shape is whatever BirdNET v2.4 TFLite expects — **the spike (§8) confirms exact parameters before implementation**.
- `BirdNetTfliteModel`: loads `birdnet_v2_4.tflite` via `MappedByteBuffer`, runs the interpreter on each preprocessed window. Returns top-K (taxon, confidence) per window.
- Window/hop: `3 s / 1 s` defaults (configurable via DataStore).
- `DetectionAggregator`: collapses per-window outputs into per-species summaries, applying `min_confidence_display` for what reaches the UI but storing all detections above an internal threshold (`0.10`) so that toggling thresholds in the UI does not require re-inference.
- Total target: ≤20 s on a 30 s recording on Poco X3.

### 7.3 Spectrogram rendering

- Reuses the same mel-spectrogram tensor that fed the model (no second preprocessor).
- Down-sampled along the time axis to a fixed pixel width (target 2048 px); along the frequency axis to mel-bin count.
- Coloured with a perceptually uniform colormap (Viridis, public-domain LUT). Stored as PNG cache; regenerated lazily on first Review open if missing.
- Overlays drawn on top via Compose `Canvas`, rectangles whose width corresponds to a detected window and whose vertical extent spans the full mel-bin range, colour selected from a small per-species palette assigned in render order.

## 8. Model spike (first task in Spec 1 implementation)

Goal: confirm the model choice on the user's target acoustic profile **before** writing the Android model wrapper.

Tasks (run on laptop, not on Android):

1. Pick 5–10 source recordings (Xeno-Canto Cyprus subset + a couple of mixed-region samples).
2. Run BirdNET-Analyzer (Python reference) to obtain ground-truth predictions.
3. Load the same `.tflite` artifact in Python via `tflite-runtime`, run inference on the same windows, compare top-1 confidence and top-5 set overlap. Acceptance: top-1 confidence within 0.05 of reference; top-5 set ≥80% overlap. Failures here mean Android implementation will be wrong, so we re-spike before continuing.
4. (Stretch) Run Perch on the same files to record a baseline for the future public-track decision.
5. Write `docs/private/MODEL_SPIKE.md`: chosen model id and SHA-256, parameters (sample rate, window, hop, FFT, mel bins), per-file top-1 results, decision and rationale.

Only after this spike is the BirdNET preprocessor and TFLite wrapper implemented on Android.

## 9. Build, dependencies, CI

- Single Gradle module `:app`. Kotlin 2.x, AGP current stable, Compose BoM current stable.
- Dependencies: AndroidX Compose, Hilt, Room, DataStore, Coroutines, OkHttp (model download), JTransforms (FFT), TensorFlow Lite Android, Material3, FusedLocation.
- `minifyEnabled = false` in release for Spec 1 (Proguard/R8 deferred to track-B prep).
- Detekt + ktlint formatting; Android Lint at `warningsAsErrors = false`.
- GitHub Actions workflow on every push to any branch: `lint`, `detekt`, `testDebugUnitTest`, `assembleDebug`. No instrumented tests in CI; they run locally on the test device.
- `versionName = "0.1.0"`, `versionCode = 010000`.

## 10. Tests

| Layer | Type | Coverage |
|---|---|---|
| `inference` mel-spectrogram preprocessor | JVM unit | deterministic synthetic inputs (sine sweeps) → expected mel bins |
| `inference` `DetectionAggregator` | JVM unit | synthetic per-window detections → expected aggregates |
| `inference` `BirdNetTfliteModel` | JVM unit with mocked `Interpreter` | tensor shape contract, top-K extraction |
| `recorder` WAV writer | JVM unit | header bytes, sample alignment, length |
| `modelmanager` | JVM unit with `MockWebServer` | states, checksum mismatch, atomic rename, retry |
| `storage` DAOs | Room in-memory | CRUD, FK cascade on draft delete |
| `app` ViewModels | JVM unit with fakes | review state machine, recording timer, permissions states |
| End-to-end | one instrumented test | record (mock recorder feeding a fixture WAV) → inference (predetermined `Detection`s by injecting a fake model) → save → re-open from Home |
| Compose screenshot | optional Roborazzi | Home, Review — added if it does not blow up CI; **not** a Spec 1 acceptance gate |

TDD discipline: failing unit test first for any code in `inference`, `recorder`, `modelmanager`, `storage`. Compose code may be written first, instrumented test added afterwards.

## 11. Acceptance criteria

A manual run on Poco X3 must complete the following without fail before Spec 1 is considered done:

1. Install debug APK via `adb install`.
2. Grant microphone and location permissions on first launch.
3. Open Settings → tap `Install model` → see license string → confirm download → status becomes `Installed`.
4. Tap `Record` → Recording screen shows timer, level meter, and a GPS fix (or `No GPS` warning after 15 s).
5. Play a known bird recording on a separate speaker → tap `Stop`.
6. Review screen opens immediately. Within ≤20 s for a 30 s clip, the species list is populated.
7. Waveform and mel-spectrogram are rendered; play-cursor advances on both during playback. Detection overlays are visible. Tapping an overlay highlights the species in the list and seeks to that time. Tapping a species flashes its overlays.
8. Toggle ≥1 species checkbox → tap `Save` → return to Home with the draft at the top, `REVIEWED` badge, top-1 species shown.
9. Re-open the draft → the saved selection is intact.
10. Delete the draft from Review → confirm → row is gone from Home and `<uuid>.wav` is absent in `filesDir/recordings/`.
11. Force-stop and relaunch the app → all drafts and statuses persist.
12. Disable Wi-Fi and mobile data → record + analyze + save flow works end-to-end (model is local).

A short post-run report is written to `docs/private/MVP_REPORT.md` capturing inference time on Poco X3, debug APK size, and peak native heap during analysis.

## 12. Risks

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| BirdNET TFLite on Android diverges from the Python reference | medium | medium | Spike (§8) compares the two **before** Android implementation; tolerance defined; rerun spike on failure. |
| MIUI kills the app while inference runs | medium | low | Inference runs on a foreground UI screen with a wake-lock; user keeps screen on during the ≤20 s wait. |
| GPS unavailable indoors during testing | high | low | App stores draft without location; acceptance scenario covers the `null` case explicitly. |
| Spectrogram rendering stalls on long recordings | low | medium | Time-axis downsample to fixed pixel width (2048 px); cache PNG. |
| TFLite native libraries inflate APK by 8–15 MB | confirmed | low | Acceptable for private MVP; APK split deferred. |
| Permissions revoked mid-recording | low | medium | `AudioRecord.read()` failure stops recorder cleanly; surfaced in UI. |
| Compose UI implementation effort under-estimated | medium | schedule slip | Each screen is a separate plan task with explicit acceptance; UI tests are not on the critical path. |

## 13. Out of scope (explicit non-goals for Spec 1)

- Live inference during recording (separate spec later).
- iNaturalist OAuth registration.
- iNaturalist upload, taxon resolution, upload queue, retry logic.
- Privacy policy, full license audit document, public-track legal review (only `docs/LICENSE_NOTES.md` is written here).
- Perch model integration (only laptop spike — stretch).
- Foreground service for recording.
- BirdNET location/date filtering through species lists (only stretch inside the spike; not a UI feature in Spec 1).
- Audio editing (trim, denoise, clip).
- Localization beyond English.
- App icon design.
- R8/Proguard minification, APK split.
- Crash reporting / analytics.
- Encrypted Room database (no secrets stored in Spec 1).
- Cloud backup or device-to-device sync.
- Compose screenshot tests as a hard acceptance gate.

## 14. Hand-off to implementation plan

The implementation plan written from this spec must:

1. Begin with the model spike (§8) as task 1, gated on a written `docs/private/MODEL_SPIKE.md`.
2. Group implementation tasks by package (`recorder`, `inference`, `modelmanager`, `storage`, `location`, `app`) so each task is self-contained and reviewable.
3. Treat the four UI screens as four discrete tasks, each with acceptance against §11.
4. Include a final task that runs the §11 acceptance scenario manually on Poco X3 and writes `docs/private/MVP_REPORT.md`.
5. Keep TDD discipline for non-UI code (test first), pragmatic for UI.
