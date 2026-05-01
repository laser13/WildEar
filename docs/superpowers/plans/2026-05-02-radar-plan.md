# Radar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the "Radar" tab — a list + OSMDroid map of nearby iNaturalist observations with filters for radius, period, and taxa, backed by a 15-min in-memory cache.

**Architecture:** New bottom-tab navigation (`Recordings | Radar`) hosted by a fresh `RootScaffold`. A new `INatObservationsRepository` makes two parallel iNat HTTP calls (`species_counts` for the list, `observations` for map pins) and joins them client-side via Haversine distance. `RadarViewModel` collects filter changes from `Settings` + `LocationProvider` with `debounce(300ms)`, emits `RadarUiState` to a Compose UI made of `RadarMap` (AndroidView wrapping OSMDroid `MapView`), `RadarFilterBar` (three chip rows), and `RadarBody` (LazyColumn of `SpeciesCountRow`s).

**Tech Stack:** Kotlin, Jetpack Compose (Material3 1.3.0), Hilt DI, Room (existing — not extended), Kotlinx Coroutines + Flow, OkHttp, OSMDroid 6.1.18, AndroidX Browser (Custom Tabs), JUnit 4 + Truth + MockWebServer + Robolectric for tests.

**Build/test commands** (the project uses Gradle 8.13 which needs JDK 17, but the user's `java` PATH is 11):

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INatObservationsRepositoryTest'
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use the third form (filtered tests) while iterating on a single class — full test pass takes ~15 s.

**Spec reference:** [`docs/superpowers/specs/2026-05-02-radar-design.md`](../specs/2026-05-02-radar-design.md). All decisions in the "Decisions Locked" table are final and not up for renegotiation in this plan.

---

## Task 1: Add OSMDroid + Custom Tabs dependencies and Radar Settings keys

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/sound2inat/app/data/Settings.kt`
- Modify: `app/src/main/AndroidManifest.xml` — already declares `INTERNET`/`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` but verify no extra OSMDroid permissions needed (it does not require any beyond `INTERNET`).

- [ ] **Step 1: Add library aliases in `gradle/libs.versions.toml`**

Insert at the bottom of `[versions]` (preserving alphabetical order with the existing block):

```toml
osmdroid = "6.1.18"
androidxBrowser = "1.8.0"
```

Insert at the bottom of `[libraries]`:

```toml
osmdroid-android = { module = "org.osmdroid:osmdroid-android", version.ref = "osmdroid" }
androidx-browser = { module = "androidx.browser:browser", version.ref = "androidxBrowser" }
```

- [ ] **Step 2: Reference the libraries in `app/build.gradle.kts`**

In the `dependencies { ... }` block, after the existing `implementation(libs.androidx.security.crypto)` line:

```kotlin
implementation(libs.osmdroid.android)
implementation(libs.androidx.browser)
```

- [ ] **Step 3: Sync Gradle and confirm OSMDroid resolves**

Run:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (No new code consumes OSMDroid yet, but the build proves the dep resolves and packages.)

- [ ] **Step 4: Add Radar settings keys to `Settings.kt`**

In the `K` object inside `class Settings`, append (preserving alphabetical-ish grouping):

```kotlin
val RADAR_RADIUS_KM = intPreferencesKey("radar_radius_km")
val RADAR_PERIOD_DAYS = intPreferencesKey("radar_period_days")
val RADAR_TAXA = stringPreferencesKey("radar_taxa")
```

Append in the public-properties block (after `birdNetMetaEnabled`):

```kotlin
val radarRadiusKm: Flow<Int> =
    ctx.dataStore.data.map { it[K.RADAR_RADIUS_KM] ?: DEFAULT_RADAR_RADIUS_KM }
val radarPeriodDays: Flow<Int> =
    ctx.dataStore.data.map { it[K.RADAR_PERIOD_DAYS] ?: DEFAULT_RADAR_PERIOD_DAYS }
val radarTaxa: Flow<Set<String>> = ctx.dataStore.data.map {
    it[K.RADAR_TAXA]?.takeIf(String::isNotEmpty)?.split(',')?.toSet() ?: emptySet()
}
```

Append the setters (after `setBirdNetMetaEnabled`):

```kotlin
suspend fun setRadarRadiusKm(v: Int) {
    ctx.dataStore.edit { it[K.RADAR_RADIUS_KM] = v }
}
suspend fun setRadarPeriodDays(v: Int) {
    ctx.dataStore.edit { it[K.RADAR_PERIOD_DAYS] = v }
}
suspend fun setRadarTaxa(v: Set<String>) {
    ctx.dataStore.edit {
        if (v.isEmpty()) it.remove(K.RADAR_TAXA) else it[K.RADAR_TAXA] = v.joinToString(",")
    }
}
```

Append constants in the `companion object`:

```kotlin
const val DEFAULT_RADAR_RADIUS_KM = 5
const val DEFAULT_RADAR_PERIOD_DAYS = 7
```

- [ ] **Step 5: Build and commit**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/sound2inat/app/data/Settings.kt
git commit -m "feat(radar): add OSMDroid + Custom Tabs deps and radar settings keys

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

Expected: `BUILD SUCCESSFUL`, then commit succeeds.

---

## Task 2: Add `userId` to INatAuthRepository (needed for `not_user_id`)

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INatTokenStorage.kt`
- Modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt` (extract `verifyTokenWithUser` returning login + id)
- Modify: `app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt` (expose `userId: Long?` and persist it)
- Test: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`

The Radar repo needs a numeric user id to pass as `not_user_id=`. The current `verifyToken` returns only `login`. We replace it with a method returning both, store the id alongside login in `INatTokenStorage`, and surface it on `INatAuthRepository`.

- [ ] **Step 1: Write failing test for the new client method**

Append in `INaturalistClientTest.kt` before the closing `}` of the class:

```kotlin
@Test fun `verifyTokenWithUser returns login and id`() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"results":[{"id":42,"login":"alice"}]}"""),
    )
    val (login, id) = client.verifyTokenWithUser("jwt")
    assertThat(login).isEqualTo("alice")
    assertThat(id).isEqualTo(42L)
    val req = server.takeRequest()
    assertThat(req.path).isEqualTo("/v1/users/me")
    assertThat(req.getHeader("Authorization")).isEqualTo("jwt")
}
```

- [ ] **Step 2: Run test, confirm it fails**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INaturalistClientTest.*verifyTokenWithUser*'
```

Expected: FAIL with `Unresolved reference: verifyTokenWithUser`.

- [ ] **Step 3: Implement the method**

In `INaturalistClient.kt`, add (above the existing `verifyToken`):

```kotlin
/** Returns [Pair] of `(login, userId)` on success; throws [INatException] otherwise. */
suspend fun verifyTokenWithUser(token: String): Pair<String, Long> = withContext(ioDispatcher) {
    val req = authedGet(token, "/users/me")
    val first = executeJson(req).getJSONArray("results").getJSONObject(0)
    first.getString("login") to first.getLong("id")
}
```

Update the existing `verifyToken` to call it (preserves the public signature for callers that only need login):

```kotlin
/** Returns the authenticated login on success; throws [INatException] otherwise. */
suspend fun verifyToken(token: String): String = verifyTokenWithUser(token).first
```

- [ ] **Step 4: Run test, confirm it passes**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INaturalistClientTest'
```

Expected: PASS for the new test plus all existing tests.

- [ ] **Step 5: Extend INatTokenStorage to carry userId**

In `INatTokenStorage.kt`, add a constant in the companion plus a property and update `save`/`clear`:

Add to the companion:
```kotlin
const val KEY_USER_ID = "user_id"
```

Add a property after `login`:
```kotlin
val userId: Long? get() = prefs.getLong(KEY_USER_ID, -1L).takeIf { it > 0 }
```

Replace `save(...)` with:
```kotlin
fun save(token: String, login: String?, userId: Long?, fetchedAtUtcMs: Long) {
    prefs.edit().apply {
        putString(KEY_TOKEN, token)
        if (login != null) putString(KEY_LOGIN, login) else remove(KEY_LOGIN)
        if (userId != null && userId > 0) putLong(KEY_USER_ID, userId) else remove(KEY_USER_ID)
        putLong(KEY_FETCHED_AT, fetchedAtUtcMs)
        apply()
    }
}
```

(`clear()` already wipes everything via `prefs.edit().clear().apply()` — no change.)

- [ ] **Step 6: Update INatAuthRepository to populate and expose `userId`**

In `INatAuthRepository.kt`:

Add a state flow alongside `_loginState`:
```kotlin
private val _userIdState: MutableStateFlow<Long?> = MutableStateFlow(storage.userId)
val userIdState: StateFlow<Long?> = _userIdState.asStateFlow()

/** Convenience: snapshot of [userIdState]. */
val userId: Long? get() = _userIdState.value
```

Update `acceptCapturedToken` body to call the new client method and persist the id:

```kotlin
suspend fun acceptCapturedToken(
    token: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val pair = withContext(ioDispatcher) {
        runCatching { client.verifyTokenWithUser(token) }.getOrNull()
    }
    val login = pair?.first
    val userId = pair?.second
    storage.save(token, login, userId, System.currentTimeMillis())
    _tokenState.value = token
    _loginState.value = login
    _userIdState.value = userId
}
```

Update `logout()` to clear `_userIdState`:
```kotlin
suspend fun logout(mainDispatcher: CoroutineDispatcher = Dispatchers.Main) {
    storage.clear()
    _tokenState.value = null
    _loginState.value = null
    _userIdState.value = null
    withContext(mainDispatcher) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
```

Update the legacy migration in `ensureMigrated()` so legacy tokens save with `userId = null`:

```kotlin
storage.save(legacy, legacyLogin, null, System.currentTimeMillis())
```

- [ ] **Step 7: Build and commit**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug :app:testDebugUnitTest
git add app/src/main/java/com/sound2inat/inat/INaturalistClient.kt \
        app/src/main/java/com/sound2inat/inat/INatTokenStorage.kt \
        app/src/main/java/com/sound2inat/inat/INatAuthRepository.kt \
        app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt
git commit -m "feat(inat): expose authenticated user id alongside login

Required for the upcoming radar feature, which excludes the user's own
observations via not_user_id=<id>.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

Expected: build green, all tests green.

---

## Task 3: Geo helper (Haversine) + Radar data types

**Files:**
- Create: `app/src/main/java/com/sound2inat/inat/Geo.kt`
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/RadarUiState.kt`
- Test: `app/src/test/java/com/sound2inat/inat/GeoTest.kt`

- [ ] **Step 1: Write failing tests for Haversine**

Create `app/src/test/java/com/sound2inat/inat/GeoTest.kt`:

```kotlin
package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {
    @Test
    fun `haversineKm returns zero for same point`() {
        assertThat(haversineKm(50.0, 10.0, 50.0, 10.0)).isEqualTo(0f)
    }

    @Test
    fun `haversineKm Berlin to Paris is roughly 878 km`() {
        // Berlin 52.520, 13.405 → Paris 48.857, 2.352
        val km = haversineKm(52.520, 13.405, 48.857, 2.352)
        assertThat(km).isWithin(5f).of(878f)
    }

    @Test
    fun `haversineKm symmetric`() {
        val a = haversineKm(50.0, 10.0, 51.0, 11.0)
        val b = haversineKm(51.0, 11.0, 50.0, 10.0)
        assertThat(a).isEqualTo(b)
    }
}
```

- [ ] **Step 2: Run tests, confirm they fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.GeoTest'
```

Expected: FAIL with `Unresolved reference: haversineKm`.

- [ ] **Step 3: Implement Haversine**

Create `app/src/main/java/com/sound2inat/inat/Geo.kt`:

```kotlin
package com.sound2inat.inat

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance between two `(lat, lon)` pairs in kilometres,
 * via the Haversine formula. Returns `0f` when the two points are the
 * same. Uses the WGS-84-approximating mean Earth radius — accurate to
 * a few hundred metres at the scales the radar cares about (≤100 km).
 */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = 6371.0 // Earth mean radius, km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2).let { it * it }
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (r * c).toFloat()
}
```

- [ ] **Step 4: Run tests, confirm they pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.GeoTest'
```

Expected: 3/3 PASS.

- [ ] **Step 5: Create the Radar data-types file**

Create `app/src/main/java/com/sound2inat/app/ui/radar/RadarUiState.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import org.osmdroid.util.GeoPoint

/**
 * Cache key for [com.sound2inat.inat.INatObservationsRepository]. Coordinates
 * are quantised to a 0.01° grid (≈1.1 km) so micro-jitter from the GPS does
 * not invalidate the cache between fixes.
 */
data class FilterKey(
    val latGrid: Int,
    val lonGrid: Int,
    val radiusKm: Int,
    val periodDays: Int,
    val taxa: Set<String>,
    val excludeUserId: Long?,
)

/** One row in the species list — single species, aggregated counts/distance. */
data class SpeciesAggregate(
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val iconicTaxon: String,
    val photoUrl: String?,
    val observationCount: Int,
    val nearestObservationKm: Float,
    val nearestObservationUrl: String,
)

/** One pin on the map — a single iNat observation. */
data class MapPin(
    val observationId: Long,
    val taxonId: Long,
    val scientificName: String,
    val lat: Double,
    val lon: Double,
    val obsUrl: String,
)

/** Result returned by the repository — both list and pins, plus fetch timestamp. */
data class CachedResult(
    val species: List<SpeciesAggregate>,
    val pins: List<MapPin>,
    val fetchedAtUtcMs: Long,
)

/** What the user picked: filter selections + current location. */
data class FilterState(
    val radiusKm: Int,
    val periodDays: Int,
    val taxa: Set<String>,
    val userLocation: GeoPoint?,
)

/** Status of the location subsystem at the moment the screen is rendering. */
sealed interface LocationStatus {
    data object Loading : LocationStatus
    data class Live(val accuracyM: Float) : LocationStatus
    data object FallbackToLastKnown : LocationStatus
    data object NoLocation : LocationStatus
}

/** Compose-side state: everything `RadarScreen` reads from the VM. */
data class RadarUiState(
    val filter: FilterState,
    val species: List<SpeciesAggregate> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val locationStatus: LocationStatus = LocationStatus.Loading,
)
```

- [ ] **Step 6: Build and commit**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
git add app/src/main/java/com/sound2inat/inat/Geo.kt \
        app/src/test/java/com/sound2inat/inat/GeoTest.kt \
        app/src/main/java/com/sound2inat/app/ui/radar/RadarUiState.kt
git commit -m "feat(radar): add Haversine helper and ui state types

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

Expected: tests green, commit lands.

---

## Task 4: `INaturalistClient.nearbySpeciesCounts`

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- Test: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`

- [ ] **Step 1: Write the URL-composition test**

Append in `INaturalistClientTest.kt` before the closing brace:

```kotlin
@Test fun `nearbySpeciesCounts URL has all params and parses response`() = runTest {
    server.enqueue(
        MockResponse().setBody(
            """{
              "results": [
                {
                  "count": 12,
                  "taxon": {
                    "id": 9083,
                    "name": "Regulus regulus",
                    "preferred_common_name": "Goldcrest",
                    "iconic_taxon_name": "Aves",
                    "default_photo": { "medium_url": "https://example/goldcrest.jpg" }
                  }
                },
                {
                  "count": 3,
                  "taxon": {
                    "id": 1234,
                    "name": "Rana temporaria",
                    "preferred_common_name": null,
                    "iconic_taxon_name": "Amphibia",
                    "default_photo": null
                  }
                }
              ]
            }""",
        ),
    )
    val key = com.sound2inat.app.ui.radar.FilterKey(
        latGrid = 5050, lonGrid = 1010,
        radiusKm = 5, periodDays = 7,
        taxa = setOf("Aves", "Amphibia"),
        excludeUserId = 42L,
    )
    val results = client.nearbySpeciesCounts(key, periodEndDateUtc = "2026-04-25")

    assertThat(results).hasSize(2)
    val first = results[0]
    assertThat(first.taxonId).isEqualTo(9083L)
    assertThat(first.scientificName).isEqualTo("Regulus regulus")
    assertThat(first.commonName).isEqualTo("Goldcrest")
    assertThat(first.iconicTaxon).isEqualTo("Aves")
    assertThat(first.photoUrl).isEqualTo("https://example/goldcrest.jpg")
    assertThat(first.observationCount).isEqualTo(12)
    // nearestObservationKm and nearestObservationUrl are populated by the
    // repository's join step; the client itself returns sentinel values.
    assertThat(first.nearestObservationKm).isEqualTo(-1f)
    assertThat(first.nearestObservationUrl).isEqualTo("https://www.inaturalist.org/taxa/9083")

    val req = server.takeRequest()
    val path = req.path!!
    assertThat(path).startsWith("/v1/observations/species_counts?")
    assertThat(path).contains("lat=50.5")
    assertThat(path).contains("lng=10.1")
    assertThat(path).contains("radius=5")
    assertThat(path).contains("d1=2026-04-25")
    assertThat(path).contains("iconic_taxa=")
    // The set order is non-deterministic, so the test asserts both items
    // are present rather than a specific order.
    assertThat(path).matches(".*iconic_taxa=(Aves%2CAmphibia|Amphibia%2CAves).*")
    assertThat(path).contains("not_user_id=42")
    assertThat(path).contains("quality_grade=research%2Cneeds_id")
    assertThat(path).contains("per_page=100")
    assertThat(path).contains("order_by=count")
}

@Test fun `nearbySpeciesCounts omits empty taxa and null user_id`() = runTest {
    server.enqueue(MockResponse().setBody("""{"results":[]}"""))
    val key = com.sound2inat.app.ui.radar.FilterKey(
        latGrid = 5050, lonGrid = 1010,
        radiusKm = 25, periodDays = 30,
        taxa = emptySet(),
        excludeUserId = null,
    )
    client.nearbySpeciesCounts(key, periodEndDateUtc = "2026-04-02")
    val path = server.takeRequest().path!!
    assertThat(path).doesNotContain("iconic_taxa=")
    assertThat(path).doesNotContain("not_user_id=")
}
```

- [ ] **Step 2: Run tests, confirm they fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INaturalistClientTest.*nearbySpeciesCounts*'
```

Expected: FAIL with `Unresolved reference: nearbySpeciesCounts`.

- [ ] **Step 3: Implement `nearbySpeciesCounts` and the helper that builds the shared query string**

In `INaturalistClient.kt`, add (just before the existing `private suspend fun executeJson` helpers — the order in the file does not matter, but grouping keeps related code together):

```kotlin
import com.sound2inat.app.ui.radar.FilterKey
import com.sound2inat.app.ui.radar.SpeciesAggregate

/**
 * Returns the species observed within the radar filter window, ranked
 * by `count` descending. The `nearestObservationKm` / `nearestObservationUrl`
 * fields come back populated with sentinel values (`-1f` and the public
 * taxon page URL); the repository fills them in after joining with the
 * parallel `/observations` response.
 */
suspend fun nearbySpeciesCounts(
    key: FilterKey,
    periodEndDateUtc: String,
): List<SpeciesAggregate> = withContext(ioDispatcher) {
    val path = buildString {
        append("/observations/species_counts?")
        appendRadarParams(key, periodEndDateUtc)
        append("&per_page=100&order=desc&order_by=count")
    }
    val req = anonGet(path)
    val results = executeJson(req).optJSONArray("results") ?: return@withContext emptyList()
    val out = ArrayList<SpeciesAggregate>(results.length())
    for (i in 0 until results.length()) {
        val entry = results.getJSONObject(i)
        val count = entry.optInt("count", 0)
        val taxon = entry.optJSONObject("taxon") ?: continue
        val taxonId = taxon.optLong("id", 0L).takeIf { it > 0 } ?: continue
        out += SpeciesAggregate(
            taxonId = taxonId,
            scientificName = taxon.optString("name", ""),
            commonName = taxon.optString("preferred_common_name", "").takeIf(String::isNotBlank),
            iconicTaxon = taxon.optString("iconic_taxon_name", "").ifBlank { "Unknown" },
            photoUrl = taxon.optJSONObject("default_photo")
                ?.optString("medium_url", "")?.takeIf(String::isNotBlank),
            observationCount = count,
            nearestObservationKm = -1f,
            nearestObservationUrl = "https://www.inaturalist.org/taxa/$taxonId",
        )
    }
    out
}

/** Appends the parameters shared by both `species_counts` and `observations`. */
private fun StringBuilder.appendRadarParams(key: FilterKey, periodEndDateUtc: String) {
    val lat = key.latGrid / 100.0
    val lng = key.lonGrid / 100.0
    append("lat=").append(lat)
    append("&lng=").append(lng)
    append("&radius=").append(key.radiusKm)
    append("&d1=").append(periodEndDateUtc)
    if (key.taxa.isNotEmpty()) {
        append("&iconic_taxa=")
        append(key.taxa.joinToString(",").let { java.net.URLEncoder.encode(it, "UTF-8") })
    }
    if (key.excludeUserId != null) {
        append("&not_user_id=").append(key.excludeUserId)
    }
    append("&quality_grade=")
    append(java.net.URLEncoder.encode("research,needs_id", "UTF-8"))
}
```

(The existing `anonGet`/`executeJson` private helpers remain unchanged.)

- [ ] **Step 4: Run the new tests, confirm they pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INaturalistClientTest'
```

Expected: all `INaturalistClientTest` PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INaturalistClient.kt \
        app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt
git commit -m "feat(inat): add nearbySpeciesCounts query for the radar

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `INaturalistClient.nearbyObservations`

**Files:**
- Modify: `app/src/main/java/com/sound2inat/inat/INaturalistClient.kt`
- Test: `app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt`

- [ ] **Step 1: Write failing tests**

Append in `INaturalistClientTest.kt`:

```kotlin
@Test fun `nearbyObservations URL has all params and parses response`() = runTest {
    server.enqueue(
        MockResponse().setBody(
            """{
              "results": [
                {
                  "id": 11122,
                  "uuid": "00000000-0000-0000-0000-00000000aaa1",
                  "taxon": { "id": 9083, "name": "Regulus regulus" },
                  "geojson": { "coordinates": [10.1, 50.5] }
                },
                {
                  "id": 22233,
                  "uuid": "00000000-0000-0000-0000-00000000aaa2",
                  "taxon": { "id": 1234, "name": "Rana temporaria" },
                  "geojson": { "coordinates": [10.105, 50.512] }
                },
                {
                  "id": 33344,
                  "uuid": "00000000-0000-0000-0000-00000000aaa3",
                  "taxon": null,
                  "geojson": { "coordinates": [10.11, 50.52] }
                }
              ]
            }""",
        ),
    )
    val key = com.sound2inat.app.ui.radar.FilterKey(
        latGrid = 5050, lonGrid = 1010,
        radiusKm = 5, periodDays = 7,
        taxa = setOf("Aves"),
        excludeUserId = null,
    )
    val pins = client.nearbyObservations(key, periodEndDateUtc = "2026-04-25")
    assertThat(pins).hasSize(2) // entry 3 has no taxon — skipped
    assertThat(pins[0].observationId).isEqualTo(11122L)
    assertThat(pins[0].taxonId).isEqualTo(9083L)
    assertThat(pins[0].lat).isEqualTo(50.5)
    assertThat(pins[0].lon).isEqualTo(10.1)
    assertThat(pins[0].obsUrl).isEqualTo(
        "https://www.inaturalist.org/observations/00000000-0000-0000-0000-00000000aaa1",
    )

    val path = server.takeRequest().path!!
    assertThat(path).startsWith("/v1/observations?")
    assertThat(path).contains("lat=50.5")
    assertThat(path).contains("lng=10.1")
    assertThat(path).contains("per_page=200")
    assertThat(path).contains("order_by=observed_on")
}
```

- [ ] **Step 2: Run, confirm fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INaturalistClientTest.*nearbyObservations*'
```

Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement**

In `INaturalistClient.kt`:

```kotlin
import com.sound2inat.app.ui.radar.MapPin

/**
 * Per-observation pins for the radar map. iNat caps `/observations` at
 * `per_page=200`, ordered by date descending — the radar accepts that
 * truncation since `species_counts` (the list source) is unaffected.
 */
suspend fun nearbyObservations(
    key: FilterKey,
    periodEndDateUtc: String,
): List<MapPin> = withContext(ioDispatcher) {
    val path = buildString {
        append("/observations?")
        appendRadarParams(key, periodEndDateUtc)
        append("&per_page=200&order=desc&order_by=observed_on")
    }
    val req = anonGet(path)
    val results = executeJson(req).optJSONArray("results") ?: return@withContext emptyList()
    val out = ArrayList<MapPin>(results.length())
    for (i in 0 until results.length()) {
        val entry = results.getJSONObject(i)
        val taxon = entry.optJSONObject("taxon") ?: continue
        val taxonId = taxon.optLong("id", 0L).takeIf { it > 0 } ?: continue
        val coords = entry.optJSONObject("geojson")?.optJSONArray("coordinates")
            ?: continue
        if (coords.length() < 2) continue
        val uuid = entry.optString("uuid", "").ifBlank {
            // Fallback to the legacy numeric URL if uuid is missing.
            entry.optLong("id", 0L).toString()
        }
        out += MapPin(
            observationId = entry.optLong("id", 0L),
            taxonId = taxonId,
            scientificName = taxon.optString("name", ""),
            lat = coords.optDouble(1, Double.NaN),
            lon = coords.optDouble(0, Double.NaN),
            obsUrl = "https://www.inaturalist.org/observations/$uuid",
        )
    }
    out.filter { it.lat.isFinite() && it.lon.isFinite() }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INaturalistClientTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INaturalistClient.kt \
        app/src/test/java/com/sound2inat/inat/INaturalistClientTest.kt
git commit -m "feat(inat): add nearbyObservations query for the radar map

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `INatObservationsRepository` — parallel fetch, cache, Haversine join

**Files:**
- Create: `app/src/main/java/com/sound2inat/inat/INatObservationsRepository.kt`
- Test: `app/src/test/java/com/sound2inat/inat/INatObservationsRepositoryTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/inat/INatObservationsRepositoryTest.kt`:

```kotlin
package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.ui.radar.CachedResult
import com.sound2inat.app.ui.radar.FilterKey
import com.sound2inat.app.ui.radar.MapPin
import com.sound2inat.app.ui.radar.SpeciesAggregate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class INatObservationsRepositoryTest {

    private val key = FilterKey(
        latGrid = 5050, lonGrid = 1010,
        radiusKm = 5, periodDays = 7,
        taxa = emptySet(),
        excludeUserId = null,
    )

    @Test fun `fetch hits both endpoints in parallel`() = runTest(UnconfinedTestDispatcher()) {
        var countsStartMs = 0L
        var countsEndMs = 0L
        var pinsStartMs = 0L
        var pinsEndMs = 0L
        val repo = INatObservationsRepository(
            countsLoader = {
                countsStartMs = System.nanoTime()
                delay(100)
                countsEndMs = System.nanoTime()
                emptyList()
            },
            pinsLoader = {
                pinsStartMs = System.nanoTime()
                delay(100)
                pinsEndMs = System.nanoTime()
                emptyList()
            },
            clock = { 0L },
        )
        repo.fetch(key)
        // If the calls were sequential, one would start strictly after the
        // other ended. Parallel ⇒ overlap.
        assertThat(pinsStartMs).isLessThan(countsEndMs)
        assertThat(countsStartMs).isLessThan(pinsEndMs)
    }

    @Test fun `fetch caches result for 15 minutes`() = runTest(UnconfinedTestDispatcher()) {
        var countsCalls = 0
        var pinsCalls = 0
        var now = 0L
        val repo = INatObservationsRepository(
            countsLoader = { countsCalls++; emptyList() },
            pinsLoader = { pinsCalls++; emptyList() },
            clock = { now },
        )
        repo.fetch(key)
        assertThat(countsCalls).isEqualTo(1)
        assertThat(pinsCalls).isEqualTo(1)

        // Advance clock by 14 min — still fresh.
        now = 14L * 60 * 1000
        repo.fetch(key)
        assertThat(countsCalls).isEqualTo(1)
        assertThat(pinsCalls).isEqualTo(1)

        // Advance to 16 min — expired.
        now = 16L * 60 * 1000
        repo.fetch(key)
        assertThat(countsCalls).isEqualTo(2)
        assertThat(pinsCalls).isEqualTo(2)
    }

    @Test fun `fetch with forceRefresh bypasses cache`() = runTest(UnconfinedTestDispatcher()) {
        var countsCalls = 0
        val repo = INatObservationsRepository(
            countsLoader = { countsCalls++; emptyList() },
            pinsLoader = { emptyList() },
            clock = { 0L },
        )
        repo.fetch(key)
        repo.fetch(key, forceRefresh = true)
        assertThat(countsCalls).isEqualTo(2)
    }

    @Test fun `fetch returns failure if loader throws`() = runTest(UnconfinedTestDispatcher()) {
        val repo = INatObservationsRepository(
            countsLoader = { error("boom") },
            pinsLoader = { emptyList() },
            clock = { 0L },
        )
        val result = repo.fetch(key)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("boom")
    }

    @Test fun `attachNearestFrom joins by taxon and picks nearest pin`() {
        val species = listOf(
            sample(taxonId = 9083, count = 5),
            sample(taxonId = 1234, count = 1),
        )
        val pins = listOf(
            pin(taxonId = 9083, lat = 50.501, lon = 10.101, id = 11),
            pin(taxonId = 9083, lat = 50.510, lon = 10.110, id = 12),
            pin(taxonId = 1234, lat = 50.520, lon = 10.120, id = 13),
        )
        val joined = species.attachNearestFrom(userLat = 50.500, userLon = 10.100, pins = pins)
        // Goldcrest (9083) — nearest is pin 11.
        val gold = joined.first { it.taxonId == 9083L }
        assertThat(gold.nearestObservationUrl).contains("11")
        assertThat(gold.nearestObservationKm).isLessThan(0.5f)
        // Frog (1234) — only one pin.
        val frog = joined.first { it.taxonId == 1234L }
        assertThat(frog.nearestObservationUrl).contains("13")
    }

    @Test fun `attachNearestFrom keeps fallback URL when no pin matches`() {
        val species = listOf(sample(taxonId = 9083, count = 5))
        val joined = species.attachNearestFrom(userLat = 50.5, userLon = 10.1, pins = emptyList())
        val it = joined.first()
        assertThat(it.nearestObservationKm).isEqualTo(-1f)
        assertThat(it.nearestObservationUrl).isEqualTo("https://www.inaturalist.org/taxa/9083")
    }

    private fun sample(taxonId: Long, count: Int) = SpeciesAggregate(
        taxonId = taxonId,
        scientificName = "Sci",
        commonName = null,
        iconicTaxon = "Aves",
        photoUrl = null,
        observationCount = count,
        nearestObservationKm = -1f,
        nearestObservationUrl = "https://www.inaturalist.org/taxa/$taxonId",
    )

    private fun pin(taxonId: Long, lat: Double, lon: Double, id: Long) = MapPin(
        observationId = id,
        taxonId = taxonId,
        scientificName = "Sci",
        lat = lat,
        lon = lon,
        obsUrl = "https://www.inaturalist.org/observations/$id",
    )
}
```

- [ ] **Step 2: Run, confirm fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INatObservationsRepositoryTest'
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement the repository**

Create `app/src/main/java/com/sound2inat/inat/INatObservationsRepository.kt`:

```kotlin
package com.sound2inat.inat

import com.sound2inat.app.ui.radar.CachedResult
import com.sound2inat.app.ui.radar.FilterKey
import com.sound2inat.app.ui.radar.MapPin
import com.sound2inat.app.ui.radar.SpeciesAggregate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and caches the radar's two parallel iNat queries. Public entry point
 * is [fetch]; the result is cached for [TTL_MS] ms, keyed by the full
 * [FilterKey]. `forceRefresh = true` bypasses the cache.
 *
 * The `loader` constructor parameters exist so unit tests can swap in fakes
 * without spinning up a `MockWebServer` — the production binding (provided by
 * Hilt in `AppModule.provideINatObservationsRepository`) wires them to
 * [INaturalistClient.nearbySpeciesCounts] / [INaturalistClient.nearbyObservations].
 */
@Singleton
class INatObservationsRepository internal constructor(
    private val countsLoader: suspend (FilterKey) -> List<SpeciesAggregate>,
    private val pinsLoader: suspend (FilterKey) -> List<MapPin>,
    private val clock: () -> Long,
) {
    @Inject constructor(client: INaturalistClient) : this(
        countsLoader = { key ->
            client.nearbySpeciesCounts(key, periodEndDateUtc = isoDate(now() - key.periodDays.daysAsMs()))
        },
        pinsLoader = { key ->
            client.nearbyObservations(key, periodEndDateUtc = isoDate(now() - key.periodDays.daysAsMs()))
        },
        clock = ::now,
    )

    private val cache = ConcurrentHashMap<FilterKey, CachedResult>()

    suspend fun fetch(key: FilterKey, forceRefresh: Boolean = false): Result<CachedResult> {
        if (!forceRefresh) {
            cache[key]?.takeIf { isFresh(it) }?.let { return Result.success(it) }
        }
        return runCatching {
            coroutineScope {
                val countsDeferred = async { countsLoader(key) }
                val pinsDeferred = async { pinsLoader(key) }
                val raw = countsDeferred.await()
                val pins = pinsDeferred.await()
                val species = raw.attachNearestFrom(
                    userLat = key.latGrid / 100.0,
                    userLon = key.lonGrid / 100.0,
                    pins = pins,
                )
                CachedResult(species, pins, clock()).also { cache[key] = it }
            }
        }
    }

    fun invalidate() {
        cache.clear()
    }

    private fun isFresh(c: CachedResult): Boolean = clock() - c.fetchedAtUtcMs < TTL_MS

    internal companion object {
        const val TTL_MS = 15L * 60 * 1000

        private fun now() = System.currentTimeMillis()
        private fun Int.daysAsMs() = this * 24L * 60 * 60 * 1000
        private fun isoDate(epochMs: Long): String =
            LocalDate.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
}

/**
 * Joins the parallel `species_counts` and `observations` responses by
 * `taxon_id`. For each species, picks the geographically nearest pin and
 * writes its distance + observation URL onto the [SpeciesAggregate]. Species
 * not represented in the `pins` sample (capped at 200) keep the sentinel
 * distance (`-1f`) and the public taxon-page URL.
 *
 * Result is sorted by ascending distance; species with no nearby pin sink
 * to the bottom (sentinel `-1f` is treated as "infinitely far").
 */
internal fun List<SpeciesAggregate>.attachNearestFrom(
    userLat: Double,
    userLon: Double,
    pins: List<MapPin>,
): List<SpeciesAggregate> {
    val byTaxon: Map<Long, List<MapPin>> = pins.groupBy { it.taxonId }
    return map { sp ->
        val candidates = byTaxon[sp.taxonId] ?: return@map sp
        val nearest = candidates.minByOrNull { p -> haversineKm(userLat, userLon, p.lat, p.lon) }
            ?: return@map sp
        sp.copy(
            nearestObservationKm = haversineKm(userLat, userLon, nearest.lat, nearest.lon),
            nearestObservationUrl = nearest.obsUrl,
        )
    }.sortedBy { sp ->
        if (sp.nearestObservationKm < 0f) Float.MAX_VALUE else sp.nearestObservationKm
    }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.inat.INatObservationsRepositoryTest'
```

Expected: 6/6 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/inat/INatObservationsRepository.kt \
        app/src/test/java/com/sound2inat/inat/INatObservationsRepositoryTest.kt
git commit -m "feat(inat): add observations repository with parallel fetch + cache

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `RadarViewModel` — debounce, location fallback, error states

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/RadarViewModel.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/radar/RadarViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/app/ui/radar/RadarViewModelTest.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.osmdroid.util.GeoPoint

@OptIn(ExperimentalCoroutinesApi::class)
class RadarViewModelTest {

    @Test fun `filter change triggers single fetch after debounce`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val radius = MutableStateFlow(5)
            val period = MutableStateFlow(7)
            val taxa = MutableStateFlow<Set<String>>(emptySet())
            val vm = newVm(fakeRepo, radius, period, taxa)

            radius.value = 25
            advanceTimeBy(150)
            radius.value = 100
            advanceTimeBy(400)

            // Three filter emissions in <300ms collapse to one fetch (the last).
            assertThat(fakeRepo.calls.map { it.radiusKm }).containsExactly(100).inOrder()
        }

    @Test fun `pullRefresh forces cache bypass`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRepo = FakeRepo()
        val vm = newVm(fakeRepo)
        advanceTimeBy(400)
        assertThat(fakeRepo.calls).hasSize(1)
        assertThat(fakeRepo.lastForce).isFalse()

        vm.pullRefresh()
        advanceTimeBy(50)
        assertThat(fakeRepo.calls).hasSize(2)
        assertThat(fakeRepo.lastForce).isTrue()
    }

    @Test fun `permission denied falls back to lastKnown`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val location = object : LocationProvider {
                override suspend fun getCurrent(timeoutMs: Long): Fix? = null
            }
            val vm = newVm(
                fakeRepo,
                location = location,
                lastKnownLat = 50.0,
                lastKnownLon = 10.0,
            )
            advanceTimeBy(400)
            assertThat(fakeRepo.calls).hasSize(1)
            assertThat(fakeRepo.calls[0].latGrid).isEqualTo(5000)
            assertThat(fakeRepo.calls[0].lonGrid).isEqualTo(1000)
        }

    @Test fun `no location at all sets NoLocation state`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val location = object : LocationProvider {
                override suspend fun getCurrent(timeoutMs: Long): Fix? = null
            }
            val vm = newVm(
                fakeRepo,
                location = location,
                lastKnownLat = null,
                lastKnownLon = null,
            )
            advanceTimeBy(400)
            assertThat(fakeRepo.calls).isEmpty()
            assertThat(vm.state.value.locationStatus).isEqualTo(LocationStatus.NoLocation)
        }

    @Test fun `repo failure surfaces error in state`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo(throwing = "boom")
            val vm = newVm(fakeRepo)
            advanceTimeBy(400)
            assertThat(vm.state.value.error).isEqualTo("boom")
            assertThat(vm.state.value.loading).isFalse()
        }

    @Test fun `same FilterKey emitted twice does not double-fetch`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakeRepo = FakeRepo()
            val radius = MutableStateFlow(5)
            val vm = newVm(fakeRepo, radius)
            advanceTimeBy(400)
            radius.value = 5 // identical
            advanceTimeBy(400)
            assertThat(fakeRepo.calls).hasSize(1)
        }

    private fun newVm(
        repo: FakeRepo,
        radius: MutableStateFlow<Int> = MutableStateFlow(5),
        period: MutableStateFlow<Int> = MutableStateFlow(7),
        taxa: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        location: LocationProvider = object : LocationProvider {
            override suspend fun getCurrent(timeoutMs: Long) =
                Fix(50.5, 10.1, 5f, 0L)
        },
        lastKnownLat: Double? = null,
        lastKnownLon: Double? = null,
    ): RadarViewModel = RadarViewModel(
        repoFetch = repo::fetch,
        radarRadiusKm = radius,
        radarPeriodDays = period,
        radarTaxa = taxa,
        getLastKnown = { lastKnownLat to lastKnownLon },
        getLocation = { location.getCurrent() },
        userId = { null },
        externalScope = backgroundScope,
    )

    private class FakeRepo(private val throwing: String? = null) {
        val calls = mutableListOf<FilterKey>()
        var lastForce: Boolean = false
        suspend fun fetch(key: FilterKey, force: Boolean): Result<CachedResult> {
            calls += key
            lastForce = force
            return if (throwing != null) Result.failure(RuntimeException(throwing))
            else Result.success(CachedResult(emptyList(), emptyList(), 0L))
        }
    }
}
```

This deliberately uses a constructor with lambda parameters (`repoFetch`, `getLastKnown`, `getLocation`, `userId`) so the VM is testable on the JVM without Hilt. The Hilt wrapper (next task) plugs in the production lambdas.

- [ ] **Step 2: Run, confirm fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.app.ui.radar.RadarViewModelTest'
```

