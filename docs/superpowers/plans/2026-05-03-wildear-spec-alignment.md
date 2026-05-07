# WildEar Spec Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the current Android app into alignment with `wildear_specs/*` for a clean wildlife-recording UX, readable spectrograms, raw-first audio capture, and transparent-but-hidden ML details.

**Architecture:** Keep the existing Compose, Room, recorder, inference, and model-manager structure. The work is mostly UI reshaping plus small data-model additions for persisted fragment ranges and gate/debug metadata; model input should stay raw unless a benchmark proves preprocessing helps.

**Tech Stack:** Kotlin, Android Compose Material 3, Hilt, Room, Kotlin coroutines/Flow, TFLite, JUnit/Robolectric.

---

## Current State Audit

| Spec task | Status | Evidence |
| --- | --- | --- |
| 1. Compact species rows | Partial | Review rows are `ListItem`s, but still show confidence labels, `audio fragments`, and source badges inline in `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt:769-784`. |
| 2. Technical details in bottom sheet | Not met | BirdNET/Perch badges are rendered in the main row in `ReviewScreen.kt:781-784`; there is no species-details sheet for source scores/gate/time ranges. |
| 3. Review selection UI | Partial | Bottom CTA exists, but labels are verbose iNaturalist-specific in `ReviewScreen.kt:508-512`; checkbox is trailing/right in `ReviewScreen.kt:792-802`, not left. |
| 4. Wildlife wording | Partial | Live screen still says `Listening for birds...` in `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt:170-172`; review title says `Detected species` in `ReviewScreen.kt:280-283`. |
| 5. Compact recordings list | Partial | Home uses one row/card per draft and shows duration. Images are fine and should stay, but the current row renders multiple species thumbnails in `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt:229-234`; spec asks not to show multiple species thumbnails in the main list. Prefer one compact visual: mini spectrogram or one main species image. |
| 6. Spectrogram scaling | Partial | Review renderer uses global min/max normalization in `app/src/main/java/com/sound2inat/app/ui/review/SpectrogramRenderer.kt`; live renderer uses fixed dB bounds in `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`. Neither has visible-window percentile normalization. |
| 7. Default frequency range | Not met | Review params default to full Nyquist: `fMax = 24_000f` in `app/src/main/java/com/sound2inat/inference/MelParams.kt:15-16`; no UI preset exists. |
| 8. Fragment highlighting | Partial | Overlay rectangles and tap-to-seek exist in `app/src/main/java/com/sound2inat/app/ui/review/DetectionOverlays.kt:56-85`, but exact per-window predictions are not persisted after reopening, and row tap only seeks to first seen. |
| 9. Preserve raw audio | Mostly met | `DefaultRecorder` writes source samples directly before emitting float blocks in `app/src/main/java/com/sound2inat/recorder/Recorder.kt`; denoise preview builds separate artifacts. Need regression tests that prove original path is unchanged. |
| 10. Prefer unprocessed capture | Not met | `AndroidAudioRecordSource` constructs `AudioRecord(MediaRecorder.AudioSource.MIC, ...)` in `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt:21-24`. |
| 11. Rename denoise setting | Not met | UI label is `Denoise` in `ReviewScreen.kt:458-460`; tooltip is close but not the requested wording. |
| 12. YAMNet biological gate | Partial | A soft fail-open `YamNetTfliteGate` exists in `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt:10-16`, but it returns only Boolean and does not expose biological/background scores to details. |
| 13. Aggregate detections | Partial | `DetectionAggregator` groups by scientific name and tracks max confidence/count/time span/source confidences in `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt:32-53`. It does not persist individual fragment time ranges, aggregated confidence, species id, or gate scores. |
| 14. Benchmark preprocessing | Not met | Preprocessing exists (`highPassFilter`, `SpectralSubtractor`, denoise preview), but there is no benchmark harness/report and live/offline inference currently applies high-pass and optional spectral subtraction before model/gate. |

## File Structure

Modify:

