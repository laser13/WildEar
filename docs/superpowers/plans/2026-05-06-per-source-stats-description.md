# Per-Source Detection Stats in iNaturalist Description — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store per-model window count and time range alongside existing per-source confidence, then use it to generate per-model lines in the iNaturalist observation description.

**Architecture:** Extend the existing `sources` TEXT column on `detections` with a richer format `"src=conf:windows:firstMs:lastMs"` (backward-compatible with the old `"src=conf"` format). `DetectionAggregator` computes the new per-source stats; `SourceStats` (replaces `SourceConfidences`) serialises them; `INatSubmitter.baseDescription` generates per-model lines from them.

**Tech Stack:** Kotlin, Room (SQLite), JUnit 4, Google Truth, Robolectric, MockWebServer

---

## File Map

| Action | File |
|--------|------|
| Create | `app/src/main/java/com/sound2inat/inference/SourceStats.kt` |
| Delete | `app/src/main/java/com/sound2inat/inference/SourceConfidences.kt` |
| Modify | `app/src/main/java/com/sound2inat/inference/Detection.kt` |
| Modify | `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt` |
| Modify | `app/src/main/java/com/sound2inat/storage/DraftRepository.kt` |
| Modify | `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` (lines 359, 680) |
| Modify | `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt` |
| Create | `app/src/test/java/com/sound2inat/inference/SourceStatsTest.kt` |
| Modify | `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt` |
| Modify | `app/src/test/java/com/sound2inat/inat/INatSubmitterTest.kt` |

---

## Task 1: `SourceStats` — new serialisation class (TDD)

Replaces `SourceConfidences`. New format: `"birdnet_v2_4=0.85:3:500:12000;perch_v2=0.62:1:3000:6000"`.
`SourceConfidences` is deleted only in Task 5 after all callers are migrated.

**Files:**
- Create: `app/src/main/java/com/sound2inat/inference/SourceStats.kt`
- Create: `app/src/test/java/com/sound2inat/inference/SourceStatsTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/sound2inat/inference/SourceStatsTest.kt`:

```kotlin
package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceStatsTest {

    @Test fun `encode round-trips through decode`() {
        val input = mapOf(
            "birdnet_v2_4" to SourceStat(0.85f, 3, 500L, 12_000L),
            "perch_v2"     to SourceStat(0.62f, 1, 3_000L, 6_000L),
        )
        val encoded = SourceStats.encode(input)!!
        val decoded  = SourceStats.decode(encoded)
        assertThat(decoded).isEqualTo(input)
    }

    @Test fun `encode returns null for empty map`() {
        assertThat(SourceStats.encode(emptyMap())).isNull()
    }

    @Test fun `decode null returns empty map`() {
        assertThat(SourceStats.decode(null)).isEmpty()
    }

    @Test fun `decode blank string returns empty map`() {
        assertThat(SourceStats.decode("   ")).isEmpty()
    }

    @Test fun `decode old format (conf only) returns empty map — caller uses aggregated columns`() {
        // Rows written before this feature only have "src=conf", no colon fields.
        assertThat(SourceStats.decode("birdnet_v2_4=0.85")).isEmpty()
    }

    @Test fun `decode skips malformed tokens silently`() {
        val result = SourceStats.decode("bad;birdnet_v2_4=0.85:3:500:12000;=also-bad")
        assertThat(result).hasSize(1)
        assertThat(result["birdnet_v2_4"]).isEqualTo(SourceStat(0.85f, 3, 500L, 12_000L))
    }

    @Test fun `decodeConfidenceOnly handles new format`() {
        val text = "birdnet_v2_4=0.85:3:500:12000;perch_v2=0.62:1:3000:6000"
        val result = SourceStats.decodeConfidenceOnly(text)
        assertThat(result).containsExactly("birdnet_v2_4", 0.85f, "perch_v2", 0.62f)
    }

    @Test fun `decodeConfidenceOnly handles old format (conf only)`() {
        val result = SourceStats.decodeConfidenceOnly("birdnet_v2_4=0.85;perch_v2=0.62")
        assertThat(result).containsExactly("birdnet_v2_4", 0.85f, "perch_v2", 0.62f)
    }

    @Test fun `encode sorts entries by key for deterministic output`() {
        val input = mapOf(
            "perch_v2"     to SourceStat(0.62f, 1, 0L, 5_000L),
            "birdnet_v2_4" to SourceStat(0.85f, 3, 0L, 9_000L),
        )
        val encoded = SourceStats.encode(input)!!
        assertThat(encoded.indexOf("birdnet")).isLessThan(encoded.indexOf("perch"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.SourceStatsTest" 2>&1 | tail -20
```

