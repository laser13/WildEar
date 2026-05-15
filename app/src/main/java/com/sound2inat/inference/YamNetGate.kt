package com.sound2inat.inference

fun interface YamNetGate {
    /**
     * Classifies a PCM window and returns a [YamNetGateResult] with biological
     * score, background score, and a PASS/DOWNRANK recommendation.
     *
     * Returns null when the gate is unavailable (model not installed, error) —
     * callers must treat null as PASS (fail-open).
     */
    suspend fun classify(pcmFloat32: FloatArray, sampleRateHz: Int): YamNetGateResult?

    /** Releases native TFLite resources. Safe to call multiple times. */
    suspend fun close() {}
}
