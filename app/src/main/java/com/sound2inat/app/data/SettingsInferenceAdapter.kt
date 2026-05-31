package com.sound2inat.app.data

import com.sound2inat.inference.InferenceSettings
import kotlinx.coroutines.flow.Flow

/** Adapts the app-layer [Settings] to the inference-layer [InferenceSettings] contract. */
class SettingsInferenceAdapter(private val settings: Settings) : InferenceSettings {
    override val minConfidenceDisplay: Flow<Float> = settings.minConfidenceDisplay
    override val minWindows: Flow<Int> = settings.minWindows
    override val yamNetGateEnabled: Flow<Boolean> = settings.yamNetGateEnabled
    override val birdNetMetaEnabled: Flow<Boolean> = settings.birdNetMetaEnabled
    override val lastKnownLat: Flow<Double?> = settings.lastKnownLat
    override val lastKnownLon: Flow<Double?> = settings.lastKnownLon

    override suspend fun setLastKnownCoords(lat: Double, lon: Double) =
        settings.setLastKnownCoords(lat, lon)
}
