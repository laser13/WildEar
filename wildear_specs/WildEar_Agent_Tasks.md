# WildEar Agent Task List

## Goal

Improve WildEar Android UI and audio pipeline so the app feels like a clean field-recording tool for wildlife sounds, while keeping useful ML confidence numbers visible.

---

## Phase 1: UI cleanup

### 1. Replace large detection cards with compact rows

Current problem: detection cards are too large and show too much technical information.

Target row:

```text
[photo] Eurasian Hoopoe              80%  ×8
        Upupa epops
```

Acceptance criteria:

- At least 4–6 detections fit on one phone screen.
- Percent confidence is visible on the right.
- Fragment count is shown as `×N`.
- No `audio fragments` text in the main row.
- No `Likely`, `Uncertain`, or `Low confidence` in the main row.
- No per-model scores in the main row.

---

### 2. Move technical model details into bottom sheet

Main row should not show `BirdNET`, `Perch`, `YAMNet`, or other model internals.

Details bottom sheet should show:

```text
BirdNET: 80%
Perch: 52%
YAMNet biological gate: passed
Fragments: 8
Time ranges: 00:12–00:15, 00:31–00:34...
```

Acceptance criteria:

- User can tap a species row to open details.
- Details show model-specific scores.
- Main screen remains clean.

---

### 3. Fix review selection UI

Target review layout:

```text
Review

May 3, 06:10
Lefkoşa · 3:30 · GPS ±24 m

[▶ Play] 0:00 / 3:30       [Playback denoise off]

[spectrogram]

Detected wildlife

☑ Eurasian Hoopoe                         80%  ×8
  Upupa epops

☐ House Sparrow                           62%  ×11
  Passer domesticus

[Submit 1 selected]
```

Acceptance criteria:

- Checkbox is on the left.
- Bottom CTA updates dynamically:
  - `Select species`
  - `Submit 1 selected`
  - `Submit N selected`
- Top-right `Save` is not the primary action.

---

### 4. Replace bird-specific language

WildEar is not only for birds.

Replace:

```text
Listening for birds...
5 birds
Best bird suggestions
```

With:

```text
Listening for wildlife...
5 detections
Best matches
Detected wildlife
Possible matches
```

Acceptance criteria:

- Normal UI does not imply the app is bird-only.
- Bird-specific wording only appears if the current model/mode is explicitly bird-only.

---

### 5. Make recording list compact

Target recordings screen:

```text
WildEar                                      ⚙

Today

[mini spectrogram] Eurasian Hoopoe             1:24
                   May 3, 06:08 · 3 detections
                   Not submitted

[mini spectrogram] Common Woodpigeon           3:30
                   May 3, 06:10 · 5 detections
                   Needs review

Yesterday

[mini spectrogram] Nothing detected            0:29

                                      [🎙 Record]
```

Acceptance criteria:

- One row per recording.
- Do not show multiple species thumbnails in the main list.
- Show recording duration.
- Show review/submission status.
- Record button remains easy to find.

---

## Phase 2: Spectrogram improvements

### 6. Improve spectrogram scaling

Current issue: spectrogram looks compressed/flat compared with Merlin.

Implement:

```text
STFT
log/dB magnitude
clamped dynamic range, top_db around 70–80 dB
adaptive percentile normalization per visible window
```

Acceptance criteria:

- Quiet animal calls are visible.
- Low-frequency rumble does not flatten the whole image.
- Spectrogram remains readable in both quiet and noisy environments.

---

### 7. Use better default frequency range

Default display range:

```text
0–10 kHz
```

Optional presets:

```text
Bird-focused:     1–10 kHz
Insect/amphibian: 0–12 kHz or 0–16 kHz
Full phone range: 0–22 kHz
```

Acceptance criteria:

- Default spectrogram is not visually squeezed by showing too much unused high-frequency range.
- User can switch to full range if needed.

---

### 8. Add fragment highlighting

