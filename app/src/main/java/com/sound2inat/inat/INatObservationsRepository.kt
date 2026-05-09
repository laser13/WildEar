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
            client.nearbySpeciesCounts(
                key,
                periodEndDateUtc = isoDate(now() - key.periodDays.daysAsMs()),
            )
        },
        pinsLoader = { key ->
            client.nearbyObservations(
                key,
                periodEndDateUtc = isoDate(now() - key.periodDays.daysAsMs()),
            )
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

        internal fun now() = System.currentTimeMillis()
        internal fun Int.daysAsMs() = this * 24L * 60 * 60 * 1000
        internal fun isoDate(epochMs: Long): String =
            java.time.Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate()
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