Expected: FAIL — `RadarViewModel` not found.

- [ ] **Step 3: Implement `RadarViewModel`**

Create `app/src/main/java/com/sound2inat/app/ui/radar/RadarViewModel.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt
import com.sound2inat.location.Fix

/**
 * View-model for the Radar tab. Watches filter Settings + location, emits
 * [RadarUiState], and triggers fetches via the supplied [repoFetch] lambda.
 *
 * The constructor parameters are deliberately collaborator-shaped (lambdas /
 * Flows / suppliers) instead of "real" Hilt-injected types: this makes the
 * VM unit-testable on the JVM with no Android dependencies. The Hilt
 * wrapper [RadarViewModelHilt] plugs in production implementations.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RadarViewModel(
    private val repoFetch: suspend (FilterKey, Boolean) -> Result<CachedResult>,
    radarRadiusKm: Flow<Int>,
    radarPeriodDays: Flow<Int>,
    radarTaxa: Flow<Set<String>>,
    private val getLastKnown: suspend () -> Pair<Double?, Double?>,
    private val getLocation: suspend () -> Fix?,
    private val userId: () -> Long?,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope = externalScope ?: viewModelScope

    private val _state = MutableStateFlow(
        RadarUiState(filter = FilterState(5, 7, emptySet(), null)),
    )
    val state: StateFlow<RadarUiState> = _state

    private val locationFlow = MutableStateFlow<GeoPoint?>(null)

    init {
        scope.launch { primeLocation() }
        scope.launch {
            combine(
                radarRadiusKm,
                radarPeriodDays,
                radarTaxa,
                locationFlow,
            ) { r, p, t, loc -> FilterState(r, p, t, loc) }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { fs -> refreshFor(fs, force = false) }
        }
    }

    fun pullRefresh() {
        if (_state.value.loading) return
        scope.launch { refreshFor(_state.value.filter, force = true) }
    }

    private suspend fun primeLocation() {
        val (lat, lon) = getLastKnown()
        if (lat != null && lon != null) {
            locationFlow.value = GeoPoint(lat, lon)
            _state.update { it.copy(locationStatus = LocationStatus.FallbackToLastKnown) }
        }
        val fix = runCatching { getLocation() }.getOrNull()
        if (fix != null) {
            locationFlow.value = GeoPoint(fix.latitude, fix.longitude)
            _state.update {
                it.copy(locationStatus = LocationStatus.Live(fix.accuracyMeters ?: 0f))
            }
        } else if (locationFlow.value == null) {
            _state.update { it.copy(locationStatus = LocationStatus.NoLocation) }
        }
    }

    private suspend fun refreshFor(fs: FilterState, force: Boolean) {
        _state.update { it.copy(filter = fs, loading = true) }
        val loc = fs.userLocation
        if (loc == null) {
            _state.update {
                it.copy(loading = false, locationStatus = LocationStatus.NoLocation)
            }
            return
        }
        val key = FilterKey(
            latGrid = (loc.latitude * 100).roundToInt(),
            lonGrid = (loc.longitude * 100).roundToInt(),
            radiusKm = fs.radiusKm,
            periodDays = fs.periodDays,
            taxa = fs.taxa,
            excludeUserId = userId(),
        )
        repoFetch(key, force).fold(
            onSuccess = { r ->
                _state.update {
                    it.copy(
                        loading = false,
                        species = r.species,
                        pins = r.pins,
                        error = null,
                    )
                }
            },
            onFailure = { e ->
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Fetch failed")
                }
            },
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.app.ui.radar.RadarViewModelTest'
```