- `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`: review layout, compact species row, details bottom sheet, CTA labels, denoise label.
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`: selected details row, fragment ranges, debug/gate fields exposed to UI.
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`: details-row state, fragment selection, model-detail mapping, persistence mapping.
- `app/src/main/java/com/sound2inat/app/ui/review/DetectionOverlays.kt`: render persisted fragment ranges and selected-fragment highlights.
- `app/src/main/java/com/sound2inat/app/ui/review/SpectrogramRenderer.kt`: top-db clamp, percentile normalization, display frequency range.
- `app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt`: preset/range parameters and fragment tap routing.
- `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`: wildlife wording, compact live matches, stop button sizing if needed.
- `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`: default 0-10 kHz display range and adaptive visible-window scaling.
- `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`: compact recording rows, remove species avatar strip, add mini spectrogram placeholder/cache hook.
- `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt`: prefer `MediaRecorder.AudioSource.UNPROCESSED` with `MIC` fallback.
- `app/src/main/java/com/sound2inat/inference/Detection.kt`: add fragment ranges, aggregated confidence, optional gate/debug score types.
- `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`: aggregate fragment ranges and source/gate metadata.
- `app/src/main/java/com/sound2inat/inference/YamNetGate.kt`: return score/debug result instead of Boolean, while keeping fail-open behavior.
- `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`: compute biological and background scores.
- `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`: keep raw input path explicit; make model preprocessing configurable/off by default pending benchmark.
- `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`: same raw-first model path policy; gate may inspect preprocessed copy if needed.
- `app/src/main/java/com/sound2inat/storage/DetectionEntity.kt`: add persisted fragment ranges and optional gate score columns.
- `app/src/main/java/com/sound2inat/storage/Converters.kt`: add fragment-range converter if using a structured list.
- `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`: write/read new detection fields.
- `app/src/main/java/com/sound2inat/storage/Sound2iNatDb.kt`: Room version migration and schema export.

Create:

- `app/src/main/java/com/sound2inat/inference/FragmentRange.kt`: small value type for `startMs`/`endMs`.
- `app/src/main/java/com/sound2inat/inference/YamNetGateResult.kt`: soft gate scores and pass/downrank flag.
- `app/src/main/java/com/sound2inat/app/ui/review/SpeciesDetailsSheet.kt`: bottom-sheet content for source scores, gate status, fragments.
- `app/src/main/java/com/sound2inat/app/ui/review/SpectrogramDisplayRange.kt`: display range presets.
- `docs/private/preprocessing-benchmark.md`: benchmark protocol and current decision log.

Test:

- `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt`
- `app/src/test/java/com/sound2inat/inference/YamNetGateTest.kt`
- `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt`
- `app/src/test/java/com/sound2inat/recorder/AndroidAudioRecordSourceTest.kt` if Robolectric supports constructor inspection; otherwise use a factory seam.
- `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`
- `app/src/test/java/com/sound2inat/app/ui/review/SpectrogramRendererTest.kt`
- `app/src/test/java/com/sound2inat/app/ui/recording/RecordingViewModelTest.kt`
- Existing Compose UI tests or screenshot/manual QA for visual density.

---

### Task 1: Clean Review Species Rows And CTA

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Test: add coverage where practical in `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

- [x] Change section title from `Detected species` to `Detected wildlife`.
- [x] Move `Checkbox` to `leadingContent` before photo/name for non-uploaded rows.
- [x] Main row must show only common name, scientific name, right-aligned `NN% ×N`, and uploaded state if applicable.
- [x] Remove `confidenceLabel`, `audio fragments`, and `SourceBadge` from the main row.
- [x] Change bottom CTA labels exactly to `Select species`, `Submit 1 selected`, and `Submit N selected`; keep disabled when no selection.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.app.ui.review.ReviewViewModelTest`
- [ ] Expected: all selected-state and submit-state tests pass.

### Task 2: Add Species Details Bottom Sheet

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/review/SpeciesDetailsSheet.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`

- [ ] Add `selectedDetailsDetectionId: Long?` to UI state or local screen state.
- [ ] Row tap opens a modal bottom sheet instead of toggling selection; checkbox tap toggles selection.
- [ ] Bottom sheet shows `BirdNET: NN%`, `Perch: NN%`, `YAMNet biological gate: passed/downranked`, `Fragments: N`, and formatted time ranges.
- [ ] Fragment row tap calls `seekTo(startMs)` and highlights that fragment.
- [ ] Keep source labels and gate values out of the main species list.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.app.ui.review.ReviewViewModelTest`
- [ ] Expected: existing selection/playback/highlight tests pass; add a test for details selection if state is ViewModel-owned.

