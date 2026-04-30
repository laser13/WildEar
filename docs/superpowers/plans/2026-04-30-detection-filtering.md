# Detection Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce false-positive detections via two post-inference filters: a minimum-windows-per-taxon gate and a geographic region filter backed by the iNaturalist API.

**Architecture:** After inference, `DetectionAggregator` drops species seen in fewer than `minWindows` time-windows. A new `RegionFilter` then queries iNat's `/observations` endpoint to verify each surviving species has ever been observed near the recording location; results are cached in-memory per session. Both filters are wired through `ProductionInferenceJob` and the regional filter is configurable via `Settings` and `SettingsScreen`.

**Tech Stack:** Kotlin, OkHttp (existing), DataStore Preferences (existing), Jetpack Compose (existing), JUnit 4 + Truth + Robolectric + MockWebServer (existing test stack).

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt` | Add `minWindows` parameter |
| Modify | `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt` | Tests for `minWindows` |
| Modify | `app/src/main/java/com/sound2inat/app/data/Settings.kt` | New regional filter prefs |
| Modify | `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt` | `hasObservationsNear()` |
| Modify | `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt` | Tests for new method |
| Create | `app/src/main/java/com/sound2inat/inat/RegionFilter.kt` | Cache + filter logic |
| Create | `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt` | Unit tests |
| Modify | `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt` | Wire filter into `ProductionInferenceJob` |
| Modify | `app/src/main/java/com/sound2inat/app/di/AppModule.kt` | Provide `RegionFilter` |
| Modify | `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt` | New state fields |
| Modify | `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt` | New flows/setters |
| Modify | `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt` | Regional filter section |
| Modify | `app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt` | New settings tests |

---

## Task 1: DetectionAggregator — minWindows filter

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt`
- Modify: `app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt`

- [ ] **Step 1: Write failing test for minWindows**

Add to `DetectionAggregatorTest.kt`, after the last test:

```kotlin
@Test
fun `minWindows filters species seen fewer times than threshold`() {
    val agg2 = DetectionAggregator(minConfidence = 0.10f, minWindows = 2)
    val preds = listOf(
        wp(0, 3_000, "Parus major", 0.8f),
        wp(1_000, 4_000, "Parus major", 0.6f),   // 2 windows — passes
        wp(0, 3_000, "Sylvia atricapilla", 0.9f), // 1 window — filtered
    )
    val out = agg2.aggregate(preds).map { it.taxonScientificName }
    assertThat(out).containsExactly("Parus major")
}

@Test
fun `minWindows defaults to 1 — single-window species pass`() {
    val agg1 = DetectionAggregator(minConfidence = 0.10f) // default minWindows = 1
    val preds = listOf(wp(0, 3_000, "Parus major", 0.8f))
    val out = agg1.aggregate(preds).map { it.taxonScientificName }
    assertThat(out).containsExactly("Parus major")
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.DetectionAggregatorTest" 2>&1 | tail -20
```
Expected: FAIL — `minWindows is not a known parameter`.

- [ ] **Step 3: Add minWindows parameter to DetectionAggregator**

Replace the class signature and `aggregate` body in `DetectionAggregator.kt`:

```kotlin
class DetectionAggregator(
    private val minConfidence: Float = 0.10f,
    private val minWindows: Int = 1,
) {
    fun aggregate(preds: List<WindowPrediction>): List<AggregatedDetection> =
        preds.asSequence()
            .filter { it.confidence >= minConfidence }
            .filter { isLivingTaxon(it.taxonScientificName) }
            .groupBy { it.taxonScientificName }
            .map { (taxon, items) ->
                val bySource = items
                    .filter { it.source.isNotEmpty() }
                    .groupBy { it.source }
                    .mapValues { (_, src) -> src.maxOf { it.confidence } }
                AggregatedDetection(
                    taxonScientificName = taxon,
                    taxonCommonName = items.firstNotNullOfOrNull { it.taxonCommonName },
                    maxConfidence = items.maxOf { it.confidence },
                    detectedWindows = items.size,
                    firstSeenMs = items.minOf { it.startMs },
                    lastSeenMs = items.maxOf { it.endMs },
                    confidenceBySource = bySource,
                )
            }
            .filter { it.detectedWindows >= minWindows }
            .sortedByDescending { it.maxConfidence }
```

