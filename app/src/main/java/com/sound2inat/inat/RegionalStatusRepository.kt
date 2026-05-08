package com.sound2inat.inat

import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-session cache of iNaturalist regional presence, keyed by
 * (taxonName, lat-lon-bucket) so that different recording locations never
 * return stale results from a different region.
 *
 * Entries expire after [TTL_MS] (24 h). The backing [RegionFilter] also
 * caches internally (shorter TTLs), so cache misses here are cheap.
 */
@Singleton
class RegionalStatusRepository(
    private val regionFilter: RegionFilter,
    private val nowMs: () -> Long,
) {

    /** Production constructor used by Hilt — delegates to [RegionFilter]. */
    @Inject constructor(regionFilter: RegionFilter) : this(
        regionFilter = regionFilter,
        nowMs = System::currentTimeMillis,
    )

    /**
     * Thin interface so tests can inject a fake without constructing a full
     * [RegionFilter] (which requires [RegionLookup] + coroutine plumbing).
     */
    fun interface Annotator {
        suspend fun annotate(name: String, lat: Double, lon: Double): RegionalStatus
    }

    // The default annotator delegates to RegionFilter. Tests can override via
    // the factory below to avoid needing a real RegionFilter.
    private var annotator: Annotator = Annotator { name, lat, lon ->
        val stub = AggregatedDetection(name, null, 0f, 0, 0L, 0L)
        regionFilter.annotate(listOf(stub), lat, lon, radiusKm = 0)
            .first().regionalStatus ?: RegionalStatus.UNVERIFIED
    }

    private data class Entry(val status: RegionalStatus, val storedAtMs: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun get(taxonName: String, lat: Double, lon: Double): RegionalStatus {
        val key = "$taxonName|${bucket(lat)}|${bucket(lon)}"
        val now = nowMs()
        cache[key]?.takeIf { now - it.storedAtMs < TTL_MS }?.let { return it.status }
        val fresh = annotator.annotate(taxonName, lat, lon)
        cache[key] = Entry(fresh, now)
        return fresh
    }

    /**
     * Returns the cached [RegionalStatus] for the given taxon+location bucket if a
     * non-expired entry exists, or `null` if there is no entry (cache miss). Never
     * calls the network. Use this for the VM pre-flight check before issuing a
     * batched [RegionFilter.annotate] call for only the missing rows.
     *
     * Note: `null` unambiguously means "not in cache" — the cache never stores null
     * because [RegionalStatus] is a non-nullable enum.
     */
    fun getCached(taxonName: String, lat: Double, lon: Double): RegionalStatus? {
        val key = "$taxonName|${bucket(lat)}|${bucket(lon)}"
        val now = nowMs()
        return cache[key]?.takeIf { now - it.storedAtMs < TTL_MS }?.status
    }

    /** Stores a result already fetched externally (e.g. via a batched [RegionFilter] call). */
    fun storeResult(taxonName: String, lat: Double, lon: Double, status: RegionalStatus) {
        val key = "$taxonName|${bucket(lat)}|${bucket(lon)}"
        cache[key] = Entry(status, nowMs())
    }

    fun invalidateAll() = cache.clear()

    // 0.1° ≈ 11 km at equator — coarse enough that minor GPS jitter doesn't
    // bust the cache, fine enough that city-scale moves (e.g. 50 km) create
    // a new entry and trigger a fresh lookup.
    private fun bucket(d: Double): Int = (d * 10).toInt()

    companion object {
        const val TTL_MS = 24L * 60 * 60 * 1000

        /** Test factory: avoids constructing a full [RegionFilter]. */
        internal fun forTest(
            annotator: Annotator,
            nowMs: () -> Long = System::currentTimeMillis,
        ): RegionalStatusRepository {
            val noopLookup = object : RegionLookup {
                override suspend fun getPlaceIds(lat: Double, lon: Double) = emptyList<Long>()
                override suspend fun checkInPlaces(name: String, ids: List<Long>) = false
                override suspend fun checkNear(name: String, lat: Double, lon: Double, km: Int) = false
            }
            return RegionalStatusRepository(
                regionFilter = RegionFilter(noopLookup),
                nowMs = nowMs,
            ).also { it.annotator = annotator }
        }
    }
}