Expected: 6/6 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/radar/RadarViewModel.kt \
        app/src/test/java/com/sound2inat/app/ui/radar/RadarViewModelTest.kt
git commit -m "feat(radar): viewmodel with debounce, location fallback, error surface

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Vector drawables for the 12 iconic taxa

**Files:**
- Create: `app/src/main/res/drawable/ic_iconic_aves.xml`
- Create: `app/src/main/res/drawable/ic_iconic_amphibia.xml`
- Create: `app/src/main/res/drawable/ic_iconic_reptilia.xml`
- Create: `app/src/main/res/drawable/ic_iconic_mammalia.xml`
- Create: `app/src/main/res/drawable/ic_iconic_actinopterygii.xml`
- Create: `app/src/main/res/drawable/ic_iconic_mollusca.xml`
- Create: `app/src/main/res/drawable/ic_iconic_arachnida.xml`
- Create: `app/src/main/res/drawable/ic_iconic_insecta.xml`
- Create: `app/src/main/res/drawable/ic_iconic_plantae.xml`
- Create: `app/src/main/res/drawable/ic_iconic_fungi.xml`
- Create: `app/src/main/res/drawable/ic_iconic_protozoa.xml`
- Create: `app/src/main/res/drawable/ic_iconic_unknown.xml`