### Task 3: Replace Bird-Specific UI Wording

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/RecordingScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Search: `rg -n "birds|bird suggestions|Detected species|Denoise|audio fragments|Likely|Uncertain|Low confidence|High confidence" app/src/main/java app/src/main/res/values`

- [x] Replace `Listening for birds...` with `Listening for wildlife...`.
- [x] Add `Possible matches` above live rows when matches exist.
- [x] Replace `Detected species` with `Detected wildlife`.
- [x] Keep `BirdNET` and bird-related model names only in details/debug/model manager contexts.
- [x] Run the search command above.
- [x] Expected: normal UI strings no longer imply bird-only behavior.

### Task 4: Make Home Recording List Compact While Keeping Images

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/home/HomeScreen.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/home/HomeViewModelTest.kt`

- [x] Replace `RecordingCard` with a flatter row surface or keep `ListItem` without a wrapping visual card if Material spacing remains compact.
- [x] Keep a visual thumbnail in each row: use a fixed-size mini spectrogram when available, otherwise one main species photo/avatar, otherwise the current status icon fallback.
- [x] Do not show multiple species thumbnails in the main list; move any additional species/photos to review/details if needed.
- [x] Headline should be top species label or `Nothing detected`.
- [x] Supporting text should include date/time, detection count, and status (`Needs review`, `Not submitted`, `Submitted`).
- [x] Trailing text should show duration.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.app.ui.home.HomeViewModelTest`
- [ ] Expected: draft ordering/model readiness tests still pass; add summary formatting tests if moved into pure functions.

### Task 5: Prefer Raw/Unprocessed Audio Capture

**Files:**
- Modify: `app/src/main/java/com/sound2inat/recorder/AndroidAudioRecordSource.kt`
- Test: create `app/src/test/java/com/sound2inat/recorder/AndroidAudioRecordSourceTest.kt` or introduce an `AudioRecordFactory` seam if direct testing is awkward.

- [ ] Try `MediaRecorder.AudioSource.UNPROCESSED` on Android M+.
- [ ] Fall back to `MediaRecorder.AudioSource.MIC` when construction/start fails or when unsupported.
- [ ] Do not use `VOICE_COMMUNICATION` by default.
- [ ] Preserve existing sample rate/channel/bit-depth behavior.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.recorder.*`
- [ ] Expected: recorder tests pass; new fallback test proves MIC is used after an UNPROCESSED failure.

### Task 6: Rename Denoise To Playback Denoise

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Modify: tests only if string assertions exist.

- [x] Change visible label to `Playback denoise`.
- [x] Change tooltip text to `Makes listening easier. Original recording is kept.`
- [x] Keep denoised WAV/PNG as separate preview artifacts; do not overwrite `state.audioPath`.
- [x] Run: `rg -n "\"Denoise\"|About denoise" app/src/main/java`.
- [x] Expected: only implementation names/comments remain; visible UI copy uses `Playback denoise`.

### Task 7: Persist Fragment Ranges And Debug Metadata

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/FragmentRange.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/Detection.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/DetectionEntity.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/Converters.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`
- Modify: `app/src/main/java/com/sound2inat/storage/Sound2iNatDb.kt`
- Test: `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt`
- Test: `app/src/test/java/com/sound2inat/storage/DraftRepositoryTest.kt`

- [ ] Add `FragmentRange(startMs: Long, endMs: Long)`.
- [ ] Add `fragmentRanges: List<FragmentRange>` and `aggregatedConfidence: Float` to `AggregatedDetection`.
- [ ] Store individual window ranges during aggregation; merge or keep ranges sorted by start time.
- [ ] Add Room columns for fragment ranges and optional gate/background scores; export schema.
- [ ] Map persisted fields back into `SpeciesRow`.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.inference.DetectionAggregatorTest --tests com.sound2inat.storage.DraftRepositoryTest`
- [ ] Expected: fragment counts equal range counts, ranges survive repository round trip.

### Task 8: Upgrade YAMNet Gate To Soft Score Result

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/YamNetGateResult.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/YamNetGate.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/YamNetTfliteGate.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`
- Test: `app/src/test/java/com/sound2inat/inference/YamNetGateTest.kt`
- Test: `app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt`

