# Detection Filtering Design

**Date:** 2026-04-30
**Status:** Approved

## Problem

BirdNET (birds only) and Perch (broad iNaturalist taxonomy) both produce false positives:
- Perch outputs logits near the ceiling for many species — simple confidence threshold cannot discriminate
- Without filtering, recordings in Europe show South American species

Two root causes:
1. Single-window false positives — species appears in 1 out of 26 windows
2. Geographically impossible species — not observed anywhere near recording location

## Solution: Two post-inference filter layers

```
WindowPrediction[] (raw predictions from both models)
         ↓
DetectionAggregator  ← minConfidence + minDetectedWindows (≥ 2)
         ↓
AggregatedDetection[] (species that passed window filter)
         ↓
RegionFilter         ← iNat API: was this species observed within R km?
         ↓
AggregatedDetection[] (final list → Room DB → UI)
```

Cross-model score calibration was considered but rejected: Perch false positives have nearly identical logit values to true positives (~0.98 vs ~0.99), so rescaling cannot discriminate between them. Additionally, BirdNET is bird-only and cannot validate non-bird detections from Perch (frogs, insects, etc.).

## Layer 1: minDetectedWindows

**File:** `DetectionAggregator.kt`

Add parameter `minWindows: Int = 2`. A species passes only if `detectedWindows.size >= minWindows`.

Applied before the regional filter — no point querying iNat for a single-window detection.

`minWindows` is hardcoded to 2, not exposed in Settings. It is a reasonable default for any recording length (Perch 5s windows: ~26 windows per 30s recording; BirdNET 3s: ~28 windows).

## Layer 2: RegionFilter

**File:** `RegionFilter.kt` (new)

```kotlin
class RegionFilter(
    private val client: INaturalistClient,
    private val prefs: Settings
) {
    suspend fun filter(
        detections: List<AggregatedDetection>,
        lat: Double,
        lon: Double
    ): List<AggregatedDetection>
}
```

**Logic:**
1. Round (lat, lon) to 1 decimal place (~11 km precision) → cache key
2. For each species: check in-memory cache. On miss: query iNat `/v1/observations?taxon_name=...&lat=...&lng=...&radius=R&per_page=1`
3. Cache lives for process lifetime (no TTL needed — region doesn't change mid-session)
4. On network error or empty result: species **passes through** (shown) — prefer false positive over silently hiding rare species

**New INaturalistClient method:**
```kotlin
suspend fun hasObservationsNear(
    scientificName: String,
    lat: Double,
    lon: Double,
    radiusKm: Int
): Boolean
```

## Settings changes

```kotlin
val regionRadiusKm: Int = 200           // slider 50–500 km in SettingsScreen
val lastKnownLat: Double? = null        // updated after each recording with GPS
val lastKnownLon: Double? = null
val regionalFilterEnabled: Boolean = true  // toggle in SettingsScreen
```

**Fallback chain when GPS unavailable:**
1. `lastKnownLat` / `lastKnownLon` from previous recording
2. If neither available → skip regional filter entirely, show all detections

## ProductionInferenceJob wiring

```kotlin
val aggregated = DetectionAggregator(minWindows = 2).aggregate(windowPredictions)
val filtered = if (settings.regionalFilterEnabled && lat != null && lon != null) {
    regionFilter.filter(aggregated, lat, lon)
} else {
    aggregated
}
repo.attachDetections(draftId, filtered)
```

GPS coordinates come from `Draft.latitude` / `Draft.longitude` (already stored).

## UI changes

**SettingsScreen:** Add section "Региональный фильтр":
- Toggle `regionalFilterEnabled`
- Slider `regionRadiusKm` (50–500 km, step 50), visible only when toggle is on

## Out of scope

- Cross-model score calibration (rejected — see Problem section)
- Persisting regional cache across sessions (iNat API is fast enough for re-query)
- Per-model minWindows thresholds
- Configuring minWindows in Settings
