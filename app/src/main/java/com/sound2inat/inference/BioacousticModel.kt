package com.sound2inat.inference

import java.io.File

/**
 * Pluggable bioacoustic model contract used by [InferenceRunner].
 *
 * Implementations are NOT required to be thread-safe; the runner serialises
 * calls per recording. Lat/lon/observedAt are forwarded so future models
 * (e.g. BirdNET-style metadata models) may consume them — the BirdNET v2.4
 * raw-audio model ignores them.
 */
interface BioacousticModel {
    val modelId: String
    val modelVersion: String

    /** Sample rate the model expects on its raw-audio input tensor (Hz). */
    val expectedSampleRateHz: Int

    /** Window length the model consumes per [predict] call (milliseconds). */
    val windowMs: Long

    suspend fun load(modelFile: File, labelsFile: File)

    // Signature is locked by the plan (Step 1) and by the spike's metadata
    // contract — lat/lon/observedAt flow through to future location-aware
    // models. Splitting into a request DTO would change the contract.
    @Suppress("LongParameterList")
    suspend fun predict(
        pcmFloat32: FloatArray,
        sampleRateHz: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<WindowPrediction>

    /**
     * Returns a new, unloaded instance of the same model type.
     * The caller must call [load] before [predict] and [close] when done.
     * Does not copy interpreter state.
     */
    fun newInstance(): BioacousticModel

    fun close()
}
