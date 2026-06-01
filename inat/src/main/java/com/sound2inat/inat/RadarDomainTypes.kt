package com.sound2inat.inat

/**
 * Cache key for [INatObservationsRepository]. Coordinates are quantised to a
 * 0.01° grid (≈1.1 km) so micro-jitter from the GPS does not invalidate the
 * cache between fixes.
 *
 * `taxa` must be an immutable [Set]. The cache uses this key in a
 * [java.util.concurrent.ConcurrentHashMap]; mutating the set after insertion
 * would orphan the entry because its hash would no longer match.
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

/** Result returned by [INatObservationsRepository] — species list, map pins, and fetch timestamp. */
data class CachedResult(
    val species: List<SpeciesAggregate>,
    val pins: List<MapPin>,
    val fetchedAtUtcMs: Long,
)