When user taps a species row, highlight the detected audio fragments on the spectrogram.

Acceptance criteria:

- Species details include fragment time ranges.
- Spectrogram shows highlighted fragment regions.
- Tapping a fragment jumps playback to that point.

---

## Phase 3: Audio capture and preprocessing

### 9. Preserve raw audio

Rule:

```text
Always keep original raw recording.
Do not destructively denoise or filter the stored audio.
```

Acceptance criteria:

- Raw WAV/PCM is stored unchanged.
- Any denoise/filtering is applied only to playback, visualization, or experimental model path.

---

### 10. Prefer raw/unprocessed recording path

Recommended input source:

```text
AudioSource.UNPROCESSED if supported
fallback: AudioSource.MIC
avoid VOICE_COMMUNICATION as default
```

Acceptance criteria:

- App attempts to use unprocessed audio when available.
- App falls back safely on devices without support.
- Voice-oriented effects are not enabled by default for model input.

---

### 11. Rename denoise setting

Replace:

```text
Denoise
```

With:

```text
Playback denoise
```

Tooltip:

```text
Makes listening easier. Original recording is kept.
```

Acceptance criteria:

- User understands that original recording is preserved.
- Denoise does not imply improved ML accuracy unless benchmarked.

---

## Phase 4: Biological gate and ML aggregation

### 12. Add YAMNet Biological Gate

Use YAMNet as a soft first-stage detector.

Purpose:

```text
Detect whether a window contains potentially biological sound.
If yes → send to species recognizer.
If no → skip or down-rank.
```

Suggested logic:

```text
biological_score = weighted sum of bird / animal / insect / frog-like classes
background_score = traffic + wind + speech + music + machinery
pass_to_species_model = biological_score > threshold OR precheck_species_confidence > high_threshold
```

Acceptance criteria:

- Gate is soft, not absolute.
- Species model can still override gate if confidence is high.
- Gate result is visible only in debug/details.

---

### 13. Aggregate detections into species rows

Window-level detections should be merged.

For each species, store:

```text
species_id
common_name
scientific_name
max_confidence
aggregated_confidence
fragment_count
fragment_time_ranges
source_models
biological_gate_score
background_score
```

Main UI shows:

```text
Common name
Scientific name
confidence percentage
×N fragment count
```

Acceptance criteria:

- Duplicate detections are merged into one row per species.
- Fragment count is correct.
- Confidence is stable and understandable.

---

## Phase 5: Benchmark preprocessing

### 14. Compare preprocessing options before enabling them

Benchmark paths:

```text
1. Raw audio
2. Display high-pass only
3. Android NoiseSuppressor
4. WebRTC NS mild
5. WebRTC NS medium/aggressive
6. RNNoise
7. Custom high-pass / band-pass
```

Metrics:

```text
species detection precision
species detection recall
false positives from traffic/wind/speech
confidence stability
number of useful fragments
human playback quality
CPU/battery cost
latency
```

Acceptance criteria:

- No preprocessing is enabled for model input by default until benchmark proves it helps.
- Playback denoise may be optional earlier because it does not affect stored raw audio.

---

## Final acceptance criteria

The app should feel like this:

```text
Review

May 3, 06:10
Lefkoşa · 3:30 · GPS ±24 m

[▶ Play] 0:00 / 3:30       [Playback denoise off]

[clear spectrogram, 0–10 kHz default]

Detected wildlife

☑ Eurasian Hoopoe                         80%  ×8
  Upupa epops

☐ House Sparrow                           62%  ×11
  Passer domesticus

☐ Laughing Dove                           45%  ×3
  Streptopelia senegalensis

[Submit 1 selected]
```

Live recording should feel like this:

```text
× Recording

0:36                         GPS ±21 m

[clear live spectrogram]

Listening for wildlife...

Possible matches

Collared Dove                         43%  ×1
Streptopelia decaocto

[Stop]
```
