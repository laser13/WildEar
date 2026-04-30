package com.sound2inat.inference

fun interface YamNetGate {
    /** Returns false if the window is clearly non-biological and should be skipped. */
    suspend fun isBiological(pcmFloat32: FloatArray, sampleRateHz: Int): Boolean
}