Expected: compilation error — `SourceStat`, `SourceStats` not found.

- [ ] **Step 3: Create `SourceStats.kt`**

Create `app/src/main/java/com/sound2inat/inference/SourceStats.kt`:

```kotlin
package com.sound2inat.inference

data class SourceStat(
    val maxConf: Float,
    val windows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

/**
 * Serialises per-source detection stats as a flat string for the `sources`
 * TEXT column. Format: `"src1=conf:windows:firstMs:lastMs;src2=…"`.
 *
 * Backward-compatible with the v4 confidence-only format `"src=conf"`:
 * [decode] returns an empty map for those rows (caller falls back to
 * the aggregated `detectedWindows`/`firstSeenMs`/`lastSeenMs` columns).
 * [decodeConfidenceOnly] handles both formats and always returns the
 * confidence map (used by ReviewViewModel / SpeciesDetailsSheet).
 */
object SourceStats {

    fun encode(map: Map<String, SourceStat>): String? {
        if (map.isEmpty()) return null
        return map.entries
            .sortedBy { it.key }
            .joinToString(";") { (k, v) ->
                "$k=${v.maxConf}:${v.windows}:${v.firstSeenMs}:${v.lastSeenMs}"
            }
    }

    fun decode(text: String?): Map<String, SourceStat> {
        if (text.isNullOrBlank()) return emptyMap()
        return buildMap {
            for (token in text.split(';')) {
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.length - 1) continue
                val key = token.substring(0, eq).trim()
                val parts = token.substring(eq + 1).trim().split(':')
                if (parts.size < 4) continue  // old format — skip
                val conf    = parts[0].toFloatOrNull() ?: continue
                val windows = parts[1].toIntOrNull()  ?: continue
                val firstMs = parts[2].toLongOrNull()  ?: continue
                val lastMs  = parts[3].toLongOrNull()  ?: continue
                put(key, SourceStat(conf, windows, firstMs, lastMs))
            }
        }
    }

    /** Returns per-source max confidence. Handles both old and new format. */
    fun decodeConfidenceOnly(text: String?): Map<String, Float> {
        if (text.isNullOrBlank()) return emptyMap()
        return buildMap {
            for (token in text.split(';')) {
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.length - 1) continue
                val key  = token.substring(0, eq).trim()
                val conf = token.substring(eq + 1).trim().split(':')[0].toFloatOrNull() ?: continue
                put(key, conf)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests — all must pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.SourceStatsTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/SourceStats.kt \
        app/src/test/java/com/sound2inat/inference/SourceStatsTest.kt
git commit -m "feat(inference): SourceStats — per-source windows+ranges serialisation"
```

---

## Task 2: Extend `AggregatedDetection` and `DetectionAggregator`

Add `windowsBySource`, `firstSeenBySource`, `lastSeenBySource` to the in-memory model and compute them in the aggregator.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/Detection.kt`
- Modify: `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt`

- [ ] **Step 1: Add failing tests to `DetectionAggregatorTest.kt`**

Append these two tests at the end of the class body (before the closing `}` and the `private fun wp` helper):

```kotlin
    @Test
    fun `per-source maps populated from source-tagged predictions`() {
        val preds = listOf(
            WindowPrediction(0L,     3_000L, "Parus major", null, 0.85f, "birdnet_v2_4"),
            WindowPrediction(3_000L, 6_000L, "Parus major", null, 0.70f, "birdnet_v2_4"),
            WindowPrediction(1_000L, 6_000L, "Parus major", null, 0.62f, "perch_v2"),
        )
        val out = DetectionAggregator(minConfidence = 0.10f).aggregate(preds).first()

        assertThat(out.windowsBySource).containsExactly("birdnet_v2_4", 2, "perch_v2", 1)
        assertThat(out.firstSeenBySource).containsExactly("birdnet_v2_4", 0L, "perch_v2", 1_000L)
        assertThat(out.lastSeenBySource).containsExactly("birdnet_v2_4", 6_000L, "perch_v2", 6_000L)
    }

    @Test
    fun `per-source maps empty when all predictions have no source tag`() {
        val preds = listOf(
            wp(0L, 3_000L, "Parus major", 0.8f),  // source = "" (default wp helper)
            wp(1_000L, 4_000L, "Parus major", 0.6f),
        )
        val out = DetectionAggregator(minConfidence = 0.10f).aggregate(preds).first()

        assertThat(out.windowsBySource).isEmpty()
        assertThat(out.firstSeenBySource).isEmpty()
        assertThat(out.lastSeenBySource).isEmpty()
    }
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.DetectionAggregatorTest" 2>&1 | tail -20
```

Expected: compilation error — `windowsBySource`, `firstSeenBySource`, `lastSeenBySource` not found on `AggregatedDetection`.

- [ ] **Step 3: Add fields to `AggregatedDetection` in `Detection.kt`**

The existing `AggregatedDetection` ends at line 36. Add three fields after `confidenceBySource`:

```kotlin
data class AggregatedDetection(
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confidenceBySource: Map<String, Float> = emptyMap(),
    val windowsBySource: Map<String, Int> = emptyMap(),
    val firstSeenBySource: Map<String, Long> = emptyMap(),
    val lastSeenBySource: Map<String, Long> = emptyMap(),
    val regionalStatus: RegionalStatus? = null,
    val fragmentRanges: List<FragmentRange> = emptyList(),
    val aggregatedConfidence: Float = 0f,
)
```

- [ ] **Step 4: Update `DetectionAggregator.kt`**

In `aggregate()`, find the existing block (around line 38–43):

```kotlin
val bySource = items
    .filter { it.source.isNotEmpty() }
    .groupBy { it.source }
    .mapValues { (_, src) -> src.maxOf { it.confidence } }
