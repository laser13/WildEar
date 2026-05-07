# WildEar Audio, Spectrogram, and ML Pipeline Spec

## 1. Goal

WildEar should record raw environmental audio, visualize it as a useful spectrogram, detect potentially biological sound, run species recognition models, and aggregate detections into clean species rows for review.

Important principle:

```text
Always keep the original raw recording.
Apply denoise/filtering only for:
1. playback,
2. spectrogram visualization,
3. optional model input experiments.
```

Do not overwrite or destructively alter the original audio.

---

## 2. Recording defaults

Recommended capture settings:

```text
Sample rate: 48 kHz if available, otherwise 44.1 kHz
Channel: mono
Format: PCM 16-bit or float if needed
Storage: WAV for raw export/debug, compressed copy only if needed later
Audio source: UNPROCESSED if supported, fallback to MIC
Avoid default voice-processing modes for scientific recording
```

Android implementation candidates:

```text
AudioRecord
PCM ring buffer
WAV writer
Foreground service for long recordings
Room database for metadata
WorkManager for background export/upload
```

Avoid using `VOICE_COMMUNICATION` as the default input source, because it may enable speech-oriented processing that can damage wildlife sounds.

---

## 3. Phone microphone constraints

WildEar must assume normal Android phone microphones.

Most phones record at 44.1 or 48 kHz. By Nyquist, this means they cannot capture frequencies above about 22.05 or 24 kHz.

Implication:

```text
WildEar should be realistic:
- good for birds, frogs, many insects, and audible mammals;
- limited for ultrasonic bats and very high-frequency sounds;
- should not claim reliable ultrasonic detection with normal phone microphones.
```

Suggested help text:

```text
Phone microphones cannot capture all wildlife sounds. Ultrasonic species may require an external microphone.
```

---

## 4. Spectrogram requirements

The spectrogram is a key UI element. It should communicate “the app is really listening” and help the user visually inspect animal sounds.

Current issue to investigate: the existing WildEar spectrogram appears visually compressed/flat compared with Merlin. Possible causes:

```text
- poor dB/log scaling;
- too wide a frequency range squeezed into a small view;
- insufficient dynamic range normalization;
- low-frequency wind/traffic rumble dominating the image;
- non-adaptive color/contrast mapping;
- wrong FFT window/hop settings.
```

Use two display modes:

```text
Compact live spectrogram
Detailed review spectrogram
```

---

## 5. Live spectrogram settings

Goal: immediate visual feedback during recording.

Suggested settings:

```text
Sample rate: 48 kHz if available, otherwise 44.1 kHz
FFT window: 1024 or 2048 samples
Hop size: 256–512 samples
Window function: Hann
Magnitude scale: log-magnitude / dB
Default visible frequency range: 0–10 kHz
Optional full range: 0–22 kHz
```

Recommended behavior:

```text
- Render continuously while recording.
- Use adaptive dB contrast.
- Keep visual latency low.
- Do not let low-frequency rumble flatten the image.
- Do not require user interaction during live recording.
```

---

## 6. Review spectrogram settings

Goal: easier visual inspection after recording.

Suggested presets:

```text
Default:          0–10 kHz
Bird-focused:     1–10 kHz
Insect/amphibian: 0–12 kHz or 0–16 kHz
Full phone range: 0–22 kHz
```

Recommended behavior:

```text
- Show playback cursor.
- Allow horizontal scrubbing.
- Allow pinch/zoom if feasible.
- Tap species row → highlight its detected fragments on the spectrogram.
- Tap fragment → jump playback to that time range.
```

---

## 7. Spectrogram dynamic range and contrast

Recommended algorithm:

```text
1. Compute STFT.
2. Convert magnitude to dB.
3. Clamp dynamic range, for example top_db = 70–80 dB.
4. Apply percentile normalization per visible window.
5. Render with a stable but adaptive color mapping.
```

Important:

```text
- Low-frequency rumble should not dominate the spectrogram.
- Bird/insect calls should appear as visible strokes/lines.
- The display should adapt to quiet and loud environments.
- The original audio must remain unchanged.
```

For display only, consider high-pass filtering:

```text
100–150 Hz: mild rumble reduction
200–300 Hz: stronger urban/wind rumble reduction
```

This should be applied only to the display/playback path unless experiments prove it improves model quality.

---

## 8. Audio preprocessing strategy

Do not assume “denoise always helps”. Many noise suppression systems are designed for human speech and may remove animal calls.

MVP recommendation:

```text
- Record raw audio.
- Implement display-only high-pass filtering.
- Implement better spectrogram normalization.
- Do not apply aggressive denoise to model input by default.
```

Later experimental paths:

```text
- Android NoiseSuppressor
- WebRTC Noise Suppression, mild mode first
- RNNoise
- Custom high-pass / band-pass filters
- Spectral gating experiments
```

Each preprocessing option must be evaluated by detection quality, not just by whether the audio sounds nicer to humans.

