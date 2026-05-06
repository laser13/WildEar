# Radar (Nearby Observations) Design

**Date:** 2026-05-02
**Status:** Approved for implementation

## Goal

Add a second top-level tab "Radar" that shows iNaturalist observations
others have made near the user's current location: a list (one row per
species, with photo, count, distance) plus an interactive map with
per-observation pins. The user picks radius, time window, and which
taxonomic groups to include via filter chips. Tapping a row or pin opens
the observation page on iNaturalist in a Custom Tab. The user's own
observations are excluded.

## Non-Goals

- No in-app observation detail screen — taps go to iNaturalist web.
- No map of the user's own recordings (Home already covers that).
- No offline persistence — Radar requires fresh data.
- No marker clustering for MVP.
- No drag-to-resize map vs list split.
- No manual pin-on-map location selection — the radar follows GPS.

## Decisions Locked

The following choices were finalised in the brainstorming session and
drive the design below; they are not up for re-debate at the planning
or implementation stage:

| Topic | Decision |
|---|---|
| Navigation | Bottom tab bar: `Recordings` \| `Radar`; Settings stays as TopAppBar icon |
| MVP scope | Map (top, fixed 280dp height) + list (bottom) on the same screen |
| Filter groups | 11 of iNat's canonical iconic taxa as multi-select chips ("Unknown" is rendered for unidentified rows but not filterable — iNat API has no `iconic_taxa=Unknown` value) |
| Map library | OSMDroid 6.1.18 (no API key, OpenStreetMap tiles) |
| Time window | Configurable chips: Day / Week / Month |
| Radius | Independent setting from `regionRadiusKm`; on-screen chips 1/5/25/100 km |
| Observation tap | Opens `/observations/<uuid>` in a Chrome Custom Tab |
| Cache | In-memory `Map<FilterKey, CachedResult>` with 15 min TTL; pull-to-refresh forces bypass |
| GPS missing | Falls back to `lastKnownLat/Lon` from Settings; banner prompts to grant location |
| Own observations | Excluded via iNat `not_user_id=<my_user_id>` |
| Sort order | List: by distance ascending |
| Aggregation | One row per species (server-side via `species_counts`); pin per individual observation |
| Quality grade | `quality_grade=research,needs_id` (excludes only `casual`) |
| API endpoints | Parallel: `species_counts` for list + `observations` for pins |

## Architecture

### File layout

```
app/src/main/java/com/sound2inat/
├── inat/
│   ├── INaturalistClient.kt              [+] nearbySpeciesCounts(), nearbyObservations()
│   └── INatObservationsRepository.kt     [NEW] @Singleton; parallel fetch + 15min in-memory cache
├── app/
│   ├── data/
│   │   └── Settings.kt                   [+] radarRadiusKm, radarPeriodDays, radarTaxa
│   ├── nav/
│   │   ├── Routes.kt                     [+] Routes.RADAR
│   │   ├── RootScaffold.kt               [NEW] Scaffold(bottomBar=NavigationBar) wrapping NavHost
│   │   └── Sound2iNatNavHost.kt          [~] takes nav+padding params, adds RADAR composable
│   ├── MainActivity.kt                   [~] hosts RootScaffold instead of Sound2iNatNavHost directly
│   └── ui/radar/
│       ├── RadarUiState.kt               [NEW] SpeciesAggregate, MapPin, FilterState, RadarUiState
│       ├── RadarViewModel.kt             [NEW] VM + Hilt wrapper
│       ├── RadarScreen.kt                [NEW] Compose entry, Scaffold inside, list+map+filters
│       ├── RadarFilterBar.kt             [NEW] three chip rows: radius, period, taxa
│       ├── RadarMap.kt                   [NEW] AndroidView wrapping OSMDroid MapView + markers
│       ├── SpeciesCountRow.kt            [NEW] list item composable
│       └── IconicTaxa.kt                 [NEW] mapping of iconic_taxon_name → drawable + label
└── res/drawable/
    ├── ic_iconic_aves.xml                [NEW] vector drawable, ~12 files total
    ├── ic_iconic_amphibia.xml
    ├── ic_iconic_reptilia.xml
    ├── ic_iconic_mammalia.xml
    ├── ic_iconic_actinopterygii.xml
    ├── ic_iconic_mollusca.xml
    ├── ic_iconic_arachnida.xml
    ├── ic_iconic_insecta.xml
    ├── ic_iconic_plantae.xml
    ├── ic_iconic_fungi.xml
    ├── ic_iconic_protozoa.xml
    └── ic_iconic_unknown.xml

gradle/libs.versions.toml                 [+] osmdroid = "6.1.18"
app/build.gradle.kts                      [+] implementation(libs.osmdroid)
```