```

Replace it with:

```kotlin
val groupedBySource   = items.filter { it.source.isNotEmpty() }.groupBy { it.source }
val bySource          = groupedBySource.mapValues { (_, src) -> src.maxOf { it.confidence } }
val windowsBySource   = groupedBySource.mapValues { (_, src) -> src.size }
val firstSeenBySource = groupedBySource.mapValues { (_, src) -> src.minOf { it.startMs } }
val lastSeenBySource  = groupedBySource.mapValues { (_, src) -> src.maxOf { it.endMs } }
```

Then in the `AggregatedDetection(...)` constructor call (around line 45), add the three new fields after `confidenceBySource = bySource`:

```kotlin
AggregatedDetection(
    taxonScientificName = taxon,
    taxonCommonName     = items.firstNotNullOfOrNull { it.taxonCommonName },
    maxConfidence       = items.maxOf { it.confidence },
    detectedWindows     = items.size,
    firstSeenMs         = items.minOf { it.startMs },
    lastSeenMs          = items.maxOf { it.endMs },
    confidenceBySource  = bySource,
    windowsBySource     = windowsBySource,
    firstSeenBySource   = firstSeenBySource,
    lastSeenBySource    = lastSeenBySource,
    fragmentRanges      = ranges,
    aggregatedConfidence = items.map { it.confidence }.average().toFloat(),
)
```

- [ ] **Step 5: Run tests — all must pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.DetectionAggregatorTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all existing + 2 new tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/Detection.kt \
        app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt \
        app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt
git commit -m "feat(inference): per-source window count and time bounds in AggregatedDetection"
```

---

## Task 3: Update `DraftRepository` to encode new per-source stats

`DraftRepository` has two call sites that write `sources`. Both replace `SourceConfidences.encode(it.confidenceBySource)` with `SourceStats.encode(…)`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/storage/DraftRepository.kt`

- [ ] **Step 1: Update import**

At the top of `DraftRepository.kt`, replace:

```kotlin
import com.sound2inat.inference.SourceConfidences
```

with:

```kotlin
import com.sound2inat.inference.SourceStat
import com.sound2inat.inference.SourceStats
```

- [ ] **Step 2: Replace both encode call sites**

There are two identical blocks (in `createWithDetections` around line 122 and in `attachDetections` around line 160). In each, replace:

```kotlin
sources = SourceConfidences.encode(it.confidenceBySource),
```

with:

```kotlin
sources = SourceStats.encode(
    it.confidenceBySource.mapValues { (src, conf) ->
        SourceStat(
            maxConf     = conf,
            windows     = it.windowsBySource[src]   ?: 0,
            firstSeenMs = it.firstSeenBySource[src] ?: it.firstSeenMs,
            lastSeenMs  = it.lastSeenBySource[src]  ?: it.lastSeenMs,
        )
    }
),
```

- [ ] **Step 3: Build to confirm no compilation errors**

```bash
./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sound2inat/storage/DraftRepository.kt
git commit -m "feat(storage): encode per-source window stats into detections.sources column"
```

---

## Task 4: Update `ReviewViewModel` and delete `SourceConfidences`

Migrate the two decode call sites in `ReviewViewModel` to `SourceStats.decodeConfidenceOnly`, then delete `SourceConfidences.kt`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Delete: `app/src/main/java/com/sound2inat/inference/SourceConfidences.kt`

- [ ] **Step 1: Update `ReviewViewModel.kt`**

Update the import at the top — replace:

```kotlin
import com.sound2inat.inference.SourceConfidences
```

with:

```kotlin
import com.sound2inat.inference.SourceStats
```

Then at line 359 and line 680, replace:

```kotlin
confidenceBySource = SourceConfidences.decode(e.sources),
```

with:

```kotlin
confidenceBySource = SourceStats.decodeConfidenceOnly(e.sources),
```

- [ ] **Step 2: Delete `SourceConfidences.kt`**

```bash
rm app/src/main/java/com/sound2inat/inference/SourceConfidences.kt
```

- [ ] **Step 3: Build and run all tests**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass (no remaining references to `SourceConfidences`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt
git rm app/src/main/java/com/sound2inat/inference/SourceConfidences.kt
git commit -m "refactor(inference): replace SourceConfidences with SourceStats"
```

