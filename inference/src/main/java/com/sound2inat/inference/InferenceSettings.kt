package com.sound2inat.inference

import kotlinx.coroutines.flow.Flow

/**
 * The settings surface the inference layer needs, declared in the inference
 * package so `inference` does not depend on `com.sound2inat.app.data.Settings`
 * (app layer). Implemented by an app-layer adapter and injected via Hilt.
 */
interface InferenceSettings {
    val minConfidenceDisplay: Flow<Float>
    val minWindows: Flow<Int>
    val yamNetGateEnabled: Flow<Boolean>
    val birdNetMetaEnabled: Flow<Boolean>
    val lastKnownLat: Flow<Double?>
    val lastKnownLon: Flow<Double?>

    suspend fun setLastKnownCoords(lat: Double, lon: Double)
}
