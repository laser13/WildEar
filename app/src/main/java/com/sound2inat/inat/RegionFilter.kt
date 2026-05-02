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

class RegionFilter(private val lookup: RegionLookup) {

    private val placeCache = ConcurrentHashMap<String, List<Long>>()
    private val statusCache = ConcurrentHashMap<String, RegionalStatus>()

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
        placeCache[key]?.let {
            Log.d(TAG, "resolvePlace: cache hit key=$key → $it")
            return it
        }
        val resolved = runCatching { lookup.getPlaceIds(lat, lon) }.getOrDefault(emptyList())
        Log.d(TAG, "resolvePlace: fetched key=$key → $resolved")
        placeCache[key] = resolved
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
        statusCache[key]?.let {
            Log.d(TAG, "resolveStatus: cache hit $taxon → $it")
            return it
        }
        val status = runCatching {
            val found = if (placeIds.isNotEmpty()) {
                lookup.checkInPlaces(taxon, placeIds)
            } else {
                lookup.checkNear(taxon, lat, lon, radiusKm)
            }
            if (found) RegionalStatus.CONFIRMED else RegionalStatus.NOT_CONFIRMED
        }.getOrDefault(RegionalStatus.UNVERIFIED)
        statusCache[key] = status
        return status
    }
}