- [ ] **Step 4: Run all DetectionAggregator tests**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inference.DetectionAggregatorTest" 2>&1 | tail -20
```
Expected: All 6 tests pass.

- [ ] **Step 5: Set minWindows = 2 in ProductionInferenceJob**

In `ReviewViewModel.kt`, find line ~602:
```kotlin
val aggregator = DetectionAggregator(minConfidence = minConf)
```
Replace with:
```kotlin
val aggregator = DetectionAggregator(minConfidence = minConf, minWindows = 2)
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/inference/DetectionAggregator.kt \
        app/src/test/java/com/sound2inat/inference/DetectionAggregatorTest.kt \
        app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt
git commit -m "feat(inference): add minWindows filter to DetectionAggregator (default 2 in production)"
```

---

## Task 2: Settings — regional filter preferences

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/data/Settings.kt`

- [ ] **Step 1: Add new keys and flows to Settings.kt**

Replace the entire `Settings.kt` content with:

```kotlin
package com.sound2inat.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("sound2inat")

class Settings(private val ctx: Context) {
    private object K {
        val MIN_CONF = floatPreferencesKey("min_conf")
        val TOP_K = intPreferencesKey("top_k")
        val INAT_TOKEN = stringPreferencesKey("inat_token")
        val INAT_LOGIN = stringPreferencesKey("inat_login")
        val REGION_FILTER_ENABLED = booleanPreferencesKey("region_filter_enabled")
        val REGION_RADIUS_KM = intPreferencesKey("region_radius_km")
        val LAST_KNOWN_LAT = doublePreferencesKey("last_known_lat")
        val LAST_KNOWN_LON = doublePreferencesKey("last_known_lon")
    }

    val minConfidenceDisplay: Flow<Float> = ctx.dataStore.data.map { it[K.MIN_CONF] ?: DEFAULT_MIN_CONF }
    val topK: Flow<Int> = ctx.dataStore.data.map { it[K.TOP_K] ?: DEFAULT_TOP_K }
    val inatToken: Flow<String?> = ctx.dataStore.data.map { it[K.INAT_TOKEN]?.takeIf(String::isNotBlank) }
    val inatLogin: Flow<String?> = ctx.dataStore.data.map { it[K.INAT_LOGIN]?.takeIf(String::isNotBlank) }
    val regionalFilterEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[K.REGION_FILTER_ENABLED] ?: true }
    val regionRadiusKm: Flow<Int> = ctx.dataStore.data.map { it[K.REGION_RADIUS_KM] ?: DEFAULT_REGION_RADIUS_KM }
    val lastKnownLat: Flow<Double?> = ctx.dataStore.data.map { it[K.LAST_KNOWN_LAT] }
    val lastKnownLon: Flow<Double?> = ctx.dataStore.data.map { it[K.LAST_KNOWN_LON] }

    suspend fun setMinConfidenceDisplay(v: Float) { ctx.dataStore.edit { it[K.MIN_CONF] = v } }
    suspend fun setTopK(v: Int) { ctx.dataStore.edit { it[K.TOP_K] = v } }
    suspend fun setInatToken(v: String?) {
        ctx.dataStore.edit {
            if (v.isNullOrBlank()) it.remove(K.INAT_TOKEN) else it[K.INAT_TOKEN] = v
        }
    }
    suspend fun setInatLogin(v: String?) {
        ctx.dataStore.edit {
            if (v.isNullOrBlank()) it.remove(K.INAT_LOGIN) else it[K.INAT_LOGIN] = v
        }
    }
    suspend fun setRegionalFilterEnabled(v: Boolean) { ctx.dataStore.edit { it[K.REGION_FILTER_ENABLED] = v } }
    suspend fun setRegionRadiusKm(v: Int) { ctx.dataStore.edit { it[K.REGION_RADIUS_KM] = v } }
    suspend fun setLastKnownCoords(lat: Double, lon: Double) {
        ctx.dataStore.edit {
            it[K.LAST_KNOWN_LAT] = lat
            it[K.LAST_KNOWN_LON] = lon
        }
    }

    companion object {
        const val DEFAULT_MIN_CONF = 0.25f
        const val DEFAULT_TOP_K = 5
        const val DEFAULT_REGION_RADIUS_KM = 200
    }
}
```

- [ ] **Step 2: Verify build compiles**