This is the manual asset-extraction step. The iNat iconic-taxa SVG glyphs come from the [iNaturalist/inaturalist](https://github.com/inaturalist/inaturalist) GitHub repo (MIT-licensed) — specifically the icon font sources under `app/assets/stylesheets/iconic_taxa.scss` and the matching `.svg`s under `app/assets/images/iconic_taxa/`. Each SVG is converted to an Android Vector Drawable XML.

- [ ] **Step 1: Fetch the SVG sources**

```bash
mkdir -p /tmp/inat-icons
cd /tmp/inat-icons
for taxon in aves amphibia reptilia mammalia actinopterygii mollusca arachnida insecta plantae fungi protozoa unknown; do
  curl -fsSL -o "$taxon.svg" \
    "https://raw.githubusercontent.com/inaturalist/inaturalist/main/app/assets/images/iconic_taxa/$taxon.svg" \
    || echo "MISSING: $taxon"
done
ls -la
```

Expected: 12 `.svg` files appear. If any are missing, find the correct path under the iNat repo (search for `iconic_taxa` in `app/assets/`) or extract from the iconic-taxa webfont (`iconic_taxa.woff`) via [Fontello](https://fontello.com).

- [ ] **Step 2: Convert each SVG to an Android Vector Drawable**

Open Android Studio → right-click `app/src/main/res/drawable/` → New → Vector Asset → Local file → pick `/tmp/inat-icons/aves.svg` → name it `ic_iconic_aves` → set "Override" size to `24dp x 24dp` → tint colour `#FF000000` → Next → Finish. Repeat for all 12 files.

Alternatively, use the CLI tool [`vd-tool`](https://github.com/google/android-svg-tool) (ships inside Android Studio):
```bash
~/Library/Android/sdk/cmdline-tools/latest/bin/vd-tool -c -in /tmp/inat-icons -out app/src/main/res/drawable
```

After conversion, double-check each XML opens with the Vector Asset viewer in Android Studio without warnings. iNat glyphs are pure single-path silhouettes, so the conversion is reliable.

- [ ] **Step 3: Verify each XML compiles**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Resource compilation errors here usually mean a path attribute (`android:pathData`) is malformed — re-export from Vector Asset Studio.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_iconic_*.xml
git commit -m "feat(radar): bundle iNat iconic-taxa vector drawables

12 vector drawables matching iNaturalist's canonical iconic taxa icons,
extracted from the upstream MIT-licensed SVGs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `IconicTaxa` mapping module

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/IconicTaxa.kt`
- Test: `app/src/test/java/com/sound2inat/app/ui/radar/IconicTaxaTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/sound2inat/app/ui/radar/IconicTaxaTest.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IconicTaxaTest {
    @Test fun `FilterableIconicTaxa contains 11 taxa with stable order`() {
        assertThat(FilterableIconicTaxa).hasSize(11)
        assertThat(FilterableIconicTaxa.first().id).isEqualTo("Aves")
        assertThat(FilterableIconicTaxa.last().id).isEqualTo("Protozoa")
        assertThat(FilterableIconicTaxa.none { it.id == "Unknown" }).isTrue()
    }

    @Test fun `iconicTaxonForName returns FilterableIconicTaxa entry by id`() {
        val aves = iconicTaxonForName("Aves")
        assertThat(aves.id).isEqualTo("Aves")
        assertThat(aves.label).isEqualTo("Birds")
    }

    @Test fun `iconicTaxonForName falls back to Unknown for null or blank`() {
        assertThat(iconicTaxonForName(null).id).isEqualTo("Unknown")
        assertThat(iconicTaxonForName("").id).isEqualTo("Unknown")
        assertThat(iconicTaxonForName("Animalia").id).isEqualTo("Unknown")
    }
}
```

- [ ] **Step 2: Run, confirm fail**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.app.ui.radar.IconicTaxaTest'
```

Expected: FAIL — symbols missing.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/sound2inat/app/ui/radar/IconicTaxa.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import androidx.annotation.DrawableRes
import com.sound2inat.R

/**
 * iNat iconic-taxon descriptor. The [id] matches the string iNat returns
 * in `iconic_taxon_name`, so mapping API → icon is a direct lookup.
 */
data class IconicTaxon(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
)

/**
 * Eleven taxa filterable via iNat's `iconic_taxa` query parameter. "Unknown"
 * is intentionally not in this list because iNat has no `iconic_taxa=Unknown`
 * value — observations without an identification simply have a null
 * `iconic_taxon_name`.
 */
internal val FilterableIconicTaxa: List<IconicTaxon> = listOf(
    IconicTaxon("Aves", "Birds", R.drawable.ic_iconic_aves),
    IconicTaxon("Amphibia", "Amphibians", R.drawable.ic_iconic_amphibia),
    IconicTaxon("Reptilia", "Reptiles", R.drawable.ic_iconic_reptilia),
    IconicTaxon("Mammalia", "Mammals", R.drawable.ic_iconic_mammalia),
    IconicTaxon("Actinopterygii", "Fish", R.drawable.ic_iconic_actinopterygii),
    IconicTaxon("Mollusca", "Molluscs", R.drawable.ic_iconic_mollusca),
    IconicTaxon("Arachnida", "Arachnids", R.drawable.ic_iconic_arachnida),
    IconicTaxon("Insecta", "Insects", R.drawable.ic_iconic_insecta),
    IconicTaxon("Plantae", "Plants", R.drawable.ic_iconic_plantae),
    IconicTaxon("Fungi", "Fungi", R.drawable.ic_iconic_fungi),
    IconicTaxon("Protozoa", "Protozoa", R.drawable.ic_iconic_protozoa),
)

/** Used for unidentified rows / pins (no `iconic_taxon_name` from iNat). */
internal val UnknownIconicTaxon =
    IconicTaxon("Unknown", "Unknown", R.drawable.ic_iconic_unknown)

private val byId: Map<String, IconicTaxon> =
    (FilterableIconicTaxa + UnknownIconicTaxon).associateBy { it.id }

/** Resolve an iNat `iconic_taxon_name` (or null) to the bundled icon descriptor. */
internal fun iconicTaxonForName(name: String?): IconicTaxon =
    byId[name?.takeIf(String::isNotBlank).orEmpty()] ?: UnknownIconicTaxon
```

- [ ] **Step 4: Run, confirm pass**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --tests 'com.sound2inat.app.ui.radar.IconicTaxaTest'
```

Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/radar/IconicTaxa.kt \
        app/src/test/java/com/sound2inat/app/ui/radar/IconicTaxaTest.kt
git commit -m "feat(radar): icon mapping for the 11 filterable iconic taxa + Unknown

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: `openCustomTab` utility + `RadarFilterBar` + `SpeciesCountRow`

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/CustomTab.kt`
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/RadarFilterBar.kt`
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/SpeciesCountRow.kt`

This task introduces the stateless UI building blocks that `RadarScreen` composes. They have no VM/repo dependencies, so they are tested manually via the screen — no JVM-unit tests here per the spec.

- [ ] **Step 1: Custom Tab utility**

Create `app/src/main/java/com/sound2inat/app/ui/radar/CustomTab.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens [url] in a Chrome Custom Tab, with no toolbar customisation. */
internal fun Context.openCustomTab(url: String) {
    CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
}
```

- [ ] **Step 2: `RadarFilterBar`**

Create `app/src/main/java/com/sound2inat/app/ui/radar/RadarFilterBar.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private val RADIUS_OPTIONS = listOf(1, 5, 25, 100)
private val PERIOD_OPTIONS = listOf(1, 7, 30)

/**
 * Three horizontal chip rows: Radius (single-select), Period (single-select),
 * Groups (multi-select over [FilterableIconicTaxa]). Stateless — the host
 * passes `filter` plus three callbacks.
 */
@Composable
internal fun RadarFilterBar(
    filter: FilterState,
    onRadiusChange: (Int) -> Unit,
    onPeriodChange: (Int) -> Unit,
    onTaxaToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        ChipRow(label = "Radius") {
            RADIUS_OPTIONS.forEach { km ->
                FilterChip(
                    selected = filter.radiusKm == km,
                    onClick = { onRadiusChange(km) },
                    label = { Text("$km km") },
                )
            }
        }
        ChipRow(label = "Period") {
            PERIOD_OPTIONS.forEach { d ->
                FilterChip(
                    selected = filter.periodDays == d,
                    onClick = { onPeriodChange(d) },
                    label = { Text(periodLabel(d)) },
                )
            }
        }
        ChipRow(label = "Groups") {
            FilterableIconicTaxa.forEach { t ->
                FilterChip(
                    selected = t.id in filter.taxa,
                    onClick = { onTaxaToggle(t.id) },
                    leadingIcon = {
                        Icon(painterResource(t.icon), contentDescription = null)
                    },
                    label = { Text(t.label) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

private fun periodLabel(days: Int): String = when (days) {
    1 -> "Day"
    7 -> "Week"
    30 -> "Month"
    else -> "$days d"
}
```

- [ ] **Step 3: `SpeciesCountRow`**

Create `app/src/main/java/com/sound2inat/app/ui/radar/SpeciesCountRow.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
internal fun SpeciesCountRow(
    row: SpeciesAggregate,
    onTap: (SpeciesAggregate) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable { onTap(row) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (row.photoUrl != null) {
                    AsyncImage(
                        model = row.photoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(iconicTaxonForName(row.iconicTaxon).icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        headlineContent = {
            Text(row.commonName ?: row.scientificName)
        },
        supportingContent = {
            Column {
                Text(supportingLine(row), style = MaterialTheme.typography.bodySmall)
                Text(
                    row.scientificName,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                )
            }
        },
    )
}

private fun supportingLine(row: SpeciesAggregate): String {
    val nearest = if (row.nearestObservationKm < 0f) "—"
    else "%.1f km".format(row.nearestObservationKm)
    return "${row.observationCount} obs · nearest $nearest"
}
```

- [ ] **Step 4: Build to verify all references resolve**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (Composables are not unit-tested here per the spec; the screen-level smoke test in Task 14 covers them.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/radar/CustomTab.kt \
        app/src/main/java/com/sound2inat/app/ui/radar/RadarFilterBar.kt \
        app/src/main/java/com/sound2inat/app/ui/radar/SpeciesCountRow.kt
git commit -m "feat(radar): filter chip rows + species list row + custom-tab helper

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: `RadarMap` (OSMDroid AndroidView)

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/RadarMap.kt`
- Modify: `app/src/main/java/com/sound2inat/Sound2iNatApp.kt` (initialise OSMDroid `Configuration` once at app start)

OSMDroid requires a one-time configuration call (`Configuration.getInstance().userAgentValue = packageName`) before the first `MapView` is constructed; if you skip it, OSMDroid bombards the OSM tile servers with a default UA and gets banned. Initialise it in `Sound2iNatApp.onCreate()`.

- [ ] **Step 1: Initialise OSMDroid in the Application class**

Modify `app/src/main/java/com/sound2inat/Sound2iNatApp.kt`. After the existing class declaration (and any inherited `onCreate`), override `onCreate`:

```kotlin
package com.sound2inat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class Sound2iNatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
    }
}
```

(If the existing class already overrides `onCreate`, append the OSMDroid line; do not replace the file's other contents.)

- [ ] **Step 2: Create `RadarMap`**

Create `app/src/main/java/com/sound2inat/app/ui/radar/RadarMap.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Compose-friendly wrapper around an OSMDroid [MapView]. Renders one marker
 * per supplied [pin] and (when [userLocation] is non-null) a centred "you
 * are here" marker. Tapping a pin invokes [onPinTap] with the iNat URL —
 * `RadarScreen` forwards that to a Custom Tab.
 *
 * The `update` lambda is the source of truth for marker state: it removes
 * every overlay we previously added and re-adds the current set, which keeps
 * the implementation trivial at the cost of recreating Marker instances on
 * each recomposition. With ≤200 pins the cost is negligible.
 */
@Composable
internal fun RadarMap(
    pins: List<MapPin>,
    userLocation: GeoPoint?,
    onPinTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(13.0)
        }
    }
    AndroidView(
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            userLocation?.let { gp ->
                map.controller.setCenter(gp)
                map.overlays += Marker(map).apply {
                    position = gp
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You"
                }
            }
            for (p in pins) {
                map.overlays += Marker(map).apply {
                    position = GeoPoint(p.lat, p.lon)
                    title = p.scientificName
                    setOnMarkerClickListener { _, _ ->
                        onPinTap(p.obsUrl)
                        true
                    }
                }
            }
            map.invalidate()
        },
        modifier = modifier,
    )

    LaunchedEffect(Unit) {
        // OSMDroid's MapView does not auto-handle Compose lifecycle; the
        // remembered instance is fine for the screen's lifetime, but we
        // must call onDetach when the composable leaves composition. The
        // factory creates the view exactly once, so DisposableEffect would
        // need the same MapView — kept simple here.
    }
}
```

- [ ] **Step 3: Build**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/radar/RadarMap.kt \
        app/src/main/java/com/sound2inat/Sound2iNatApp.kt
git commit -m "feat(radar): OSMDroid map view with user pin and per-observation markers

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: `RadarScreen` + Hilt VM wrapper + DI bindings

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/ui/radar/RadarScreen.kt`
- Modify: `app/src/main/java/com/sound2inat/app/ui/radar/RadarViewModel.kt` (append `RadarViewModelHilt`)
- Modify: `app/src/main/java/com/sound2inat/app/di/AppModule.kt` (provide `LocationProvider` for Hilt — verify it already does so via `SwappableModule`; otherwise extend)

- [ ] **Step 1: Verify `LocationProvider` is Hilt-injectable**

```bash
grep -n "LocationProvider" app/src/main/java/com/sound2inat/app/di/SwappableModule.kt
```

Expected: a `@Provides` for `LocationProvider` exists. If yes, skip Step 2. If not, add it in Step 2.

- [ ] **Step 2 (only if Step 1 found nothing): Add the Hilt binding**

Append in `app/src/main/java/com/sound2inat/app/di/SwappableModule.kt` (matching the existing `@Provides` style):

```kotlin
@Provides
@Singleton
fun provideLocationProvider(@ApplicationContext ctx: Context): LocationProvider =
    FusedLocationProvider(ctx)
```

Add the imports for `LocationProvider`, `FusedLocationProvider`, `ApplicationContext` if missing. If Step 1 already showed an existing binding, skip this step entirely.

- [ ] **Step 3: Append `RadarViewModelHilt` at the bottom of `RadarViewModel.kt`**

Append after the existing `RadarViewModel` class (and before the file's final newline):

```kotlin
import androidx.lifecycle.SavedStateHandle
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.INatObservationsRepository
import com.sound2inat.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RadarViewModelHilt @Inject constructor(
    private val repo: INatObservationsRepository,
    private val auth: INatAuthRepository,
    private val location: LocationProvider,
    private val settings: Settings,
    @Suppress("UNUSED_PARAMETER") savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val delegate: RadarViewModel = RadarViewModel(
        repoFetch = { key, force -> repo.fetch(key, force) },
        radarRadiusKm = settings.radarRadiusKm,
        radarPeriodDays = settings.radarPeriodDays,
        radarTaxa = settings.radarTaxa,
        getLastKnown = { settings.lastKnownLat.first() to settings.lastKnownLon.first() },
        getLocation = { location.getCurrent() },
        userId = { auth.userId },
    )

    fun setRadius(km: Int) = scope { settings.setRadarRadiusKm(km) }
    fun setPeriod(d: Int) = scope { settings.setRadarPeriodDays(d) }
    fun toggleTaxon(id: String) = scope {
        val cur = settings.radarTaxa.first()
        settings.setRadarTaxa(if (id in cur) cur - id else cur + id)
    }

    private fun scope(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
```

(Note: `viewModelScope` and `kotlinx.coroutines.flow.first` need imports — add if not already present in the file.)

- [ ] **Step 4: Build and confirm Hilt graph resolves**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. A Hilt missing-binding error here points to either `LocationProvider` or `INatObservationsRepository` not being provided — re-check Steps 1–3.

- [ ] **Step 5: Create `RadarScreen`**

Create `app/src/main/java/com/sound2inat/app/ui/radar/RadarScreen.kt`:

```kotlin
package com.sound2inat.app.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val MAP_HEIGHT_DP = 280

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(onOpenSettings: () -> Unit) {
    val vm: RadarViewModelHilt = hiltViewModel()
    val state by vm.delegate.state.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RadarMap(
                pins = state.pins,
                userLocation = state.filter.userLocation,
                onPinTap = { url -> ctx.openCustomTab(url) },
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT_DP.dp),
            )
            RadarFilterBar(
                filter = state.filter,
                onRadiusChange = vm::setRadius,
                onPeriodChange = vm::setPeriod,
                onTaxaToggle = vm::toggleTaxon,
            )
            PullToRefreshBox(
                isRefreshing = state.loading && state.species.isNotEmpty(),
                onRefresh = vm.delegate::pullRefresh,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                RadarBody(
                    state = state,
                    onRowTap = { row -> ctx.openCustomTab(row.nearestObservationUrl) },
                )
            }
        }
    }
}

@Composable
private fun RadarBody(
    state: RadarUiState,
    onRowTap: (SpeciesAggregate) -> Unit,
) {
    when {
        state.locationStatus == LocationStatus.NoLocation && state.species.isEmpty() -> {
            EmptyState(
                title = "Grant location to see what's around",
                detail = "WildEar uses your phone's GPS to query iNaturalist for " +
                    "observations within your chosen radius.",
            )
        }
        state.error != null -> {
            EmptyState(title = "Something went wrong", detail = state.error)
        }
        state.species.isEmpty() && state.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.species.isEmpty() -> {
            EmptyState(
                title = "No observations found",
                detail = "Try a wider radius or a longer period.",
            )
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.species, key = { it.taxonId }) { row ->
                    SpeciesCountRow(row = row, onTap = onRowTap)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
```

- [ ] **Step 6: Build to confirm everything compiles**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/radar/RadarScreen.kt \
        app/src/main/java/com/sound2inat/app/ui/radar/RadarViewModel.kt
# Add SwappableModule.kt only if Step 2 modified it.
git status
git commit -m "feat(radar): screen composition with Hilt VM wrapper

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Bottom-tab navigation refactor (`RootScaffold`)

**Files:**
- Create: `app/src/main/java/com/sound2inat/app/nav/RootScaffold.kt`
- Modify: `app/src/main/java/com/sound2inat/app/nav/Routes.kt`
- Modify: `app/src/main/java/com/sound2inat/app/nav/Sound2iNatNavHost.kt`
- Modify: `app/src/main/java/com/sound2inat/app/MainActivity.kt`

- [ ] **Step 1: Add the RADAR route**

In `app/src/main/java/com/sound2inat/app/nav/Routes.kt`, add:

```kotlin
const val RADAR = "radar"
```

(Place it next to the existing `HOME` constant — follow whatever order the existing constants use.)

- [ ] **Step 2: Refactor `Sound2iNatNavHost` to take `nav` and `padding`**

Replace the current contents of `app/src/main/java/com/sound2inat/app/nav/Sound2iNatNavHost.kt` with:

```kotlin
package com.sound2inat.app.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sound2inat.app.ui.home.HomeScreen
import com.sound2inat.app.ui.radar.RadarScreen
import com.sound2inat.app.ui.recording.RecordingScreen
import com.sound2inat.app.ui.review.ReviewScreen
import com.sound2inat.app.ui.settings.SettingsScreen

@Suppress("FunctionNaming")
@Composable
fun Sound2iNatNavHost(
    nav: NavHostController,
    padding: PaddingValues,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.padding(padding),
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onRecord = { nav.navigate(Routes.RECORDING) },
                onOpenDraft = { id -> nav.navigate(Routes.review(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RADAR) {
            RadarScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onDone = { id -> nav.navigate(Routes.review(id)) { popUpTo(Routes.HOME) } },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument("draftId") { type = NavType.StringType }),
        ) {
            ReviewScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
```

- [ ] **Step 3: Create `RootScaffold`**

Create `app/src/main/java/com/sound2inat/app/nav/RootScaffold.kt`:

```kotlin
package com.sound2inat.app.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Top-level scaffold owning the bottom navigation bar. The bar shows only
 * for the two top-level destinations [Routes.HOME] and [Routes.RADAR];
 * full-screen destinations (Recording / Review / Settings) hide it.
 */
@Suppress("FunctionNaming")
@Composable
fun RootScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.HOME, Routes.RADAR)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { nav.navigateToTab(Routes.HOME) },
                        icon = { Icon(Icons.Outlined.Mic, contentDescription = null) },
                        label = { Text("Recordings") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.RADAR,
                        onClick = { nav.navigateToTab(Routes.RADAR) },
                        icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                        label = { Text("Radar") },
                    )
                }
            }
        },
    ) { padding ->
        Sound2iNatNavHost(nav = nav, padding = padding)
    }
}

private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) {
            saveState = true
            inclusive = false
        }
        launchSingleTop = true
        restoreState = true
    }
}
```

(`Icons.Outlined.Public` is the closest stock Material icon to a "radar/world" idea; replace with a custom drawable later if desired.)

- [ ] **Step 4: Switch `MainActivity` to `RootScaffold`**

In `app/src/main/java/com/sound2inat/app/MainActivity.kt`, find the line that calls `Sound2iNatNavHost()` inside `setContent { ... Sound2iNatTheme { ... } }` and replace it with `RootScaffold()`. Update the import accordingly:

Before:
```kotlin
import com.sound2inat.app.nav.Sound2iNatNavHost
…
Sound2iNatNavHost()
```

After:
```kotlin
import com.sound2inat.app.nav.RootScaffold
…
RootScaffold()
```

- [ ] **Step 5: Build, run all unit tests, install**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL`, all unit tests green, app installs.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/nav/Routes.kt \
        app/src/main/java/com/sound2inat/app/nav/Sound2iNatNavHost.kt \
        app/src/main/java/com/sound2inat/app/nav/RootScaffold.kt \
        app/src/main/java/com/sound2inat/app/MainActivity.kt
git commit -m "feat(nav): bottom tab bar with Recordings | Radar via RootScaffold

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Location permission flow + manual smoke test

**Files:**
- Modify: `app/src/main/java/com/sound2inat/app/ui/radar/RadarScreen.kt` (request `ACCESS_FINE_LOCATION` on first open)

Recording already uses `ACCESS_FINE_LOCATION`, so the permission is declared in the manifest. The Radar screen needs to actively request it on first open if not yet granted, then trigger a re-fetch once granted so the user sees results without restarting the app.

- [ ] **Step 1: Add the permission request to `RadarScreen`**

At the top of `RadarScreen` (after the `vm` and `state` lines), add:

```kotlin
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
…

val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
) { granted ->
    if (granted) vm.delegate.pullRefresh()
}
LaunchedEffect(Unit) {
    val granted = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
}
```

The `LaunchedEffect(Unit)` runs once on first composition; on re-entry the system reports the permission as granted and the launch is skipped.

- [ ] **Step 2: Build**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and run the manual smoke checklist**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sound2inat/.app.MainActivity
```

Manual checks (each ~30 s):

1. App launches on `Recordings` tab; bottom bar shows two items.
2. Tap `Radar` — bar highlights Radar, top bar reads "Nearby". On a fresh install the system permission prompt appears; grant it.
3. After granting, the loading indicator appears, then a list populates plus map markers around your current location.
4. Tap each radius chip (1, 5, 25, 100) — the list re-fetches; switching back to a previously-used radius within 15 min comes from cache (no spinner).
5. Tap Day / Week / Month — re-fetch; results plausibly differ.
6. Tap a Group chip (e.g. Birds); results filter; tap again to deselect.
7. Pull list down — spinner appears, then list re-renders with fresh data.
8. Tap a list row — Chrome Custom Tab opens on `inaturalist.org/observations/<uuid>`.
9. Tap a map marker — same Custom Tab behaviour.
10. Tap the gear icon top-right → Settings opens. Press back → returns to Radar with state preserved.
11. Switch tabs Recordings → Radar a few times; both keep their state (HomeScreen doesn't lose scroll, Radar doesn't re-fetch).
12. Toggle every taxon chip off — list shows "all groups" results (broader).
13. Turn airplane mode on, pull-to-refresh — error banner appears with iNat message; existing list/pins remain visible.

If any step fails, capture the failing behaviour in a follow-up commit (or revert to investigate).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sound2inat/app/ui/radar/RadarScreen.kt
git commit -m "feat(radar): request fine-location permission on first screen open

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: Final cleanup pass

**Files:**
- Verify the full test suite is green.
- Verify the APK installs and the smoke checklist (Task 14, Step 3) is fully clean.

- [ ] **Step 1: Run the entire test suite**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest
```

Expected: every test green (the existing test count plus 6 from Task 6 + 6 from Task 7 + 3 from Task 9 + 3 from Task 3 + 3 from Task 4 + 1 from Task 5 + 1 from Task 2 = ~23 new tests).

- [ ] **Step 2: Run the linter (Detekt — already part of the project)**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:detekt
```

Expected: no new violations. If Detekt flags `LongMethod` on `RadarScreen` or `RadarViewModel`, suppress narrowly via `@Suppress("LongMethod")` on the offender — do not split functions just to satisfy the linter.

- [ ] **Step 3: Verify the smoke checklist in Task 14 Step 3 still passes**

If anything regressed, file follow-up commits. The Radar feature is "shipped" once Steps 1, 2, and 3 are clean.

- [ ] **Step 4: Final commit only if anything was tweaked**

```bash
git status
# If clean, no commit needed.
git commit -am "chore(radar): suppress narrow detekt violations after smoke pass" || true
```

---

## Self-review

**1. Spec coverage:**

| Spec section / requirement | Task |
|---|---|
| OSMDroid + Custom Tabs deps | Task 1 |
| `Settings` keys: radarRadiusKm, radarPeriodDays, radarTaxa | Task 1 |
| `INatAuthRepository.userId` extraction | Task 2 |
| Haversine helper | Task 3 |
| Data types (FilterKey, CachedResult, SpeciesAggregate, MapPin, FilterState, RadarUiState, LocationStatus) | Task 3 |
| `INaturalistClient.nearbySpeciesCounts` | Task 4 |
| `INaturalistClient.nearbyObservations` | Task 5 |
| `INatObservationsRepository` (parallel fetch + cache + Haversine join) | Task 6 |
| `RadarViewModel` (debounce, location fallback, error states, pullRefresh) | Task 7 |
| 12 vector drawables for iconic taxa | Task 8 |
| `IconicTaxa` mapping table + `iconicTaxonForName` fallback | Task 9 |
| `openCustomTab` utility | Task 10 |
| `RadarFilterBar` (3 chip rows, single+multi select) | Task 10 |
| `SpeciesCountRow` | Task 10 |
| `RadarMap` (OSMDroid `AndroidView` + markers + tap) | Task 11 |
| `RadarScreen` + `RadarViewModelHilt` + DI | Task 12 |
| `Sound2iNatApp` OSMDroid `Configuration` init | Task 11 |
| Bottom-tab nav refactor (`RootScaffold` + Sound2iNatNavHost params + MainActivity + Routes.RADAR) | Task 13 |
| Location permission flow | Task 14 |
| All 14 unit tests listed in the spec | Tasks 2, 4, 5, 6, 7, 9 |
| Manual smoke checklist | Task 14 |
| Detekt clean | Task 15 |

Every spec section maps to at least one task.

**2. Placeholder scan:**

Searched for "TBD", "TODO", "implement later", "fill in", "Add appropriate", "Similar to". None found.

**3. Type consistency:**

- `FilterKey` defined in Task 3, used unchanged in Tasks 4, 5, 6, 7, 12.
- `CachedResult` defined in Task 3, used unchanged in Tasks 6, 7, 12.
- `SpeciesAggregate` / `MapPin` defined in Task 3, used unchanged downstream.
- `nearbySpeciesCounts(key, periodEndDateUtc)` signature: defined in Task 4, called consistently in Tasks 6 (via the Hilt constructor's `countsLoader` lambda).
- `nearbyObservations(key, periodEndDateUtc)` signature: defined in Task 5, called consistently in Task 6.
- `attachNearestFrom(userLat, userLon, pins)` defined in Task 6, used inside the same file.
- `RadarViewModel(repoFetch, radarRadiusKm, radarPeriodDays, radarTaxa, getLastKnown, getLocation, userId, externalScope)` constructor signature defined in Task 7, satisfied verbatim by `RadarViewModelHilt` in Task 12.
- `RadarScreen(onOpenSettings)` defined in Task 12, called from `Sound2iNatNavHost` in Task 13 with `onOpenSettings = { nav.navigate(Routes.SETTINGS) }`.
- `Routes.RADAR = "radar"` introduced in Task 13, referenced from `RootScaffold` in same task.

No naming drift.
