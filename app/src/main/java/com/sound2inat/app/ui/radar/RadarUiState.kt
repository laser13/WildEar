package com.sound2inat.app.ui.radar

import org.osmdroid.util.GeoPoint

/**
 * Cache key for [com.sound2inat.inat.INatObservationsRepository]. Coordinates
 * are quantised to a 0.01° grid (≈1.1 km) so micro-jitter from the GPS does
 * not invalidate the cache between fixes. Quantisation is a heuristic — two
 * fixes straddling a grid boundary will land in different cells and miss
 * each other, which is acceptable given the 15-min repo TTL.
 *
 * `taxa` must be an immutable [Set]. The cache uses this key in a
 * `ConcurrentHashMap`; mutating the set after insertion would orphan the
 * entry because its hash would no longer match.
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