```
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|warning:" | head -20
```
Expected: No errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/data/Settings.kt
git commit -m "feat(settings): add regional filter prefs (enabled toggle, radius km, last known coords)"
```

---

## Task 3: INaturalistClient — hasObservationsNear

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- Modify: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `INaturalistClientTest.kt` after the last test:

```kotlin
@Test
fun `hasObservationsNear returns true when total_results is positive`() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"results":[{"id":1}],"total_results":1}"""),
    )
    val found = client.hasObservationsNear("Parus major", 55.75, 37.62, 200)
    assertThat(found).isTrue()
    val req = server.takeRequest()
    assertThat(req.path).contains("taxon_name=Parus+major")
    assertThat(req.path).contains("lat=55.75")
    assertThat(req.path).contains("lng=37.62")
    assertThat(req.path).contains("radius=200")
    assertThat(req.path).contains("per_page=1")
}

@Test
fun `hasObservationsNear returns false when total_results is zero`() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"results":[],"total_results":0}"""),
    )
    val found = client.hasObservationsNear("Gnorimopsar chopi", 55.75, 37.62, 200)
    assertThat(found).isFalse()
}

@Test
fun `hasObservationsNear returns true on network error (fail-open)`() = runTest {
    server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
    val found = client.hasObservationsNear("Parus major", 55.75, 37.62, 200)
    assertThat(found).isTrue()
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.INaturalistClientTest" 2>&1 | tail -20
```
Expected: FAIL — `hasObservationsNear` not found.

- [ ] **Step 3: Implement hasObservationsNear**

Add the following method to `INaturalistClient.kt`, after `fetchTaxonPhotoUrl` and before `addIdentification`:

```kotlin
/**
 * Returns true if [scientificName] has at least one observation within [radiusKm] km of
 * ([lat], [lon]) on iNaturalist. Anonymous — no token required.
 *
 * Fail-open: returns true on any network or parse error so a transient outage never
 * silently drops a valid detection.
 */
suspend fun hasObservationsNear(
    scientificName: String,
    lat: Double,
    lon: Double,
    radiusKm: Int,
): Boolean = withContext(ioDispatcher) {
    val q = scientificName.replace(' ', '+')
    val path = "/observations?taxon_name=$q&lat=$lat&lng=$lon&radius=$radiusKm&per_page=1"
    val req = anonGet(path)
    runCatching { executeJson(req) }
        .map { json -> json.optInt("total_results", 0) > 0 }
        .getOrDefault(true)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.INaturalistClientTest" 2>&1 | tail -20
```
Expected: All tests pass (including the 3 new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INaturalistClient.kt \
        app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt
git commit -m "feat(inat): add hasObservationsNear for regional species filtering"
```

---

## Task 4: RegionFilter — new class

**Files:**
- Create: `app/src/main/java/com/sound2inat/inat/RegionFilter.kt`
- Create: `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt`:

```kotlin
package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RegionFilterTest {

    private fun det(name: String) = AggregatedDetection(
        taxonScientificName = name,
        taxonCommonName = null,
        maxConfidence = 0.9f,
        detectedWindows = 3,
        firstSeenMs = 0L,
        lastSeenMs = 3_000L,
    )

    @Test
    fun `filter keeps species with observations near location`() = runTest {
        val filter = RegionFilter { name, _, _, _ -> name == "Parus major" }
        val result = filter.filter(
            detections = listOf(det("Parus major"), det("Gnorimopsar chopi")),
            lat = 55.75,
            lon = 37.62,
            radiusKm = 200,
        )
        assertThat(result.map { it.taxonScientificName }).containsExactly("Parus major")
    }

    @Test
    fun `filter passes all species when lookup returns true for all`() = runTest {
        val filter = RegionFilter { _, _, _, _ -> true }
        val result = filter.filter(
            detections = listOf(det("Parus major"), det("Sylvia atricapilla")),
            lat = 55.75,
            lon = 37.62,
            radiusKm = 200,
        )
        assertThat(result).hasSize(2)
    }

    @Test
    fun `filter returns empty list when no species found nearby`() = runTest {
        val filter = RegionFilter { _, _, _, _ -> false }
        val result = filter.filter(
            detections = listOf(det("Gnorimopsar chopi")),
            lat = 55.75,
            lon = 37.62,
            radiusKm = 200,
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `filter caches results — lookup called only once per species per region`() = runTest {
        var callCount = 0
        val filter = RegionFilter { _, _, _, _ -> callCount++; true }
        val detections = listOf(det("Parus major"), det("Parus major"))
        filter.filter(detections, 55.75, 37.62, 200)
        assertThat(callCount).isEqualTo(1)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.RegionFilterTest" 2>&1 | tail -20
```
Expected: FAIL — `RegionFilter` class not found.

- [ ] **Step 3: Create RegionFilter.kt**

Create `app/src/main/java/com/sound2inat/inat/RegionFilter.kt`:

```kotlin
package com.sound2inat.inat

import com.sound2inat.inference.AggregatedDetection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

fun interface RegionLookup {
    suspend fun hasObservationsNear(
        scientificName: String,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): Boolean
}

class RegionFilter(private val lookup: RegionLookup) {

    private val cache = ConcurrentHashMap<String, Boolean>()

    suspend fun filter(
        detections: List<AggregatedDetection>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): List<AggregatedDetection> = coroutineScope {
        val roundedLat = (lat * 10).roundToInt()
        val roundedLon = (lon * 10).roundToInt()
        detections
            .map { det ->
                async {
                    val key = "${det.taxonScientificName}|$roundedLat|$roundedLon|$radiusKm"
                    val allowed = cache.getOrPut(key) {
                        lookup.hasObservationsNear(det.taxonScientificName, lat, lon, radiusKm)
                    }
                    if (allowed) det else null
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.inat.RegionFilterTest" 2>&1 | tail -20
```
Expected: All 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/RegionFilter.kt \
        app/src/test/java/com/sound2inat/inat/RegionFilterTest.kt
git commit -m "feat(inat): add RegionFilter with in-memory cache"
```

---

## Task 5: Wire RegionFilter into ProductionInferenceJob

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt`

- [ ] **Step 1: Provide RegionFilter in AppModule**

In `AppModule.kt`, add import and provider. After `provideINaturalistClient`:

Add import at the top:
```kotlin
import com.sound2inat.inat.RegionFilter
```

Add provider after `provideINatSubmitter`:
```kotlin
@Provides @Singleton
fun provideRegionFilter(client: INaturalistClient): RegionFilter =
    RegionFilter(lookup = client::hasObservationsNear)
```

- [ ] **Step 2: Inject RegionFilter into ReviewViewModelHilt**

In `ReviewViewModel.kt`, find `ReviewViewModelHilt` (line ~474). Add `regionFilter` to its constructor:

Change:
```kotlin
class ReviewViewModelHilt @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repo: DraftRepository,
    private val models: List<@JvmSuppressWildcards BioacousticModel>,
    private val descriptors: List<@JvmSuppressWildcards ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val submitter: INatSubmitter,
    private val inatObservationsDao: InatObservationDao,
    private val inatClient: INaturalistClient,
) : ViewModel() {
```

To:
```kotlin
class ReviewViewModelHilt @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val repo: DraftRepository,
    private val models: List<@JvmSuppressWildcards BioacousticModel>,
    private val descriptors: List<@JvmSuppressWildcards ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val submitter: INatSubmitter,
    private val inatObservationsDao: InatObservationDao,
    private val inatClient: INaturalistClient,
    private val regionFilter: RegionFilter,
) : ViewModel() {
```

Add import at the top of `ReviewViewModel.kt`:
```kotlin
import com.sound2inat.inat.RegionFilter
```

- [ ] **Step 3: Pass regionFilter to ProductionInferenceJob**

In `ReviewViewModelHilt`, find `inference = ProductionInferenceJob(models, descriptors, modelManager, settings)`. Change to:
```kotlin
inference = ProductionInferenceJob(models, descriptors, modelManager, settings, regionFilter),
```

- [ ] **Step 4: Update ProductionInferenceJob to accept and use RegionFilter**

Find `private class ProductionInferenceJob` (line ~575). Change its constructor to:
```kotlin
private class ProductionInferenceJob(
    private val models: List<BioacousticModel>,
    private val descriptors: List<ModelDescriptor>,
    private val modelManager: ModelManager,
    private val settings: Settings,
    private val regionFilter: RegionFilter,
) : InferenceJob {
```

Find the return at the bottom of `run()` (lines ~649-658):
```kotlin
InferenceOutcome.Success(
    modelId = ids,
    modelVersion = versions,
    detections = aggregator.aggregate(allPreds),
    windows = allPreds,
)
```

Replace with:
```kotlin
val rawDetections = aggregator.aggregate(allPreds)

if (latitude != null && longitude != null) {
    settings.setLastKnownCoords(latitude, longitude)
}

val filteredDetections = when {
    !settings.regionalFilterEnabled.first() -> rawDetections
    latitude != null && longitude != null -> {
        val radius = settings.regionRadiusKm.first()
        regionFilter.filter(rawDetections, latitude, longitude, radius)
    }
    else -> {
        val lastLat = settings.lastKnownLat.first()
        val lastLon = settings.lastKnownLon.first()
        if (lastLat != null && lastLon != null) {
            val radius = settings.regionRadiusKm.first()
            regionFilter.filter(rawDetections, lastLat, lastLon, radius)
        } else {
            rawDetections
        }
    }
}

InferenceOutcome.Success(
    modelId = ids,
    modelVersion = versions,
    detections = filteredDetections,
    windows = allPreds,
)
```

- [ ] **Step 5: Run full unit test suite to verify no regressions**

```
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/di/AppModule.kt \
        app/src/main/java/com/sound2inat/app/ui/review/ReviewViewModel.kt
git commit -m "feat(inference): wire RegionFilter into ProductionInferenceJob"
```

---

## Task 6: Settings UI — regional filter controls

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write failing test for new settings fields**

Add to `SettingsViewModelTest.kt` after the last test. First look at the `build()` helper at the bottom of the test file to understand its signature, then add:

```kotlin
@Test
fun `setRegionalFilterEnabled propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
    val captured = mutableListOf<Boolean>()
    val flow = MutableStateFlow(true)
    val vm = build(
        initial = ModelInstallState.NotInstalled,
        regionalFilterEnabledFlow = flow,
        writeRegionalFilterEnabled = { captured += it; flow.value = it },
        scope = backgroundScope,
    )
    vm.setRegionalFilterEnabled(false)
    assertThat(captured).containsExactly(false)
    assertThat(vm.state.value.regionalFilterEnabled).isFalse()
}

@Test
fun `setRegionRadiusKm propagates via setter`() = runTest(UnconfinedTestDispatcher()) {
    val captured = mutableListOf<Int>()
    val flow = MutableStateFlow(200)
    val vm = build(
        initial = ModelInstallState.NotInstalled,
        regionRadiusKmFlow = flow,
        writeRegionRadiusKm = { captured += it; flow.value = it },
        scope = backgroundScope,
    )
    vm.setRegionRadiusKm(350)
    assertThat(captured).containsExactly(350)
    assertThat(vm.state.value.regionRadiusKm).isEqualTo(350)
}
```

You will also need to update the `build()` helper to accept the new parameters. Find it in the test file and add the new optional parameters with defaults:

```kotlin
private fun build(
    initial: ModelInstallState = ModelInstallState.NotInstalled,
    install: suspend (ModelDescriptor, (ModelInstallState) -> Unit) -> Unit = { _, _ -> },
    topKFlow: kotlinx.coroutines.flow.MutableStateFlow<Int> = MutableStateFlow(5),
    writeTopK: suspend (Int) -> Unit = {},
    regionalFilterEnabledFlow: kotlinx.coroutines.flow.MutableStateFlow<Boolean> = MutableStateFlow(true),
    writeRegionalFilterEnabled: suspend (Boolean) -> Unit = {},
    regionRadiusKmFlow: kotlinx.coroutines.flow.MutableStateFlow<Int> = MutableStateFlow(200),
    writeRegionRadiusKm: suspend (Int) -> Unit = {},
    scope: CoroutineScope,
): SettingsViewModel = SettingsViewModel(
    descriptors = listOf(descriptor),
    installModel = install,
    removeModel = {},
    resolveState = { initial },
    minConfFlow = MutableStateFlow(0.25f),
    topKFlow = topKFlow,
    writeMinConf = {},
    writeTopK = writeTopK,
    inatTokenFlow = MutableStateFlow(null),
    inatLoginFlow = MutableStateFlow(null),
    writeInatToken = {},
    writeInatLogin = {},
    verifyInatToken = { "" },
    regionalFilterEnabledFlow = regionalFilterEnabledFlow,
    writeRegionalFilterEnabled = writeRegionalFilterEnabled,
    regionRadiusKmFlow = regionRadiusKmFlow,
    writeRegionRadiusKm = writeRegionRadiusKm,
    externalScope = scope,
)
```

Note: read the current `build()` helper in the test file first to see its exact current signature before modifying — add only the new parameters to what's already there.

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.settings.SettingsViewModelTest" 2>&1 | tail -20
```
Expected: FAIL — new parameters not yet in `SettingsViewModel`.

- [ ] **Step 3: Add new fields to SettingsUiState**

In `SettingsUiState.kt`, change `SettingsUiState`:

```kotlin
data class SettingsUiState(
    val sections: List<ModelSection> = emptyList(),
    val minConfidenceDisplay: Float = 0.25f,
    val topK: Int = 5,
    val inatTokenField: String = "",
    val inatLogin: String? = null,
    val inatTestStatus: InatTestStatus = InatTestStatus.Idle,
    val regionalFilterEnabled: Boolean = true,
    val regionRadiusKm: Int = 200,
)
```

- [ ] **Step 4: Add new parameters and logic to SettingsViewModel**

In `SettingsViewModel.kt`, add to the constructor parameter list (after `verifyInatToken`):

```kotlin
private val regionalFilterEnabledFlow: Flow<Boolean>,
private val regionRadiusKmFlow: Flow<Int>,
private val writeRegionalFilterEnabled: suspend (Boolean) -> Unit,
private val writeRegionRadiusKm: suspend (Int) -> Unit,
```

In the `init` block, add collection of the new flows:

```kotlin
scope.launch {
    regionalFilterEnabledFlow.collect { v ->
        _state.value = _state.value.copy(regionalFilterEnabled = v)
    }
}
scope.launch {
    regionRadiusKmFlow.collect { v ->
        _state.value = _state.value.copy(regionRadiusKm = v)
    }
}
```

Add new public functions before `private fun updateSection`:

```kotlin
fun setRegionalFilterEnabled(v: Boolean) { scope.launch { writeRegionalFilterEnabled(v) } }
fun setRegionRadiusKm(v: Int) { scope.launch { writeRegionRadiusKm(v) } }
```

In `SettingsViewModelHilt`, update the `delegate` construction to pass the new arguments (after `verifyInatToken`):

```kotlin
regionalFilterEnabledFlow = settings.regionalFilterEnabled,
writeRegionalFilterEnabled = { settings.setRegionalFilterEnabled(it) },
regionRadiusKmFlow = settings.regionRadiusKm,
writeRegionRadiusKm = { settings.setRegionRadiusKm(it) },
```

- [ ] **Step 5: Run settings VM tests**

```
./gradlew :app:testDebugUnitTest --tests "com.sound2inat.app.ui.settings.SettingsViewModelTest" 2>&1 | tail -20
```
Expected: All tests pass.

- [ ] **Step 6: Add regional filter section to SettingsScreen**

In `SettingsScreen.kt`, find the `AboutSection()` composable and add a new section before it. Add the following composable function before `AboutSection`:

```kotlin
@Suppress("FunctionNaming")
@Composable
private fun RegionalFilterSection(state: SettingsUiState, vm: SettingsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Regional filter")
        Switch(
            checked = state.regionalFilterEnabled,
            onCheckedChange = { vm.setRegionalFilterEnabled(it) },
        )
    }
    if (state.regionalFilterEnabled) {
        Text("Search radius: ${state.regionRadiusKm} km")
        Slider(
            value = state.regionRadiusKm.toFloat(),
            onValueChange = { vm.setRegionRadiusKm(it.toInt()) },
            valueRange = MIN_REGION_RADIUS.toFloat()..MAX_REGION_RADIUS.toFloat(),
            steps = (MAX_REGION_RADIUS - MIN_REGION_RADIUS) / REGION_RADIUS_STEP - 1,
        )
    }
}
```

Add the new constants at the bottom of `SettingsScreen.kt`:
```kotlin
private const val MIN_REGION_RADIUS = 50
private const val MAX_REGION_RADIUS = 500
private const val REGION_RADIUS_STEP = 50
```

Add the following imports to `SettingsScreen.kt` (after the existing `import androidx.compose.foundation.layout.Column` line):
```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
```

In the main Column where `SectionCard` blocks are listed (around line 74), add the new section **between** the "Inference" and "iNaturalist" cards:

```kotlin
SectionCard(title = "Inference") {
    InferenceSection(state, vm)
}
SectionCard(title = "Regional filter") {
    RegionalFilterSection(state, vm)
}
SectionCard(title = "iNaturalist") {
    INaturalistSection(state, vm)
}
```

- [ ] **Step 7: Run full unit test suite**

```
./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```
Expected: All tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/settings/SettingsUiState.kt \
        app/src/main/java/com/sound2inat/app/ui/settings/SettingsViewModel.kt \
        app/src/main/java/com/sound2inat/app/ui/settings/SettingsScreen.kt \
        app/src/test/java/com/sound2inat/app/ui/settings/SettingsViewModelTest.kt
git commit -m "feat(settings): add regional filter controls (toggle + radius slider)"
```
