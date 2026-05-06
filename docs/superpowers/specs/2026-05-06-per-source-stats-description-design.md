# Per-Source Detection Stats in iNaturalist Description

**Date:** 2026-05-06
**Branch:** feat/radar

## Goal

Change the auto-generated iNaturalist observation description so each contributing model is listed separately with its own window count, time range, and max confidence:

```
Recorded with WildEar.
BirdNET v2.4 detected 3 window(s) between 2–14 s, max confidence 85%.
Perch v2 (Google) detected 1 window(s) between 5–8 s, max confidence 62%.

Sibling observations from the same recording:
 - Turdus merula → https://www.inaturalist.org/observations/123
 - Parus major → https://www.inaturalist.org/observations/124
```

Currently the description is one merged line: `"Recorded with WildEar (BirdNET v2.4). Detected 4 window(s) between 2–14 s, max confidence 85%."` — no per-model breakdown, model name baked into header.

---

## Approach

Extend the existing `sources` TEXT column with per-source windows, firstSeenMs, lastSeenMs in addition to the existing per-source maxConf. No DB migration required — the existing column is reused with a richer format that is backward-compatible.

**Format change:**
- Old: `"birdnet_v2_4=0.85;perch_v2=0.62"`
- New: `"birdnet_v2_4=0.85:3:500:12000;perch_v2=0.62:1:3000:6000"` (conf:windows:firstMs:lastMs)

Old-format rows (conf only, no colon fields) continue to parse safely; description falls back to the aggregated `detectedWindows`/`firstSeenMs`/`lastSeenMs` columns for those rows.

---

## Components

### 1. `SourceStat` + `SourceStats` — replaces `SourceConfidences`

**File:** `app/src/main/java/com/sound2inat/inference/SourceStats.kt`
**Deletes:** `app/src/main/java/com/sound2inat/inference/SourceConfidences.kt`

```kotlin
data class SourceStat(
    val maxConf: Float,
    val windows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

object SourceStats {
    fun encode(map: Map<String, SourceStat>): String?
    fun decode(text: String?): Map<String, SourceStat>
    // Convenience for callers that only need confidence (ReviewViewModel).
    fun decodeConfidenceOnly(text: String?): Map<String, Float>
}
```

**Encode:** `"src=conf:windows:firstMs:lastMs"` entries joined by `;`, sorted by key. Returns `null` when map is empty (matches current `SourceConfidences` behaviour).

**Decode:** splits on `;`, then on `=`. After `=`, splits on `:`. If only one token (old format) → `windows = detectedWindows` fallback not available here — caller uses aggregated columns. If four tokens → full stat. Unknown/malformed tokens skipped silently.

**`decodeConfidenceOnly`:** calls `decode()` then maps each entry to `maxConf`. Used by `ReviewViewModel` and `SpeciesDetailsSheet` which only display per-source confidence.

---

### 2. `AggregatedDetection` — three new fields

**File:** `app/src/main/java/com/sound2inat/inference/Detection.kt`

Add to `AggregatedDetection`:
```kotlin
val windowsBySource: Map<String, Int> = emptyMap()
val firstSeenBySource: Map<String, Long> = emptyMap()
val lastSeenBySource: Map<String, Long> = emptyMap()
```

Default to empty maps so all existing construction sites (tests, review VM) compile without changes.

---

### 3. `DetectionAggregator` — populate new fields

**File:** `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`

In `aggregate()`, the existing `bySource` computation groups `WindowPrediction` items by `source`. Extend to also compute:

```kotlin
val groupedBySource = items.filter { it.source.isNotEmpty() }.groupBy { it.source }
val bySource       = groupedBySource.mapValues { (_, src) -> src.maxOf { it.confidence } }
val windowsBySource   = groupedBySource.mapValues { (_, src) -> src.size }
val firstSeenBySource = groupedBySource.mapValues { (_, src) -> src.minOf { it.startMs } }
val lastSeenBySource  = groupedBySource.mapValues { (_, src) -> src.maxOf { it.endMs } }
```

Pass the three new maps into `AggregatedDetection(...)`.

---

### 4. `DraftRepository` — encode new fields

**File:** `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`