### Module boundaries

**`INatObservationsRepository`** — single public method, no UI knowledge:

```kotlin
suspend fun fetch(key: FilterKey, forceRefresh: Boolean = false): Result<CachedResult>
```

Internally: cache lookup → if expired or missing, two parallel HTTP
requests (`species_counts` for list, `observations` for map pins) →
join the two responses by `taxon_id` so each `SpeciesAggregate` can
expose the **nearest observation's** distance and URL (computed
client-side via the Haversine formula over the user's coordinates and
the per-observation lat/lon from the `/observations` response). When
a species in `species_counts` has no matching observation in the
parallel `/observations` sample (because `/observations` is capped at
200 entries ordered by date, not by taxon), fall back to:
`nearestObservationKm = -1f` (sentinel, displayed as "—") and
`nearestObservationUrl = "https://www.inaturalist.org/taxa/<taxonId>"`
(public taxon page).

**`RadarViewModel`** — composes `FilterKey` from Settings + current
location, calls `repo.fetch`, maps to `RadarUiState`. No knowledge of
OSMDroid. Hilt VM wrapper exposes `delegate` (matching the Review screen
pattern in this codebase).

**`RadarMap`** — pure view: takes `pins: List<MapPin>` and
`userLocation: GeoPoint?` as parameters. No VM, no repo. Wraps OSMDroid
`MapView` via `AndroidView`.

**`RadarFilterBar`** — stateless: takes `FilterState` + `onChange`
lambdas.

### Dependencies

```
RadarViewModel  ──→  INatObservationsRepository  ──→  INaturalistClient
       │                       │                          │
       │                       └──→ INatAuthRepository    │
       │                            (for not_user_id)     │
       │                                                  │
       ├──→  LocationProvider (existing FusedLocationProvider)
       │
       └──→  Settings (radar* keys)
```

## Data Types

```kotlin
// In-memory cache key. Coordinates are quantised to a 0.01° grid
// (~1.1 km) so micro-jitter from GPS does not invalidate the cache.
data class FilterKey(
    val latGrid: Int,            // round(lat * 100)
    val lonGrid: Int,            // round(lon * 100)
    val radiusKm: Int,
    val periodDays: Int,         // 1, 7, or 30
    val taxa: Set<String>,       // "Aves", "Mammalia", ... (empty = all)
    val excludeUserId: Long?,    // current iNat user.id, or null when logged out
)

data class CachedResult(
    val species: List<SpeciesAggregate>,
    val pins: List<MapPin>,
    val fetchedAtUtcMs: Long,
)

data class SpeciesAggregate(
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val iconicTaxon: String,             // matches IconicTaxa table
    val photoUrl: String?,               // taxon.default_photo.medium_url
    val observationCount: Int,
    val nearestObservationKm: Float,
    val nearestObservationUrl: String,   // public observation page
)

data class MapPin(
    val observationId: Long,
    val taxonId: Long,
    val scientificName: String,
    val lat: Double,
    val lon: Double,
    val obsUrl: String,
)

data class FilterState(
    val radiusKm: Int,
    val periodDays: Int,
    val taxa: Set<String>,
    val userLocation: GeoPoint?,
)

data class RadarUiState(
    val filter: FilterState,
    val species: List<SpeciesAggregate> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val locationStatus: LocationStatus = LocationStatus.Loading,
)

sealed interface LocationStatus {
    data object Loading : LocationStatus
    data class Live(val accuracyM: Float) : LocationStatus
    data object FallbackToLastKnown : LocationStatus
    data object NoLocation : LocationStatus
}
```

## Iconic Taxa

Twelve groups, matching iNat's canonical iconic taxa exactly. Vector
drawables bundled under `res/drawable/ic_iconic_<name>.xml`.

```kotlin
data class IconicTaxon(
    val id: String,           // "Aves", "Mammalia", ...
    val label: String,        // localised display label
    @DrawableRes val icon: Int,
)

// Eleven taxa are filterable via iNat's `iconic_taxa` query param.
// "Unknown" is intentionally NOT in the filter chip list because iNat
// has no `iconic_taxa=Unknown` value — observations without an
// identification simply have a null `iconic_taxon_name`. We keep the
// drawable so the list/map can fall back to it whenever the API
// returns an observation with no iconic_taxon_name.
internal val FilterableIconicTaxa = listOf(
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
internal val UnknownIconicTaxon =
    IconicTaxon("Unknown", "Unknown", R.drawable.ic_iconic_unknown)
```

