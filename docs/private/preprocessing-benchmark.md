# Preprocessing Benchmark

## Purpose

This document tracks the experimental evaluation of audio preprocessing (high-pass filter +
spectral subtraction) applied **before** model inference. The goal is to determine whether
preprocessing improves or degrades real-world detection quality compared to the default
raw-first path.

WildEar spec alignment (Task 10) moved preprocessing behind an explicit opt-in flag
(`usePreprocessing = false` by default). Before turning preprocessing on in production, run
this benchmark to justify the change with data.

## Background

The inference pipeline (both `InferenceRunner` and `LiveInferenceEngine`) has two modes:

| Mode | `usePreprocessing` | What the model receives |
|------|--------------------|------------------------|
| Raw-first (default) | `false` | Normalized PCM [-1, 1] only |
| Preprocessed (benchmark) | `true` | Normalized → 250 Hz HPF → SpectralSubtractor |

Display/playback denoise (`denoiseFull()` in `AudioPreprocessor.kt`) is **not** affected by
this flag — it is always available in the Review screen regardless of model inference mode.

## How to Enable Preprocessing for Benchmarking

### InferenceRunner (offline / file-based)

```kotlin
val runner = InferenceRunner(
    model = model,
    hopSeconds = 1f,
    spectralSubtractor = SpectralSubtractor(),
    yamNetGate = yamNetGate,
    usePreprocessing = true,   // enable benchmark path
)
```

### LiveInferenceEngine (live recording)

Either construct directly with `usePreprocessing = true`, or update the factory in
`SwappableModule.provideLiveInferenceEngineFactory` (revert the default back to `true`
temporarily for a test build):

```kotlin
LiveInferenceEngine(
    model = birdnet,
    yamNetGate = yamGate,
    spectralSubtractor = SpectralSubtractor(),
    sampleRateHz = sampleRateHz,
    usePreprocessing = true,   // benchmark path
)
```

## Metrics to Collect

For each test recording session (same environment, same recordings played back):

| Metric | Description |
|--------|-------------|
| **Detection rate** | Fraction of known species present that were detected (recall). |
| **False positive rate** | Detections of species not present / total detections. |
| **Median confidence** | Median confidence score across all emitted `WindowPrediction`s. |
| **Top-1 accuracy** | Whether the highest-confidence prediction matches the ground-truth species. |
| **Gate pass rate** | Fraction of windows that passed the YAMNet gate (`PASS` vs `DOWNRANK`). |
| **Processing latency** | Wall-clock time per window (ms) — preprocessing adds CPU overhead. |

Collect metrics for both `usePreprocessing = false` (raw) and `usePreprocessing = true`
(preprocessed) on identical recordings.

## Test Protocol

1. Record a set of reference clips (≥ 5 min) in a variety of acoustic environments:
   - Quiet woodland (low background noise)
   - Roadside / urban (moderate traffic noise)
   - Wind / rain (high-frequency interference above 250 Hz cut-off)

2. Annotate ground-truth species with time ranges.

3. Run both pipeline variants back-to-back on each clip using `InferenceRunner` (offline
   batch mode avoids real-time latency confounds).

4. Record results in the table below.

## Results

> **Status: Placeholder — no benchmark runs yet.**

| Recording | Environment | Preprocessing | Detection rate | FP rate | Median confidence |
|-----------|-------------|---------------|---------------|---------|-------------------|
| clip_01   | Quiet woodland | off (raw) | — | — | — |
| clip_01   | Quiet woodland | on (HPF+SS) | — | — | — |
| clip_02   | Roadside       | off (raw) | — | — | — |
| clip_02   | Roadside       | on (HPF+SS) | — | — | — |
| clip_03   | Wind           | off (raw) | — | — | — |
| clip_03   | Wind           | on (HPF+SS) | — | — | — |

## Decision Criteria

Enable preprocessing in production only if **both** of the following hold for the majority of
environments:

- Detection rate (recall) improves by ≥ 5 percentage points, OR
- False positive rate drops by ≥ 5 percentage points

…without causing a regression of > 10 pp in the other metric.

If results are inconclusive or show no benefit, keep `usePreprocessing = false` permanently
and remove the preprocessing flag in a follow-up cleanup.