---

## 9. Wind, traffic, and urban noise

Do not implement this as simple “subtract car/wind sample from audio”. Real field noise overlaps with biological signal in time and frequency.

Better approach:

```text
1. Keep raw audio.
2. Use high-pass/band-pass filtering for visualization.
3. Use adaptive spectrogram contrast.
4. Use a biological gate to avoid running expensive species models on irrelevant audio.
5. Use optional denoise only for playback or controlled experiments.
```

Typical handling:

```text
Wind rumble:
- often low-frequency and broad;
- high-pass can help visually;
- physical wind protection is better than software.

Traffic:
- often low/mid-frequency continuous noise;
- high-pass and adaptive scaling can help;
- avoid over-filtering because some animal sounds may overlap.

Speech/music:
- should usually be detected as non-target background;
- can be used to reduce confidence or skip model inference.
```

---

## 10. Biological gate / YAMNet

YAMNet is a reasonable first-stage general audio classifier. Use it as a soft biological gate, not as a perfect wildlife detector.

Purpose:

```text
YAMNet Biological Gate
→ decide whether a short audio window contains potentially biological sound
→ if yes, send to species recognizer
→ if no, skip or mark as background
```

Do not treat the gate as hard truth. YAMNet may confuse squeaks, whistles, brakes, voices, and animal sounds.

Suggested gate logic:

```text
Input window: model-native window, usually around 0.96s
Sliding aggregation: 3–10 seconds
Pass if:
- bird / animal / insect / frog-like classes exceed threshold
- OR species model independently gives high confidence
Fail or down-rank if:
- traffic / engine / wind / speech / music dominates
```

Soft scoring example:

```text
biological_score = weighted sum of relevant YAMNet classes
background_score = traffic + wind + speech + music + machinery
pass_to_species_model = biological_score > threshold OR precheck_species_confidence > high_threshold
```

Keep this invisible in the normal UI. Show only in debug/details:

```text
YAMNet gate: passed
Dominant background: traffic
```

---

## 11. Species recognition pipeline

Recommended MVP pipeline:

```text
AudioRecord
→ raw PCM ring buffer
→ WAV storage
→ spectrogram renderer
→ YAMNet biological gate
→ species model(s)
→ fragment aggregation
→ review UI
```

For birds, BirdNET / BirdNET-Lite is a strong candidate. For non-bird taxa, investigate separate models and datasets rather than assuming BirdNET is enough.

Potential model runtime options:

```text
TensorFlow Lite / LiteRT
TFLite Task Library AudioClassifier
ONNX Runtime Mobile if needed
```

---

## 12. Fragment aggregation

Window-level detections should be merged into species-level rows.

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

Main UI should show only:

```text
Common name
Scientific name
confidence percentage
×N fragment count
```

Example:

```text
Eurasian Hoopoe                         80%  ×8
Upupa epops
```

Details can show:

```text
BirdNET: 80%
Perch: 52%
YAMNet gate: passed
Fragments: 8
Time ranges: 00:12–00:15, 00:31–00:34...
```

---

## 13. Denoise modes

Rename the user-facing toggle from `Denoise` to:

```text
Playback denoise
```

Tooltip:

```text
Makes listening easier. Original recording is kept.
```

Modes:

```text
Off
Playback only
Experimental model input
```

Default:

```text
Off for model input
Optional for playback
```

---

## 14. Benchmark plan

Before enabling preprocessing by default, run an A/B benchmark.

Compare these paths:

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

Decision rule:

```text
Do not enable preprocessing for model input unless it improves detection quality on real field recordings.
```

---

## 15. Implementation candidates

UI:

```text
Jetpack Compose
Material 3
Navigation Compose
Room
DataStore
WorkManager
```

Audio capture:

```text
AudioRecord
Foreground service
PCM ring buffer
WAV writer
```

DSP / spectrogram:

```text
Custom Kotlin FFT pipeline
TarsosDSP
JTransforms
KissFFT
Compose Canvas rendering
```

ML:

```text
TensorFlow Lite / LiteRT
TFLite Task Library AudioClassifier
ONNX Runtime Mobile if needed
```

Audio preprocessing experiments:

```text
Android NoiseSuppressor
WebRTC Audio Processing Module
RNNoise
Custom filters
```

---

## 16. MVP task list

Spectrogram:

```text
- Implement log/dB scaling.
- Add adaptive percentile contrast.
- Default visible frequency range to 0–10 kHz.
- Add optional full-range view up to 22 kHz.
- Add high-pass display filter preset.
- Add playback cursor.
- Highlight detection fragments on tap.
- Store raw audio unchanged.
```

Audio/ML:

```text
- Use raw/unprocessed recording path by default.
- Add YAMNet biological gate as a soft filter.
- Aggregate detections into species rows with confidence and ×N.
- Store per-fragment time ranges.
- Benchmark preprocessing options before enabling denoise for model input.
```