Two call sites (`saveDraft`, `attachDetections`) build `DetectionEntity`. Replace:
```kotlin
sources = SourceConfidences.encode(it.confidenceBySource)
```
with:
```kotlin
sources = SourceStats.encode(
    it.confidenceBySource.mapValues { (src, conf) ->
        SourceStat(
            maxConf = conf,
            windows = it.windowsBySource[src] ?: 0,
            firstSeenMs = it.firstSeenBySource[src] ?: it.firstSeenMs,
            lastSeenMs = it.lastSeenBySource[src] ?: it.lastSeenMs,
        )
    }
)
```

---

### 5. `ReviewViewModel` — decode update

**File:** `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`

Two call sites call `SourceConfidences.decode(e.sources)`. Replace with `SourceStats.decodeConfidenceOnly(e.sources)`. Return type is the same `Map<String, Float>`, so no further changes needed.

---

### 6. `INatSubmitter` — new description generation

**File:** `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt`

#### `baseDescription(det: DetectionEntity): String`

```
Recorded with WildEar.
{displayName} detected {N} window(s) between {first}–{last} s, max confidence {X}%.
[one line per model, sorted by model id]
```

If `SourceStats.decode(det.sources)` returns an empty map (legacy row), fall back to the old single-line format:
```
Recorded with WildEar.
Detected {N} window(s) between {first}–{last} s, max confidence {X}%.
```

Model display names come from `KnownModels.firstOrNull { it.id == src }?.displayName ?: src`. This avoids duplicating the name strings and stays in sync with `ModelDescriptor`.

#### `crossLink()` — signature change

Current signature: `crossLink(token, rows: List<InatObservationEntity>)`
New signature: `crossLink(token, pairs: List<Pair<InatObservationEntity, DetectionEntity>>)`

In `submit()`, accumulate `createdPairs: MutableList<Pair<InatObservationEntity, DetectionEntity>>` alongside `createdRows`, pass to `crossLink`.

Inside `crossLink`, for each pair build:
```
{baseDescription(det)}

Sibling observations from the same recording:
 - {taxonScientificName} → {observationUrl}
 - ...
```

And PUT this as the updated description (replacing the initial `baseDescription`-only version set at creation time).

---

## Data Flow

```
WindowPrediction (source, startMs, endMs, confidence)
  ↓ DetectionAggregator.aggregate()
AggregatedDetection (confidenceBySource, windowsBySource, firstSeenBySource, lastSeenBySource)
  ↓ DraftRepository.saveDraft() / attachDetections()
DetectionEntity.sources = "birdnet_v2_4=0.85:3:500:12000;perch_v2=0.62:1:3000:6000"
  ↓ INatSubmitter.baseDescription()
"Recorded with WildEar.\nBirdNET v2.4 detected 3 window(s) ..."
```

---

## Backward Compatibility

| Row version | `sources` value | Description behaviour |
|-------------|----------------|----------------------|
| v4 (old format) | `"birdnet_v2_4=0.85"` | Falls back to aggregated columns — single merged line |
| v5+ (new format) | `"birdnet_v2_4=0.85:3:500:12000"` | Full per-model lines |
| null (pre-v4) | `null` | Falls back to aggregated columns |

---

## Tests

**`DetectionAggregatorTest`** — existing tests need assertions extended: check that `windowsBySource`, `firstSeenBySource`, `lastSeenBySource` are populated correctly for multi-source inputs. Add a test for single-source (only one model predicts) — other model keys should be absent from all three maps.

**`SourceStatsTest`** (new) — round-trip encode/decode for full format, old-format decode (conf only), empty/null/malformed input.

**`INatSubmitterTest`** (if present) — update expected description strings.

---

## Files Changed

| File | Action |
|------|--------|
| `inference/SourceStats.kt` | Create (replaces SourceConfidences) |
| `inference/SourceConfidences.kt` | Delete |
| `inference/Detection.kt` | Add 3 fields to AggregatedDetection |
| `inference/DetectionAggregator.kt` | Compute 3 new per-source maps |
| `storage/DraftRepository.kt` | Encode new fields (2 call sites) |
| `app/ui/review/ReviewViewModel.kt` | Use SourceStats.decodeConfidenceOnly (2 call sites) |
| `inat/INatSubmitter.kt` | New baseDescription + crossLink signature |
| `inference/DetectionAggregatorTest.kt` | Extend assertions |
| `inference/SourceStatsTest.kt` | Create |