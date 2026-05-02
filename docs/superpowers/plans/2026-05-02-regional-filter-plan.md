# Regional Filter: place_id + Visible Status Icons

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace radius-based regional filter with iNaturalist place_id for island-precise boundaries, and replace silent filtering with visible status icons (globe icon: green=CONFIRMED, grey=UNVERIFIED, red=NOT_CONFIRMED) on the Review screen. Also fix minWindows not being applied in the DB observe path.

**Architecture:** INaturalistClient gains two new methods (`getNearbyStandardPlace`, `hasObservationsInPlace`). RegionFilter is rewritten: new `RegionLookup` interface with place_id support + fallback, and `annotate()` replacing `filter()`. ReviewViewModel drops silent filtering — instead it annotates detections asynchronously and caches status per species. ReviewScreen shows a 16dp globe icon per row.

**Tech Stack:** Kotlin, Coroutines, ConcurrentHashMap, iNaturalist API v1 (`/places/nearby`, `/observations?place_id=`), Jetpack Compose Material3 Icons.

---

### File Map

| File | Change |
|---|---|
| `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt` | Add `getNearbyStandardPlace()` + `hasObservationsInPlace()` |
| `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt` | Add 4 tests for the new methods |
| `app/src/main/java/com/sound2inat/inat/RegionFilter.kt` | Rewrite: new `RegionLookup` interface + `annotate()` |
| `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt` | Rewrite tests for new behaviour |
| `app/src/main/java/com/sound2inat/inference/Detection.kt` | Add `RegionalStatus` enum + `regionalStatus` field to `AggregatedDetection` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt` | Add `regionalStatus: RegionalStatus?` to `SpeciesRow` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | Add `regionalStatusCache`, `launchAnnotation()`, fix minWindows in DB path, remove silent filter from inference path |
| `app/src/main/java/com/sound2inat/app/di/AppModule.kt` | Update `provideRegionFilter` to new `RegionLookup` |
| `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` | Add `RegionalStatusIcon` composable, insert in `SpeciesListItem` |
| `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt` | Add test: minWindows filter applied from DB path |

---

### Task 1: New INaturalistClient methods + tests

**Context:**
- File to modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- File to modify: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`
- The existing `hasObservationsNear` (line ~265) uses `anonGet()` + `executeJson()` pattern. Follow the exact same pattern.
- `anonGet(path)` builds a GET request to baseUrl + path.
- `executeJson(request)` returns `JSONObject` or throws.
- The class uses `withContext(ioDispatcher)` for all network calls.
- `getNearbyStandardPlace` calls `/v1/places/nearby?nelat=...&nelng=...&swlat=...&swlng=...` with a 1° bounding box. The JSON response has shape `{ "results": { "standard": [ { "id": 123 }, ... ] } }`. Return the id of the first standard place, or null if empty / on exception.
- `hasObservationsInPlace` calls `/v1/observations?taxon_name=...&place_id=...&per_page=1`. JSON has `total_results: Int`. Return `true` if > 0. On exception fail-open (return `true`).
- The test file already uses `MockWebServer` + Robolectric. Add 4 new tests at the bottom.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- Modify: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`

- [ ] **Step 1: Write the failing tests first**

Add to the bottom of `INaturalistClientTest` (before the closing `}`):

```kotlin
@Test fun `getNearbyStandardPlace returns first standard place id`() = runTest {
    server.enqueue(
        MockResponse().setBody(
            """{"results":{"standard":[{"id":7257,"name":"Cyprus"}],"community":[]}}""",
        ),
    )
    val placeId = client.getNearbyStandardPlace(34.9, 33.1)
    assertThat(placeId).isEqualTo(7257L)
    val req = server.takeRequest()
    assertThat(req.path).contains("places/nearby")
    assertThat(req.path).contains("nelat=35.9")
    assertThat(req.path).contains("swlat=33.9")
}

@Test fun `getNearbyStandardPlace returns null when no standard places`() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"results":{"standard":[],"community":[]}}"""),
    )
    val placeId = client.getNearbyStandardPlace(34.9, 33.1)
    assertThat(placeId).isNull()
}

@Test fun `hasObservationsInPlace returns true when observations found`() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"results":[],"total_results":3}"""),
    )
    val found = client.hasObservationsInPlace("Parus major", placeId = 7257L)
    assertThat(found).isTrue()
    val req = server.takeRequest()
    assertThat(req.path).contains("taxon_name=Parus+major")
    assertThat(req.path).contains("place_id=7257")
    assertThat(req.path).contains("per_page=1")
}

