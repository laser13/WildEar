package com.sound2inat.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Slices a mono 16-bit WAV into fixed windows hopped by [hopSeconds], optionally
 * applies a YAMNet biological gate, and calls [model] per window that passes the gate.
 *
 * Pipeline per run:
 *   1. Read + resample to model rate (ShortArray).
 *   2. Normalize to [-1, 1] (FloatArray).
 *   3. Per window: YamNetGate check → model.predict().
 *
 * Pass null for [yamNetGate] to skip the gate step.
 * Fail-open: the gate's own error handling returns true on any exception.
 */
class InferenceRunner(
    private val model: BioacousticModel,
    private val hopSeconds: Float = 1f,
    private val yamNetGate: YamNetGate? = null,
    /**
     * When true, a DOWNRANK gate decision skips [model.predict] entirely (true pre-filter).
     * When false (default), the gate is a soft post-filter: predict always runs, DOWNRANK only
     * drops results when no prediction exceeds [HIGH_CONFIDENCE_OVERRIDE].
     */
    val hardGate: Boolean = false,
) {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    suspend fun run(
        wavFile: File,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        _progress.value = 0f
        val t0 = System.currentTimeMillis()
        val (rawSamples, nativeRate) = WavReader.readMono16(wavFile)
        val targetRate = model.expectedSampleRateHz
        val resampled = if (nativeRate == targetRate) {
            rawSamples
        } else {
            Resampler.resample(rawSamples, nativeRate, targetRate)
        }
        android.util.Log.d(
            "InferenceTiming",
            "${model.modelId}: WAV read+resample ${System.currentTimeMillis() - t0}ms " +
                "(${rawSamples.size}→${resampled.size} samples, ${nativeRate}→${targetRate}Hz)",
        )

        // Normalize to [-1, 1].
        val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }

        val windowSeconds = model.windowMs / MS_PER_SECOND
        val win = (windowSeconds * targetRate).toInt()
        val hop = (hopSeconds * targetRate).toInt()
        require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }
        val frames = if (normalized.size < win) 0 else 1 + (normalized.size - win) / hop
        if (frames == 0) {
            _progress.value = 1f
            return emptyList()
        }
        val out = ArrayList<WindowPrediction>(frames * 5)
        val loopStart = System.currentTimeMillis()
        var batchStart = loopStart
        for (f in 0 until frames) {
            val s = f * hop
            val window = normalized.copyOfRange(s, s + win)
            _progress.value = (f + 1).toFloat() / frames
            val gateResult = yamNetGate?.classify(window, targetRate) // null = fail-open → PASS
            // Hard pre-filter: skip predict entirely when YAMNet finds no biological signal.
            if (hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) continue
            val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
            val endMs = ((s + win).toLong() * MS_PER_SECOND_LONG) / targetRate
            val predictions = model.predict(
                pcmFloat32 = window,
                sampleRateHz = targetRate,
                latitude = latitude,
                longitude = longitude,
                observedAtMillis = observedAtMillis,
                windowStartMs = startMs,
                windowEndMs = endMs,
            )
            if (f == 0 || (f + 1) % 50 == 0 || f == frames - 1) {
                val now = System.currentTimeMillis()
                android.util.Log.d(
                    "InferenceTiming",
                    "${model.modelId}: window ${f + 1}/$frames " +
                        "${now - batchStart}ms batch / ${now - loopStart}ms total",
                )
                batchStart = now
            }
            // Soft post-filter (hardGate=false only): DOWNRANK drops results when no high-confidence hit.
            if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                val hasHighConfidence = predictions.any { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }
                if (!hasHighConfidence) continue
            }
            out += predictions
        }
        android.util.Log.d(
            "InferenceTiming",
            "${model.modelId}: loop done — $frames windows in ${System.currentTimeMillis() - loopStart}ms",
        )
        return out
    }

    private companion object {
        const val MS_PER_SECOND = 1_000f
        const val MS_PER_SECOND_LONG = 1_000L

        /** If any prediction has confidence >= this, override a DOWNRANK gate decision. */
        const val HIGH_CONFIDENCE_OVERRIDE = 0.7f
    }
}

/**
 * Reads a mono 16-bit PCM WAV produced by `recorder.WavWriter` (clean 44-byte
 * header, fmt chunk size 16, PCM data chunk immediately after). Not a general
 * RIFF parser — extra chunks (e.g. LIST/INFO) are NOT supported.
 */
internal object WavReader {
    fun readMono16(file: File): Pair<ShortArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44).also { raf.readFully(it) }
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                "Not a WAV file"
            }
            val ch = readLeUint16(header, 22)
            val sr = readLeUint32(header, 24)
            val bits = readLeUint16(header, 34)
            require(ch == 1 && bits == 16) { "Mono 16-bit PCM only (got ch=$ch bits=$bits)" }
            require(String(header, 36, 4) == "data") {
                "WAV 'data' chunk not at offset 36 — unsupported chunk layout"
            }
            val dataSize = readLeUint32(header, 40)
            val raw = ByteArray(dataSize)
            raf.readFully(raw)
            val samples = ShortArray(dataSize / 2)
            for (i in samples.indices) {
                val lo = raw[2 * i].toInt() and 0xFF
                val hi = raw[2 * i + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }
            return samples to sr
        }
    }

    private fun readLeUint16(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    private fun readLeUint32(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or
            ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or
            ((buf[o + 3].toInt() and 0xFF) shl 24)
}