iNat returns `iconic_taxon_name` matching these `id`s exactly, so the
mapping species → icon is a single map lookup with no translation layer.

**Source for the SVGs:** iNat publishes its iconic-taxon webfont in the
[iNaturalist/inaturalist](https://github.com/inaturalist/inaturalist)
repo (MIT-licensed). Extract the SVG glyphs (via Fontello/Icomoon or
directly from the asset pipeline), convert each to Android Vector
Drawable XML using Android Studio's Vector Asset Studio.

## Navigation Refactor

The current `Sound2iNatNavHost` owns the top-level `NavHost`. Each
destination provides its own `Scaffold`. After this change a new
`RootScaffold` owns the global `Scaffold(bottomBar=NavigationBar)`, and
the bottom bar shows only when the current route is in
`{Routes.HOME, Routes.RADAR}`.

```kotlin
// app/src/main/java/com/sound2inat/app/nav/RootScaffold.kt
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
                        icon = { Icon(Icons.Outlined.Mic, null) },
                        label = { Text("Recordings") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.RADAR,
                        onClick = { nav.navigateToTab(Routes.RADAR) },
                        icon = { Icon(Icons.Outlined.Radar, null) },
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
        popUpTo(Routes.HOME) { saveState = true; inclusive = false }
        launchSingleTop = true
        restoreState = true
    }
}
```

`MainActivity` switches from `Sound2iNatNavHost()` to `RootScaffold()`.

`Sound2iNatNavHost` accepts `nav: NavController, padding: PaddingValues`
parameters and adds:

```kotlin
composable(Routes.RADAR) {
    RadarScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) })
}
```

The Home composable already passes the same `onOpenSettings` lambda
through, so wiring is symmetric.

`Routes.RADAR = "radar"`.

## Settings Schema

```kotlin
// Settings.kt additions
val radarRadiusKm: Flow<Int> = ctx.dataStore.data.map { it[K.RADAR_RADIUS_KM] ?: DEFAULT_RADAR_RADIUS }
val radarPeriodDays: Flow<Int> = ctx.dataStore.data.map { it[K.RADAR_PERIOD_DAYS] ?: DEFAULT_RADAR_PERIOD }
val radarTaxa: Flow<Set<String>> = ctx.dataStore.data.map {
    it[K.RADAR_TAXA]?.takeIf(String::isNotEmpty)?.split(',')?.toSet() ?: emptySet()
}

suspend fun setRadarRadiusKm(v: Int) = ctx.dataStore.edit { it[K.RADAR_RADIUS_KM] = v }
suspend fun setRadarPeriodDays(v: Int) = ctx.dataStore.edit { it[K.RADAR_PERIOD_DAYS] = v }
suspend fun setRadarTaxa(v: Set<String>) = ctx.dataStore.edit {
    if (v.isEmpty()) it.remove(K.RADAR_TAXA) else it[K.RADAR_TAXA] = v.joinToString(",")
}

const val DEFAULT_RADAR_RADIUS = 5
const val DEFAULT_RADAR_PERIOD = 7
// DEFAULT_RADAR_TAXA = empty set = "all groups"
```

Defaults are chosen so the first-open experience shows a moderate
"what's around me this week" view.

## Repository

```kotlin
@Singleton
class INatObservationsRepository @Inject constructor(
    private val client: INaturalistClient,
    private val auth: INatAuthRepository,
) {
    private val cache = ConcurrentHashMap<FilterKey, CachedResult>()

    suspend fun fetch(key: FilterKey, forceRefresh: Boolean = false): Result<CachedResult> {
        if (!forceRefresh) {
            cache[key]?.takeIf { isFresh(it) }?.let { return Result.success(it) }
        }
        return runCatching {
            coroutineScope {
                val countsDeferred = async { client.nearbySpeciesCounts(key) }
                val pinsDeferred = async { client.nearbyObservations(key) }
                val raw = countsDeferred.await()
                val pins = pinsDeferred.await()
                val species = raw.attachNearestFrom(
                    userLat = key.latGrid / 100.0,
                    userLon = key.lonGrid / 100.0,
                    pins = pins,
                )
                CachedResult(species, pins, System.currentTimeMillis())
                    .also { cache[key] = it }
            }
        }
    }

    private fun isFresh(c: CachedResult): Boolean =
        System.currentTimeMillis() - c.fetchedAtUtcMs < TTL_MS

    private companion object {
        const val TTL_MS = 15L * 60 * 1000
    }
}
```

`attachNearestFrom` joins the two responses by `taxon_id`. For each
species it scans the `pins` list, picks the geographically nearest pin
(Haversine), and writes its distance + observation URL back into the
`SpeciesAggregate`. Species not represented in the `pins` sample
(see fallback rule above) get the sentinel distance and the taxon-page
URL.

## iNat API extensions

Two new methods on `INaturalistClient`:

```kotlin
// GET /v1/observations/species_counts
//   ?lat=&lng=&radius=
//   &d1=<ISO_NOW_MINUS_N_DAYS>
//   [&iconic_taxa=Aves,Mammalia,...]
//   [&not_user_id=<my_id>]
//   &quality_grade=research,needs_id
//   &per_page=100
//   &order=desc&order_by=count
suspend fun nearbySpeciesCounts(key: FilterKey): List<SpeciesAggregate>

// GET /v1/observations
//   ?lat=&lng=&radius=
//   &d1=<ISO_NOW_MINUS_N_DAYS>
//   [&iconic_taxa=...]
//   [&not_user_id=...]
//   &quality_grade=research,needs_id
//   &per_page=200
//   &order=desc&order_by=observed_on
suspend fun nearbyObservations(key: FilterKey): List<MapPin>

// `geoprivacy` is intentionally not pinned to "open" — observations
// auto-obscured by iNat for endangered species would otherwise be
// missing from the map. Users see slight pin jitter (≤0.1°) for
// obscured items, which is iNat's intended privacy guarantee, not a
// bug we should mask.
```

The auth header is attached when a token is available (better rate
limit) but is not required — `/observations` is a public endpoint.

`d1` is computed as `Instant.now().minus(periodDays, DAYS)` formatted as
`yyyy-MM-dd`.

`taxa` set is omitted from the query string when empty, which iNat
treats as "all iconic taxa".

`not_user_id` requires a numeric user id, not the login string. The
`INatAuthRepository` exposes a `userId` property populated alongside
`login` when `verifyToken` runs (this requires extending the existing
`/users/me` parse to also pluck `results[0].id`).

## ViewModel Pipeline

```kotlin
class RadarViewModel(
    private val repo: INatObservationsRepository,
    private val auth: INatAuthRepository,
    private val location: LocationProvider,
    private val settings: Settings,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope = externalScope ?: viewModelScope
    private val _state = MutableStateFlow(RadarUiState(filter = FilterState(5, 7, emptySet(), null)))
    val state: StateFlow<RadarUiState> = _state

    init {
        scope.launch {
            combine(
                settings.radarRadiusKm,
                settings.radarPeriodDays,
                settings.radarTaxa,
                locationFlow(),
            ) { r, p, t, loc -> FilterState(r, p, t, loc) }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { fs -> refreshFor(fs, force = false) }
        }
    }

    fun setRadius(km: Int) = scope.launch { settings.setRadarRadiusKm(km) }
    fun setPeriod(d: Int)  = scope.launch { settings.setRadarPeriodDays(d) }
    fun toggleTaxon(id: String) = scope.launch {
        val cur = settings.radarTaxa.first()
        settings.setRadarTaxa(if (id in cur) cur - id else cur + id)
    }
    fun pullRefresh() {
        if (_state.value.loading) return
        scope.launch { refreshFor(_state.value.filter, force = true) }
    }

    private suspend fun refreshFor(fs: FilterState, force: Boolean) {
        _state.update { it.copy(filter = fs, loading = true) }
        if (fs.userLocation == null) {
            _state.update { it.copy(loading = false, locationStatus = LocationStatus.NoLocation) }
            return
        }
        val key = FilterKey(
            latGrid = (fs.userLocation.latitude * 100).roundToInt(),
            lonGrid = (fs.userLocation.longitude * 100).roundToInt(),
            radiusKm = fs.radiusKm,
            periodDays = fs.periodDays,
            taxa = fs.taxa,
            excludeUserId = auth.userId,
        )
        repo.fetch(key, force).fold(
            onSuccess = { r -> _state.update {
                it.copy(loading = false, species = r.species, pins = r.pins, error = null)
            } },
            onFailure = { e -> _state.update {
                it.copy(loading = false, error = e.message ?: "Fetch failed")
            } },
        )
    }

    private fun locationFlow(): Flow<GeoPoint?> = flow {
        // Emit lastKnown immediately so the first fetch can start, then
        // request a fresh fix and re-emit if it arrives.
        val last = settings.lastKnownLat.first()?.let { lat ->
            settings.lastKnownLon.first()?.let { lon -> GeoPoint(lat, lon) }
        }
        if (last != null) emit(last)
        // FusedLocationProvider.getCurrent returns Fix? (custom data
        // class with latitude/longitude/accuracy/timestamp). Treat nulls
        // as "no fresh fix" — the lastKnown emission above is the
        // fallback the UI keeps using.
        runCatching { location.getCurrent() }
            .getOrNull()
            ?.let { fix ->
                settings.setLastKnownCoords(fix.latitude, fix.longitude)
                emit(GeoPoint(fix.latitude, fix.longitude))
            }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
```

## UI Structure

```
┌──────────────────────────────────────┐
│ TopAppBar: "Nearby"        ⚙        │
├──────────────────────────────────────┤
│ ╔══════════════════════════════════╗ │
│ ║      OSMDroid map (280 dp)       ║ │
│ ║         [center pin]             ║ │
│ ║      • • •  • observation pins   ║ │
│ ╚══════════════════════════════════╝ │
├──────────────────────────────────────┤
│ Radius:  [1km] [5km*] [25km] [100km] │
│ Period:  [Day] [Week*] [Month]       │
│ Groups:  [🐦] [🐾] [🐸] [🦗] ...      │
├──────────────────────────────────────┤
│ ┌──┐ Goldcrest                       │
│ │📷│ Regulus regulus                  │
│ └──┘ 12 obs · nearest 0.4 km         │
│ ...                                  │
└──────────────────────────────────────┘
```

```kotlin
@Composable
fun RadarScreen(onOpenSettings: () -> Unit) {
    val vm: RadarViewModelHilt = hiltViewModel()
    val state by vm.delegate.state.collectAsState()
    val ctx = LocalContext.current

    Scaffold(topBar = { RadarTopBar(onOpenSettings = onOpenSettings) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RadarMap(
                pins = state.pins,
                userLocation = state.filter.userLocation,
                onPinTap = { url -> ctx.openCustomTab(url) },
                modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT_DP.dp),
            )
            RadarFilterBar(
                filter = state.filter,
                onRadiusChange = vm.delegate::setRadius,
                onPeriodChange = vm.delegate::setPeriod,
                onTaxaToggle = vm.delegate::toggleTaxon,
            )
            PullToRefreshBox(
                isRefreshing = state.loading && state.species.isNotEmpty(),
                onRefresh = vm.delegate::pullRefresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                RadarBody(state, onRowTap = { ctx.openCustomTab(it.nearestObservationUrl) })
            }
        }
    }
}

private const val MAP_HEIGHT_DP = 280
```

`RadarBody` switches between empty-state, loading, error, and the
populated `LazyColumn(state.species)`.

`RadarMap` is an `AndroidView` wrapping `MapView`. The `update` lambda
clears existing markers (excluding the persistent "me" marker) and adds
fresh ones. Tile source: `TileSourceFactory.MAPNIK` (default OSM).
Default zoom: 13.

`RadarFilterBar` renders three horizontal scrollable rows of
`FilterChip`s:

```kotlin
private val RADIUS_OPTIONS = listOf(1, 5, 25, 100)
private val PERIOD_OPTIONS = listOf(1, 7, 30)
// TAXA_OPTIONS = IconicTaxa from IconicTaxa.kt
```

Single-select for radius and period; multi-select for taxa.

`SpeciesCountRow`:

```kotlin
ListItem(
    leadingContent = {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))) {
            if (row.photoUrl != null) {
                AsyncImage(row.photoUrl, contentDescription = null)
            } else {
                Icon(painterResource(IconicTaxa.iconFor(row.iconicTaxon)), null)
            }
        }
    },
    headlineContent = { Text(row.commonName ?: row.scientificName) },
    supportingContent = {
        Column {
            Text("${row.observationCount} obs · nearest ${formatKm(row.nearestObservationKm)}")
            Text(row.scientificName, style = bodySmall.italic())
        }
    },
    modifier = Modifier.clickable { onRowTap(row) },
)
```

Custom Tab utility:

```kotlin
fun Context.openCustomTab(url: String) {
    CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
}
```

## Edge Cases

| Scenario | Behaviour |
|---|---|
| `ACCESS_FINE_LOCATION` not yet granted | First Radar open requests permission via `RequestPermission` contract. Denial → fall back to `lastKnownLat/Lon`; if also null → empty state with "Grant location" CTA. |
| Permission denied "Don't ask again" | Banner with "Open app settings" → `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`. |
| GPS slow (≥10 s) | TopAppBar shows "Locating…" indicator; fetch starts on `lastKnownLat/Lon` if available. New fix → re-fetch only if it moved >100 m. |
| Zero species in radius | Empty-state copy: "No observations found. Try a wider radius or different period." No automatic widening. |
| `/observations` capped at 200 | List unaffected (separate `species_counts`). Map silently truncates pins; warning logged. |
| iNat HTTP 429 | `state.error = "iNaturalist rate limit reached. Try again in a minute."`; no automatic retry. |
| iNat 5xx / network failure | `state.error` set; cached species/pins remain visible; Snackbar with retry. |
| Auth token missing or expired | Anonymous request — Radar works; `excludeUserId = null` so own observations are not filtered. Banner "Sign in to exclude your own observations" shown when logged out. |
| All taxa chips off | Treated as "all groups" (empty `taxa` set in URL means iNat returns all iconic taxa). |
| Pull-to-refresh during in-flight fetch | `pullRefresh()` no-ops when `state.loading == true`. |
| GPS jitter between fixes | `latGrid/lonGrid` quantisation (0.01° ≈ 1.1 km) keeps the FilterKey stable across small movements. |
| `geoprivacy=obscured` observations | iNat returns coordinates with up to 0.1° noise. Pins are placed where iNat says — accept the noise, document for future. |
| Rotation / process restart | VM survives rotation (Hilt VM). Cache survives rotation, not process kill. First post-kill open re-fetches. |
| OSMDroid offline tiles | OSMDroid renders grey placeholders. Markers still drawn over them. No special handling. |
| Huge species list (≥100 rows) | LazyColumn handles it. No clustering on map for MVP — revisit if perf bites. |

## Testing

JVM unit tests (no Android dependencies):

| Test | What it covers |
|---|---|
| `filter change triggers fetch with debounce` | Single radius change → 300 ms debounce → exactly one repo.fetch |
| `rapid filter changes coalesce` | Three changes within 100 ms → one fetch with the last value |
| `pullRefresh forces cache bypass` | After warm cache, `pullRefresh()` calls repo with `force=true` |
| `permission denied falls back to lastKnown` | LocationProvider returns null + lastKnown set → fetch uses lastKnown coords |
| `no location at all → NoLocation state` | LocationProvider null + lastKnown null → state.locationStatus == NoLocation, no fetch |
| `error from repo surfaces in state` | Repo failure → state.error non-null, species list preserved |
| `same FilterKey hits cache` | Two opens within 5 minutes with identical filters → repo.fetch invoked once |
| `expired cache triggers refetch` | Inject clock at +16 minutes → repo.fetch invoked again |
| `parallel fetch of species_counts and observations` | MockWebServer dispatches both in parallel (verify timestamps) |
| `cache key normalises lat/lon to grid` | (50.123, 10.456) and (50.124, 10.457) produce the same FilterKey |
| `excludeUserId omitted when null` | Generated URL contains no `not_user_id=` parameter |
| `nearbySpeciesCounts URL composition` | All required parameters present in URL |
| `parses species_counts response fixture` | Hand-crafted iNat JSON → expected `List<SpeciesAggregate>` |
| `parses observations response fixture` | Hand-crafted iNat JSON → expected `List<MapPin>` |

Out of scope for unit tests:

- `RadarMap` (OSMDroid `AndroidView`) — manual UI verification only.
- `RadarFilterBar` — stateless and trivial.
- Custom Tab launch — system UI.
- Permission flow — needs an instrumented test, deferred.

## Effort Estimate

- 12 vector drawables (extract + convert): ~3-4 hours
- New repo + client methods + Settings keys: ~3 hours
- ViewModel + UiState: ~2 hours
- RadarScreen + filter bar + list row: ~3 hours
- RadarMap (OSMDroid AndroidView): ~3 hours
- RootScaffold refactor + nav wiring: ~2 hours
- Unit tests (12 cases): ~2-3 hours
- Manual QA + UX polish: ~3-4 hours

**Total: ~1.5-2 days.**