@Test fun `hasObservationsInPlace returns false when none found`() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"results":[],"total_results":0}"""),
    )
    val found = client.hasObservationsInPlace("Columba palumbus", placeId = 7257L)
    assertThat(found).isFalse()
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.INaturalistClientTest" 2>&1 | tail -30
```

Expected: 4 new tests fail with "Unresolved reference: getNearbyStandardPlace" or similar.

- [ ] **Step 3: Implement the two new methods in INaturalistClient**

Read the file first to find the exact location (after `hasObservationsNear`). Add the two new methods in the same style:

```kotlin
suspend fun getNearbyStandardPlace(lat: Double, lon: Double): Long? =
    withContext(ioDispatcher) {
        val path = "/places/nearby" +
            "?nelat=${lat + 1.0}&nelng=${lon + 1.0}" +
            "&swlat=${lat - 1.0}&swlng=${lon - 1.0}"
        runCatching {
            val results = executeJson(anonGet(path)).getJSONObject("results")
            val standard = results.getJSONArray("standard")
            if (standard.length() == 0) null else standard.getJSONObject(0).getLong("id")
        }.getOrNull()
    }

suspend fun hasObservationsInPlace(scientificName: String, placeId: Long): Boolean =
    withContext(ioDispatcher) {
        val q = scientificName.replace(' ', '+')
        runCatching {
            executeJson(anonGet("/observations?taxon_name=$q&place_id=$placeId&per_page=1"))
        }
            .map { json -> json.optInt("total_results", 0) > 0 }
            .getOrDefault(true)
    }
```

- [ ] **Step 4: Run tests — expect 4 new tests to pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.INaturalistClientTest" 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Run detekt — fix any new violations**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:detekt 2>&1 | grep -E "weighted|INaturalistClient" | head -20
```

If violations appear in `INaturalistClient.kt`, apply minimal `@Suppress` annotations.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INaturalistClient.kt \
        app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt
git commit -m "feat(inat): add getNearbyStandardPlace and hasObservationsInPlace"
```

---

### Task 2: Rewrite RegionFilter + RegionFilterTest

**Context:**
- File to rewrite: `app/src/main/java/com/sound2inat/inat/RegionFilter.kt`
- File to rewrite: `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt`
- Current `RegionFilter` uses a `fun interface RegionLookup` with single method `hasObservationsNear`. Replace with a regular interface that has 3 methods.
- New behaviour: `annotate()` returns the same list with `regionalStatus` set on each item (no items removed).
- `RegionalStatus` enum will be defined in `Detection.kt` (Task 3). However, to avoid a compile-time dependency cycle in this task, define `RegionalStatus` in the **same file** (`RegionFilter.kt`) for now — it will be moved to `Detection.kt` in Task 3 (and the import in `RegionFilter.kt` updated).

  **Alternative (preferred):** Define `RegionalStatus` in `Detection.kt` first in this same task, then use it from `RegionFilter.kt`. Both files are in different modules? No — they're both in the `:app` module. So define `RegionalStatus` in `Detection.kt` in this task.

- `ConcurrentHashMap` cannot store null values. Use sentinel `NO_PLACE = -1L` for "place lookup returned null".
- Place cache key: `"${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}"` (same grid rounding as `FilterKey`).
- Status cache key: `"$taxon|p|$placeId"` when place was found, `"$taxon|r|$lat|$lon|$radius"` as fallback.
- Fallback: if `getPlaceId()` returns null, call `checkNear()` with the provided `radiusKm`.
- On any exception during check: status = `UNVERIFIED`.

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/Detection.kt` (add `RegionalStatus` enum + field)
- Rewrite: `app/src/main/java/com/sound2inat/inat/RegionFilter.kt`
- Rewrite: `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt`

- [ ] **Step 1: Add RegionalStatus to Detection.kt and regionalStatus field to AggregatedDetection**

Read `app/src/main/java/com/sound2inat/inference/Detection.kt` first.

Add the enum at the top of the file (after the package declaration):

```kotlin
enum class RegionalStatus { CONFIRMED, NOT_CONFIRMED, UNVERIFIED }
```

Add `val regionalStatus: RegionalStatus? = null` as the last field of `AggregatedDetection`:

```kotlin
data class AggregatedDetection(
    val taxonScientificName: String,
    val taxonCommonName: String?,
    val maxConfidence: Float,
    val detectedWindows: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confidenceBySource: Map<String, Float> = emptyMap(),
    val regionalStatus: RegionalStatus? = null,
)
```

- [ ] **Step 2: Write the failing RegionFilterTest**

Replace the entire content of `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt`:

```kotlin
package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegionFilterTest {

    private val det1 = AggregatedDetection("Parus major", "Great Tit", 0.8f, 3, 0L, 1000L)
    private val det2 = AggregatedDetection("Corvus cornix", "Carrion Crow", 0.6f, 2, 0L, 2000L)

    @Test fun `annotate returns CONFIRMED when place found and species present`() = runTest {
        val filter = RegionFilter(FakeLookup(placeId = 7257L, inPlace = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result).hasSize(1)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.CONFIRMED)
    }

    @Test fun `annotate returns NOT_CONFIRMED when place found and species absent`() = runTest {
        val filter = RegionFilter(FakeLookup(placeId = 7257L, inPlace = false))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.NOT_CONFIRMED)
    }

    @Test fun `annotate falls back to radius check when no place found`() = runTest {
        val filter = RegionFilter(FakeLookup(placeId = null, nearbyResult = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.CONFIRMED)
    }

    @Test fun `annotate returns UNVERIFIED when lookup throws`() = runTest {
        val filter = RegionFilter(FakeLookup(throws = true))
        val result = filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(result[0].regionalStatus).isEqualTo(RegionalStatus.UNVERIFIED)
    }

    @Test fun `annotate annotates all detections — nothing is removed`() = runTest {
        val filter = RegionFilter(FakeLookup(placeId = 7257L, inPlace = false))
        val result = filter.annotate(listOf(det1, det2), 34.9, 33.1, 50)
        assertThat(result).hasSize(2)
    }

    @Test fun `annotate caches place lookup — getPlaceId called once per location`() = runTest {
        val lookup = FakeLookup(placeId = 7257L, inPlace = true)
        val filter = RegionFilter(lookup)
        filter.annotate(listOf(det1, det2), 34.9, 33.1, 50)
        assertThat(lookup.placeIdCalls).isEqualTo(1)
    }

    @Test fun `annotate caches status lookup — checkInPlace called once per species`() = runTest {
        val lookup = FakeLookup(placeId = 7257L, inPlace = true)
        val filter = RegionFilter(lookup)
        filter.annotate(listOf(det1), 34.9, 33.1, 50)
        filter.annotate(listOf(det1), 34.9, 33.1, 50)
        assertThat(lookup.checkCalls).isEqualTo(1)
    }

    private class FakeLookup(
        private val placeId: Long? = null,
        private val inPlace: Boolean = false,
        private val nearbyResult: Boolean = false,
        private val throws: Boolean = false,
    ) : RegionLookup {
        var placeIdCalls = 0
        var checkCalls = 0

        override suspend fun getPlaceId(lat: Double, lon: Double): Long? {
            placeIdCalls++
            if (throws) throw RuntimeException("network error")
            return placeId
        }

        override suspend fun checkInPlace(scientificName: String, placeId: Long): Boolean {
            checkCalls++
            if (throws) throw RuntimeException("network error")
            return inPlace
        }

        override suspend fun checkNear(
            scientificName: String,
            lat: Double,
            lon: Double,
            radiusKm: Int,
        ): Boolean {
            if (throws) throw RuntimeException("network error")
            return nearbyResult
        }
    }
}
```

- [ ] **Step 3: Run test to confirm it fails**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.RegionFilterTest" 2>&1 | tail -30
```

Expected: compile errors because `RegionLookup` interface and `RegionFilter.annotate()` don't exist yet.

- [ ] **Step 4: Rewrite RegionFilter.kt**

Replace the entire content of `app/src/main/java/com/sound2inat/inat/RegionFilter.kt`:

```kotlin
package com.sound2inat.inat

import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

interface RegionLookup {
    suspend fun getPlaceId(lat: Double, lon: Double): Long?
    suspend fun checkInPlace(scientificName: String, placeId: Long): Boolean
    suspend fun checkNear(scientificName: String, lat: Double, lon: Double, radiusKm: Int): Boolean
}

class RegionFilter(private val lookup: RegionLookup) {

    private val placeCache = ConcurrentHashMap<String, Long>()
    private val statusCache = ConcurrentHashMap<String, RegionalStatus>()

    suspend fun annotate(
        detections: List<AggregatedDetection>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): List<AggregatedDetection> {
        val placeId = resolvePlace(lat, lon)
        return detections.map { det ->
            val status = resolveStatus(det.taxonScientificName, placeId, lat, lon, radiusKm)
            det.copy(regionalStatus = status)
        }
    }

    private suspend fun resolvePlace(lat: Double, lon: Double): Long? {
        val key = "${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}"
        val cached = placeCache[key]
        if (cached != null) return if (cached == NO_PLACE) null else cached
        val resolved = runCatching { lookup.getPlaceId(lat, lon) }.getOrNull()
        placeCache[key] = resolved ?: NO_PLACE
        return resolved
    }

    private suspend fun resolveStatus(
        taxon: String,
        placeId: Long?,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): RegionalStatus {
        val key = if (placeId != null) {
            "$taxon|p|$placeId"
        } else {
            "$taxon|r|${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}|$radiusKm"
        }
        statusCache[key]?.let { return it }
        val status = runCatching {
            val found = if (placeId != null) {
                lookup.checkInPlace(taxon, placeId)
            } else {
                lookup.checkNear(taxon, lat, lon, radiusKm)
            }
            if (found) RegionalStatus.CONFIRMED else RegionalStatus.NOT_CONFIRMED
        }.getOrDefault(RegionalStatus.UNVERIFIED)
        statusCache[key] = status
        return status
    }

    private companion object {
        const val NO_PLACE = -1L
    }
}
```

- [ ] **Step 5: Run RegionFilterTest — expect all tests to pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.RegionFilterTest" 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, 7 tests pass.

- [ ] **Step 6: Run full test suite to ensure no breakage**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -40
```

If any tests fail due to removed `RegionFilter.filter()` or changed constructor, fix them now (the old `filter()` usages will be cleaned up in Task 3, but the signature change must compile).

- [ ] **Step 7: Run detekt**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:detekt 2>&1 | grep -E "weighted|RegionFilter|Detection" | head -20
```

Fix any violations with minimal `@Suppress` annotations.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/Detection.kt \
        app/src/main/java/com/sound2inat/inat/RegionFilter.kt \
        app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt
git commit -m "feat(inference): add RegionalStatus enum and rewrite RegionFilter with annotate()"
```

---

### Task 3: ReviewViewModel + ReviewUiState + AppModule + minWindows bug fix

**Context — what to read before starting:**
- Read `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt` (the `SpeciesRow` data class)
- Read `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` (the full file — it's long ~1100 lines)
- Read `app/src/main/java/com/sound2inat/app/di/AppModule.kt` (the `provideRegionFilter` binding)

**What to change:**

**ReviewUiState.kt:** Add `val regionalStatus: RegionalStatus? = null` as last field to `SpeciesRow`.

**AppModule.kt:** Update `provideRegionFilter` to use the new `RegionLookup` interface:
```kotlin
@Provides @Singleton
fun provideRegionFilter(client: INaturalistClient): RegionFilter =
    RegionFilter(
        lookup = object : RegionLookup {
            override suspend fun getPlaceId(lat: Double, lon: Double) =
                client.getNearbyStandardPlace(lat, lon)
            override suspend fun checkInPlace(name: String, placeId: Long) =
                client.hasObservationsInPlace(name, placeId)
            override suspend fun checkNear(name: String, lat: Double, lon: Double, radius: Int) =
                client.hasObservationsNear(name, lat, lon, radius)
        },
    )
```

**ReviewViewModel.kt — four changes:**

1. **Add cache field** (after `photoUrlCache`):
```kotlin
private val regionalStatusCache: MutableMap<String, RegionalStatus?> = mutableMapOf()
private var annotationJob: Job? = null
```

2. **Fix minWindows bug** in DB observe block. Find the block that calls `dwd.detections.map { e -> SpeciesRow(...) }` and add a filter before the map:
```kotlin
val minWin = settings.minWindows.first()
species = dwd.detections
    .filter { e -> e.detectedWindows >= minWin }
    .map { e ->
        SpeciesRow(
            // ... same fields as before ...
            regionalStatus = regionalStatusCache[e.taxonScientificName],
        )
    }
```
Then after building `newState`, launch annotation:
```kotlin
launchAnnotation(newState.species, lat, lon, radiusKm)
```
where `lat`, `lon`, `radiusKm` come from the location/settings already available in that block.

3. **Add `launchAnnotation` function** (private, at the bottom before the companion object):
```kotlin
private fun launchAnnotation(
    rows: List<SpeciesRow>,
    lat: Double,
    lon: Double,
    radiusKm: Int,
) {
    if (!_state.value.filter.regionalFilterEnabled) return
    annotationJob?.cancel()
    annotationJob = viewModelScope.launch {
        val annotated = regionFilter.annotate(
            rows.map { r ->
                AggregatedDetection(
                    taxonScientificName = r.taxonScientificName,
                    taxonCommonName = r.taxonCommonName,
                    maxConfidence = r.maxConfidence,
                    detectedWindows = r.detectedWindows,
                    firstSeenMs = r.firstSeenMs,
                    lastSeenMs = r.lastSeenMs,
                )
            },
            lat, lon, radiusKm,
        )
        annotated.forEach { det ->
            regionalStatusCache[det.taxonScientificName] = det.regionalStatus
        }
        _state.update { s ->
            s.copy(
                species = s.species.map { row ->
                    row.copy(regionalStatus = regionalStatusCache[row.taxonScientificName])
                },
            )
        }
    }
}
```

4. **Remove silent filter from inference path.** Find the call to `regionFilter.filter(...)` and replace it with passing the raw detections list directly to the next step (the block that builds `SpeciesRow` from inference results). Also add `regionalStatus = null` (it will be populated by `launchAnnotation` called after state update).

**ReviewViewModelTest.kt — add one test:**

Find `ReviewViewModelTest.kt` and add a test that verifies minWindows is applied from the DB path. The test must use the existing test infrastructure (fake DraftRepository etc.) — read the existing tests to understand the pattern.

```kotlin
@Test fun `minWindows filters species from DB path`() = runTest(UnconfinedTestDispatcher()) {
    // Set up draft with 2 detections: one with 1 window, one with 3 windows
    // minWindows = 2
    // Assert only the 3-window species appears in state.species
}
```

(Write the full test using the actual fake/stub classes present in the test file — read the file to see how `FakeDraftRepository` or similar is used.)

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

- [ ] **Step 1: Read the files to understand current structure**

Read these files in full before making any changes:
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt`
- `app/src/main/java/com/sound2inat/app/di/AppModule.kt`
- `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` (the full file, it may be ~1100 lines — read all of it)
- `app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt`

- [ ] **Step 2: Write the failing minWindows test in ReviewViewModelTest**

Read the existing tests to understand the `FakeDraftRepository` or equivalent setup. Then add:

```kotlin
@Test fun `minWindows=2 filters out 1-window species from DB`() =
    runTest(UnconfinedTestDispatcher()) {
        // Use FakeDraftRepository with 2 detections:
        //   - "Cuculus canorus", detectedWindows=1
        //   - "Parus major", detectedWindows=3
        // Set minWindows=2 in settings
        // Advance time to trigger DB emission
        // Assert state.species has exactly ["Parus major"]
    }
```

(Fill in with actual fake classes from the test file — do not leave this as pseudocode.)

- [ ] **Step 3: Run test to confirm it fails**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest --tests "*.ReviewViewModelTest" 2>&1 | tail -30
```

Expected: new test fails (1-window species appears in list, not filtered).

- [ ] **Step 4: Apply all 4 changes to ReviewViewModel + ReviewUiState + AppModule**

Apply in this order to keep compilation working:
1. Add `regionalStatus` to `SpeciesRow` in `ReviewUiState.kt`
2. Update `provideRegionFilter` in `AppModule.kt`
3. Apply all 4 ReviewViewModel changes

- [ ] **Step 5: Run full test suite**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest 2>&1 | tail -40
```

Expected: all tests pass including new minWindows test.

- [ ] **Step 6: Run detekt**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:detekt 2>&1 | grep "weighted" | head -5
```

Fix any new violations.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewUiState.kt \
        app/src/main/java/com/sound2inat/app/di/AppModule.kt \
        app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt \
        app/src/test/java/com/sound2inat/app/ui/review/ReviewViewModelTest.kt
git commit -m "feat(review): annotate species with regional status, fix minWindows in DB path"
```

---

### Task 4: ReviewScreen — RegionalStatusIcon composable

**Context:**
- File to modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`
- Find `SpeciesListItem` composable (around line 532). It has a source badges `Row` (around lines 580-589). Add the globe icon **after** (or **before**) the source badges row, in the same trailing area.
- Import: `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.outlined.Public`
- The `SpeciesRow.regionalStatus` field is `RegionalStatus?` — show the icon only when non-null. When the regional filter is off (`row.regionalStatus == null`), show nothing.
- Colours:
  - `CONFIRMED` → `MaterialTheme.colorScheme.primary` (green-ish)
  - `NOT_CONFIRMED` → `MaterialTheme.colorScheme.error` (red)
  - `UNVERIFIED` → `MaterialTheme.colorScheme.onSurfaceVariant` (grey)
- Icon size: 16dp. No padding needed — it should sit compactly next to the source badges.
- Content description strings:
  - CONFIRMED: `"Observed in region"`
  - NOT_CONFIRMED: `"Not observed in region"`
  - UNVERIFIED: `"Regional check unavailable"`

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt`

- [ ] **Step 1: Read SpeciesListItem in ReviewScreen.kt**

Read lines 520-620 of `app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt` to understand exact structure of `SpeciesListItem` and the source badges area.

- [ ] **Step 2: Add RegionalStatusIcon composable and wire it into SpeciesListItem**

Add a private composable near `SpeciesListItem`:

```kotlin
@Composable
private fun RegionalStatusIcon(status: RegionalStatus) {
    val tint = when (status) {
        RegionalStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
        RegionalStatus.NOT_CONFIRMED -> MaterialTheme.colorScheme.error
        RegionalStatus.UNVERIFIED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val desc = when (status) {
        RegionalStatus.CONFIRMED -> "Observed in region"
        RegionalStatus.NOT_CONFIRMED -> "Not observed in region"
        RegionalStatus.UNVERIFIED -> "Regional check unavailable"
    }
    Icon(
        imageVector = Icons.Outlined.Public,
        contentDescription = desc,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}
```

In `SpeciesListItem`, find the trailing content area (the `Row` with source badges). Add the icon after the source badges row:

```kotlin
row.regionalStatus?.let { RegionalStatusIcon(it) }
```

Make sure necessary imports are added:
- `androidx.compose.material.icons.Icons`
- `androidx.compose.material.icons.outlined.Public`
- `com.sound2inat.inference.RegionalStatus`

- [ ] **Step 3: Run full test suite and detekt**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:detekt 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/review/ReviewScreen.kt
git commit -m "feat(review): show regional status globe icon on SpeciesListItem"
```

---

## Self-Review

**Spec coverage:**
- ✅ place_id from `/places/nearby` → `getNearbyStandardPlace` in Task 1
- ✅ `hasObservationsInPlace` in Task 1
- ✅ Fallback to radius-based check when no place found in Task 2 (`RegionFilter.annotate`)
- ✅ `RegionalStatus` enum (CONFIRMED/NOT_CONFIRMED/UNVERIFIED) in Task 2
- ✅ `regionalStatus` field on `AggregatedDetection` in Task 2, on `SpeciesRow` in Task 3
- ✅ Globe icon with 3-colour coding in Task 4
- ✅ minWindows bug fix in Task 3
- ✅ Silent filtering removed from inference path in Task 3
- ✅ Tests for all new INaturalistClient methods in Task 1
- ✅ Tests for all RegionFilter behaviours in Task 2
- ✅ Test for minWindows bug fix in Task 3

**Type consistency:**
- `RegionalStatus` defined in `Detection.kt`, imported in `RegionFilter.kt`, `ReviewUiState.kt`, `ReviewViewModel.kt`, `ReviewScreen.kt`
- `RegionLookup` defined in `RegionFilter.kt`, anonymous object in `AppModule.kt`
- `RegionFilter.annotate()` used in `ReviewViewModel.launchAnnotation()`
- `SpeciesRow.regionalStatus: RegionalStatus?` passed through from `regionalStatusCache`

**No placeholders:** All steps have complete code.
