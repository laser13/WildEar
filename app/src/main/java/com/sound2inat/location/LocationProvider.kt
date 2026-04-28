package com.sound2inat.location

data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timestampMs: Long,
)

interface LocationProvider {
    suspend fun getCurrent(timeoutMs: Long = 15_000): Fix?
}
