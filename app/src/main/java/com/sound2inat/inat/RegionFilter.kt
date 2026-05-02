package com.sound2inat.inat

import android.util.Log
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val TAG = "RegionFilter"

interface RegionLookup {
    suspend fun getPlaceIds(lat: Double, lon: Double): List<Long>
    suspend fun checkInPlaces(scientificName: String, placeIds: List<Long>): Boolean
    suspend fun checkNear(scientificName: String, lat: Double, lon: Double, radiusKm: Int): Boolean
}

class RegionFilter(
    private val lookup: RegionLookup,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private data class TimedEntry<T>(val value: T, val expiryMs: Long)

    private val placeCache = ConcurrentHashMap<String, TimedEntry<List<Long>>>()
    private val statusCache = ConcurrentHashMap<String, TimedEntry<RegionalStatus>>()

    suspend fun annotate(
        detections: List<AggregatedDetection>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): List<AggregatedDetection> {
        Log.d(TAG, "annotate: ${detections.size} detections at ($lat, $lon) radius=$radiusKm")
        val placeIds = resolvePlace(lat, lon)
        Log.d(TAG, "annotate: placeIds=$placeIds")
        return detections.map { det ->
            val status = resolveStatus(det.taxonScientificName, placeIds, lat, lon, radiusKm)
            Log.d(TAG, "  ${det.taxonScientificName} → $status")
            det.copy(regionalStatus = status)
        }
    }

    private suspend fun resolvePlace(lat: Double, lon: Double): List<Long> {
        val key = "${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}"
        val now = nowMs()
        placeCache[key]?.takeIf { it.expiryMs > now }?.let {
            Log.d(TAG, "resolvePlace: cache hit key=$key → ${it.value}")
            return it.value
        }
        val resolved = runCatching { lookup.getPlaceIds(lat, lon) }.getOrDefault(emptyList())
        Log.d(TAG, "resolvePlace: fetched key=$key → $resolved")
        // Empty result (network failure / no places) cached for short TTL only,
        // so a transient error doesn't permanently force the radius fallback.
        val ttl = if (resolved.isNotEmpty()) PLACE_TTL_MS else FAILURE_TTL_MS
        placeCache[key] = TimedEntry(resolved, now + ttl)
        return resolved
    }

    private suspend fun resolveStatus(
        taxon: String,
        placeIds: List<Long>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): RegionalStatus {
        val key = if (placeIds.isNotEmpty()) {
            "$taxon|p|${placeIds.joinToString(",")}"
        } else {
            "$taxon|r|${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}|$radiusKm"
        }
        val now = nowMs()
        statusCache[key]?.takeIf { it.expiryMs > now }?.let {
            Log.d(TAG, "resolveStatus: cache hit $taxon → ${it.value}")
            return it.value
        }
        val status = runCatching {
            val found = if (placeIds.isNotEmpty()) {
                lookup.checkInPlaces(taxon, placeIds)
            } else {
                lookup.checkNear(taxon, lat, lon, radiusKm)
            }
            if (found) RegionalStatus.CONFIRMED else RegionalStatus.NOT_CONFIRMED
        }.getOrDefault(RegionalStatus.UNVERIFIED)
        // UNVERIFIED is the network-error fallback: cache briefly so a single
        // failed lookup doesn't poison the species for the entire app session.
        val ttl = if (status == RegionalStatus.UNVERIFIED) FAILURE_TTL_MS else STATUS_TTL_MS
        statusCache[key] = TimedEntry(status, now + ttl)
        return status
    }

    companion object {
        private const val PLACE_TTL_MS = 24L * 60 * 60 * 1000      // places don't change daily
        private const val STATUS_TTL_MS = 6L * 60 * 60 * 1000       // new observations could land
        private const val FAILURE_TTL_MS = 5L * 60 * 1000           // transient errors recover fast
    }
}
