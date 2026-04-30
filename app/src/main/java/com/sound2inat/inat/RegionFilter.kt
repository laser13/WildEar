package com.sound2inat.inat

import com.sound2inat.inference.AggregatedDetection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

fun interface RegionLookup {
    suspend fun hasObservationsNear(
        scientificName: String,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): Boolean
}

class RegionFilter(private val lookup: RegionLookup) {

    private val cache = ConcurrentHashMap<String, Boolean>()

    suspend fun filter(
        detections: List<AggregatedDetection>,
        lat: Double,
        lon: Double,
        radiusKm: Int,
    ): List<AggregatedDetection> = coroutineScope {
        val roundedLat = (lat * 10).roundToInt()
        val roundedLon = (lon * 10).roundToInt()
        detections
            .map { det ->
                async {
                    val key = "${det.taxonScientificName}|$roundedLat|$roundedLon|$radiusKm"
                    val allowed = cache.getOrPut(key) {
                        lookup.hasObservationsNear(det.taxonScientificName, lat, lon, radiusKm)
                    }
                    if (allowed) det else null
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}
