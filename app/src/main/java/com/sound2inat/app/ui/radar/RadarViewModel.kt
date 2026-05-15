package com.sound2inat.app.ui.radar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sound2inat.app.data.Settings
import com.sound2inat.inat.CachedResult
import com.sound2inat.inat.FilterKey
import com.sound2inat.inat.INatAuthRepository
import com.sound2inat.inat.INatObservationsRepository
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
@Suppress("LongParameterList")
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class RadarViewModel(
    private val repoFetch: suspend (FilterKey, Boolean) -> Result<CachedResult>,
    radarRadiusKm: Flow<Int>,
    radarPeriodDays: Flow<Int>,
    radarTaxa: Flow<Set<String>>,
    private val getLastKnown: suspend () -> Pair<Double?, Double?>,
    private val getLocation: suspend () -> Fix?,
    private val userId: () -> Long?,
    private val setRadiusKm: suspend (Int) -> Unit = {},
    private val setPeriodDays: suspend (Int) -> Unit = {},
    private val setTaxa: suspend (Set<String>) -> Unit = {},
    private val getTaxa: suspend () -> Set<String> = { emptySet() },
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    @Inject constructor(
        repo: INatObservationsRepository,
        auth: INatAuthRepository,
        location: LocationProvider,
        settings: Settings,
        @Suppress("UNUSED_PARAMETER") savedStateHandle: SavedStateHandle,
    ) : this(
        repoFetch = { key, force -> repo.fetch(key, force) },
        radarRadiusKm = settings.radarRadiusKm,
        radarPeriodDays = settings.radarPeriodDays,
        radarTaxa = settings.radarTaxa,
        getLastKnown = { settings.lastKnownLat.first() to settings.lastKnownLon.first() },
        getLocation = { location.getCurrent() },
        userId = { auth.userId },
        setRadiusKm = { settings.setRadarRadiusKm(it) },
        setPeriodDays = { settings.setRadarPeriodDays(it) },
        setTaxa = { settings.setRadarTaxa(it) },
        getTaxa = { settings.radarTaxa.first() },
    )

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

    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun pullRefresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try { refreshFor(_state.value.filter, force = true) } finally { refreshInFlight.set(false) }
        }
    }

    fun setRadius(km: Int) = scope.launch { setRadiusKm(km) }
    fun setPeriod(d: Int) = scope.launch { setPeriodDays(d) }
    fun toggleTaxon(id: String) = scope.launch {
        val cur = getTaxa()
        setTaxa(if (id in cur) cur - id else cur + id)
    }

    private suspend fun primeLocation() {
        val (lat, lon) = getLastKnown()
        val hasLastKnown = lat != null && lon != null
        if (hasLastKnown) {
            _state.update { it.copy(locationStatus = LocationStatus.FallbackToLastKnown) }
        }
        val fix = runCatching { getLocation() }.getOrNull()
        when {
            fix != null -> {
                locationFlow.value = GeoPoint(fix.latitude, fix.longitude)
                _state.update { it.copy(locationStatus = LocationStatus.Live(fix.accuracyMeters ?: 0f)) }
            }
            hasLastKnown -> {
                locationFlow.value = GeoPoint(lat!!, lon!!)
            }
            else -> {
                _state.update { it.copy(locationStatus = LocationStatus.NoLocation) }
            }
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