- [ ] Replace Boolean-only gate with a result containing biological score, background score, and recommendation.
- [ ] Keep fail-open behavior when the model is missing or errors.
- [ ] Treat gate as soft: skip/downrank low-bio obvious-noise windows, but let high species confidence override.
- [ ] Persist/display result only in species details/debug, not the main row.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.inference.YamNetGateTest --tests com.sound2inat.inference.LiveInferenceEngineTest`
- [ ] Expected: fail-open and high-confidence override tests pass.

### Task 9: Improve Spectrogram Scaling And Frequency Range

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/review/SpectrogramDisplayRange.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/MelParams.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/SpectrogramRenderer.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/WaveformAndSpectrogram.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/recording/LiveSpectrogramView.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/review/SpectrogramRendererTest.kt`
- Test: `app/src/test/java/com/sound2inat/audio/SpectrogramTest.kt`

- [ ] Change default display range to 0-10 kHz.
- [ ] Add presets: bird-focused 1-10 kHz, insect/amphibian 0-12 or 0-16 kHz, full 0-22/24 kHz.
- [ ] Clamp dynamic range with top-db around 75 dB.
- [ ] Use percentile normalization per rendered/visible matrix so rumble does not flatten quiet calls.
- [ ] Keep live display stable enough to avoid flicker; use a smoothed percentile window if needed.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.app.ui.review.SpectrogramRendererTest --tests com.sound2inat.audio.*`
- [ ] Expected: renderer tests prove quiet events remain non-black/non-white after a loud low-frequency component.

### Task 10: Make Model Input Raw-First And Benchmark Preprocessing

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/InferenceRunner.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/LiveInferenceEngine.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt`
- Create: `docs/private/preprocessing-benchmark.md`
- Test: `app/src/test/java/com/sound2inat/inference/InferenceRunnerTest.kt`
- Test: `app/src/test/java/com/sound2inat/inference/LiveInferenceEngineTest.kt`

- [ ] Ensure stored WAV remains raw and model path defaults to raw/unprocessed audio.
- [ ] Move high-pass/spectral subtraction behind explicit experimental settings or benchmark-only path.
- [ ] Keep display/playback denoise independent of model input.
- [ ] Document benchmark paths and metrics in `docs/private/preprocessing-benchmark.md`.
- [ ] Run: `./gradlew testDebugUnitTest --tests com.sound2inat.inference.InferenceRunnerTest --tests com.sound2inat.inference.LiveInferenceEngineTest`
- [ ] Expected: tests prove default model calls receive raw normalized samples; preprocessing path is opt-in.

### Task 11: End-To-End Verification

**Files:**
- No code files unless fixing issues found during verification.

- [ ] Run: `./gradlew testDebugUnitTest`
- [ ] Run: `./gradlew detekt`
- [ ] Run: `./gradlew assembleDebug`
- [ ] Manual/emulator QA: record a short clip, stop, open review, select one species with left checkbox, open details by tapping row, tap a fragment, toggle playback denoise, verify original audio path remains unchanged.
- [ ] Manual/emulator QA: home list shows compact one-row recordings with one useful image/thumbnail per row and no multi-species avatar strip.
- [ ] Manual/emulator QA: live recording says `Listening for wildlife...`, shows `Possible matches`, and default spectrogram is visually focused on 0-10 kHz.

## Suggested Execution Order

1. UI text/row cleanup: Tasks 1, 3, 6.
2. Home list cleanup: Task 4.
3. Details and fragment persistence: Tasks 2, 7.
4. Audio capture and raw-first model path: Tasks 5, 10.
5. YAMNet soft scores: Task 8.
6. Spectrogram range/scaling: Task 9.
7. Full verification: Task 11.

This order gives visible UX wins first, then adds the data needed for robust details and spectrogram fragment behavior, then tackles the riskier inference/audio changes.
