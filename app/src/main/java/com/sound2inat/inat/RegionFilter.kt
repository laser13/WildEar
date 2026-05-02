package com.sound2inat.inat

import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

interface RegionLookup {
    suspend fun getPlaceId(lat: Double, lon: Double): Long?
    suspend fun checkInPlace(scientificName: String, placeId: Long): Boolean
    suspend fun checkNear(scientificName: String, lat: Double, lon: Double, radiusKm: Int): Boolean
}

class RegionFilter(private val lookup: RegionLookup) {

    private val placeCache = ConcurrentHashMap<String, Long>()
    private val statusCache = ConcurrentHashMap<String, RegionalStatus>()

    suspend fun annotate(
        detections: List<AggregatedDetection>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): List<AggregatedDetection> {
        val placeId = resolvePlace(lat, lon)
        return detections.map { det ->
            val status = resolveStatus(det.taxonScientificName, placeId, lat, lon, radiusKm)
            det.copy(regionalStatus = status)
        }
    }

    private suspend fun resolvePlace(lat: Double, lon: Double): Long? {
        val key = "${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}"
        val cached = placeCache[key]
        if (cached != null) return if (cached == NO_PLACE) null else cached
        val resolved = runCatching { lookup.getPlaceId(lat, lon) }.getOrNull()
        placeCache[key] = resolved ?: NO_PLACE
        return resolved
    }

    private suspend fun resolveStatus(
        taxon: String,
        placeId: Long?,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): RegionalStatus {
        val key = if (placeId != null) {
            "$taxon|p|$placeId"
        } else {
            "$taxon|r|${(lat * 100).roundToInt()}|${(lon * 100).roundToInt()}|$radiusKm"
        }
        statusCache[key]?.let { return it }
        val status = runCatching {
            val found = if (placeId != null) {
                lookup.checkInPlace(taxon, placeId)
            } else {
                lookup.checkNear(taxon, lat, lon, radiusKm)
            }
            if (found) RegionalStatus.CONFIRMED else RegionalStatus.NOT_CONFIRMED
        }.getOrDefault(RegionalStatus.UNVERIFIED)
        statusCache[key] = status
        return status
    }

    private companion object {
        const val NO_PLACE = -1L
    }
}
