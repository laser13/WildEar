package com.sound2inat.inat

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance between two `(lat, lon)` pairs in kilometres,
 * via the Haversine formula. Returns `0f` when the two points are the
 * same. Uses the WGS-84-approximating mean Earth radius — accurate to
 * a few hundred metres at the scales the radar cares about (≤100 km).
 */
internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = 6371.0 // Earth mean radius, km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2).let { it * it }
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (r * c).toFloat()
}