---

## Task 5: Update `INatSubmitter` — new description format + crossLink

Generate per-model lines in the description and append the sibling section during cross-linking.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INatSubmitter.kt`
- Modify: `app/src/test/java/com/sound2inat/inat/INatSubmitterTest.kt`

### 5a — Tests first

- [ ] **Step 1: Add test for per-source description to `INatSubmitterTest.kt`**

Add a helper method `draftWithSources` after the existing `draftWith` method:

```kotlin
/** Like [draftWith] but sets [sources] on the first detection. */
private fun draftWithSources(name: String, sources: String): DraftWithDetections {
    val base = draftWith(listOf(name))
    val det  = base.detections.first().copy(sources = sources)
    return base.copy(detections = listOf(det))
}
```

Then add this test:

```kotlin
@Test fun `description contains per-source model lines when sources populated`() = runTest {
    val sources = "birdnet_v2_4=0.85:3:0:9000;perch_v2=0.62:1:3000:6000"
    val draft = draftWithSources("Parus major", sources)

    server.enqueue(MockResponse().setBody("""{"results":[{"id":12345,"iconic_taxon_name":"Aves"}]}"""))
    server.enqueue(MockResponse().setBody("""{"id":900,"uuid":"u-1"}"""))
    server.enqueue(MockResponse().setBody("""{"results":[{"id":1}]}"""))
    server.enqueue(MockResponse().setBody("""{"id":11}"""))  // annotation Alive
    server.enqueue(MockResponse().setBody("""{"id":12}"""))  // annotation Organism

    submitter.submit("jwt", draft)

    // Drain taxa request, then inspect the createObservation POST body.
    server.takeRequest()  // GET /taxa
    val obsRequest = server.takeRequest()  // POST /observations
    val body = obsRequest.body.readUtf8()

    assertThat(body).contains("Recorded with WildEar.")
    assertThat(body).contains("BirdNET v2.4 detected 3 window(s) between 0–9 s")
    assertThat(body).contains("Perch v2 (Google) detected 1 window(s) between 3–6 s")
}
```

- [ ] **Step 2: Add test for multi-species cross-link format**

Add this test:

```kotlin
@Test fun `cross-link description contains base description and sibling bullet list`() = runTest {
    val sources = "birdnet_v2_4=0.85:3:0:9000"
    val base = draftWith(listOf("Parus major", "Sylvia atricapilla"))
    // Set sources on both detections
    val dets = base.detections.mapIndexed { i, d ->
        if (i == 0) d.copy(sources = sources) else d
    }
    val draft = base.copy(detections = dets)

    // Parus major
    server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"iconic_taxon_name":"Aves"}]}"""))
    server.enqueue(MockResponse().setBody("""{"id":700,"uuid":"u-A"}"""))
    server.enqueue(MockResponse().setBody("""{"results":[{"id":555}]}"""))
    server.enqueue(MockResponse().setBody("""{"id":11}"""))
    server.enqueue(MockResponse().setBody("""{"id":12}"""))
    // Sylvia atricapilla
    server.enqueue(MockResponse().setBody("""{"results":[{"id":2,"iconic_taxon_name":"Aves"}]}"""))
    server.enqueue(MockResponse().setBody("""{"id":701,"uuid":"u-B"}"""))
    server.enqueue(MockResponse().setBody("""{"results":[{"id":556}]}"""))
    server.enqueue(MockResponse().setBody("""{"id":13}"""))
    server.enqueue(MockResponse().setBody("""{"id":14}"""))
    // PUT cross-links
    server.enqueue(MockResponse().setBody("""{"id":700}"""))
    server.enqueue(MockResponse().setBody("""{"id":701}"""))

    submitter.submit("jwt", draft)

    repeat(10) { server.takeRequest() }

    val put1Body = server.takeRequest().body.readUtf8()
    assertThat(put1Body).contains("Recorded with WildEar.")
    assertThat(put1Body).contains("Sibling observations from the same recording:")
    assertThat(put1Body).contains(" - Sylvia atricapilla →")

    val put2Body = server.takeRequest().body.readUtf8()
    assertThat(put2Body).contains("Sibling observations from the same recording:")
    assertThat(put2Body).contains(" - Parus major →")
}
```

- [ ] **Step 3: Run new tests — confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.INatSubmitterTest" 2>&1 | tail -30
```

Expected: two new tests FAIL (description body doesn't match yet).

### 5b — Implementation

- [ ] **Step 4: Update imports in `INatSubmitter.kt`**

Add these imports:

```kotlin
import com.sound2inat.inference.SourceStats
import com.sound2inat.modelmanager.KnownModels
```

- [ ] **Step 5: Accumulate `createdPairs` in `submit()`**

In the `submit()` function, after `val createdRows = mutableListOf<InatObservationEntity>()`, add:

```kotlin
val createdPairs = mutableListOf<Pair<InatObservationEntity, DetectionEntity>>()
```

In the `outcome.onSuccess` block, after `createdRows += row`, add:

```kotlin
createdPairs += row to det
```

Replace the cross-link call:

```kotlin
if (createdRows.size > 1) {
    crossLink(token, createdRows)
}
```

with:

```kotlin
if (createdPairs.size > 1) {
    crossLink(token, createdPairs)
}
```

- [ ] **Step 6: Replace `baseDescription` function**

Find and replace the existing `baseDescription` function (currently around line 213–216):

```kotlin
private fun baseDescription(det: DetectionEntity): String {
    val stats = SourceStats.decode(det.sources)
    val header = "Recorded with WildEar."
    return if (stats.isEmpty()) {
        "$header\nDetected ${det.detectedWindows} window(s)" +
            " between ${det.firstSeenMs / MS}–${det.lastSeenMs / MS} s," +
            " max confidence ${"%.0f".format(det.maxConfidence * PCT)}%."
    } else {
        val lines = stats.entries.sortedBy { it.key }.joinToString("\n") { (src, stat) ->
            val name = sourceDisplayName(src)
            "$name detected ${stat.windows} window(s)" +
                " between ${stat.firstSeenMs / MS}–${stat.lastSeenMs / MS} s," +
                " max confidence ${"%.0f".format(stat.maxConf * PCT)}%."
        }
        "$header\n$lines"
    }
}

private fun sourceDisplayName(id: String): String =
    KnownModels.firstOrNull { it.id == id }?.displayName ?: id
```

- [ ] **Step 7: Replace `crossLink` function**

Find and replace the existing `crossLink` function (currently around line 218–228). Change its signature and body:

```kotlin
private suspend fun crossLink(
    token: String,
    pairs: List<Pair<InatObservationEntity, DetectionEntity>>,
) {
    for ((row, det) in pairs) {
        val others = pairs.filter { it.first.id != row.id }
        val siblings = others.joinToString("\n") { (sibRow, _) ->
            " - ${sibRow.taxonScientificName} → ${sibRow.observationUrl}"
        }
        val description = baseDescription(det) +
            "\n\nSibling observations from the same recording:\n$siblings"
        runCatching { client.updateObservationDescription(token, row.observationId, description) }
    }
}
```

- [ ] **Step 8: Run all tests**

```bash
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass including the two new INatSubmitter tests.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INatSubmitter.kt \
        app/src/test/java/com/sound2inat/inat/INatSubmitterTest.kt
git commit -m "feat(inat): per-source model lines in observation description"
```

---

## Self-Review Checklist

- [x] **Spec coverage**: SourceStats ✓, AggregatedDetection new fields ✓, DetectionAggregator ✓, DraftRepository (both call sites) ✓, ReviewViewModel (both call sites) ✓, INatSubmitter baseDescription ✓, crossLink signature change ✓, backward compat (old format → legacy fallback) ✓
- [x] **No placeholders**: all steps have exact code
- [x] **Type consistency**: `SourceStat` used in Task 1, referenced identically in Tasks 3 and 5; `SourceStats.decode` / `decodeConfidenceOnly` match Task 1 definitions throughout; `crossLink` signature `List<Pair<InatObservationEntity, DetectionEntity>>` consistent between Tasks 5b-step-5 and 5b-step-7
- [x] **Build remains green after each task**: Tasks 1–2 are additive; Task 3 migrates encode; Task 4 migrates decode then deletes old file; Task 5 updates INatSubmitter last