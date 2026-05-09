package com.sound2inat.inference

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Slices a mono 16-bit WAV into fixed windows hopped by [hopSeconds], optionally
 * applies a YAMNet biological gate, and calls each model per window that passes the gate.
 *
 * Sequential path ([models].size == 1): existing per-window loop, unchanged behaviour.
 * Parallel path ([models].size > 1): windows split evenly across models and processed
 * in parallel coroutines; results merged and sorted by windowStartMs.
 *
 * [run] closes every model it receives (sequential: try/finally on models[0];
 * parallel: async finally blocks per model). TFLite implementations are safe to
 * double-close (null-guard on interpreter), so callers with their own finally-block
 * close do not need to change.
 */
class InferenceRunner(
    private val models: List<BioacousticModel>,
    private val hopSeconds: Float = 1f,
    private val yamNetGate: YamNetGate? = null,
    /**
     * When true, a DOWNRANK gate decision skips [BioacousticModel.predict] entirely.
     * When false (default), DOWNRANK is a soft post-filter that drops results only
     * when no prediction exceeds [HIGH_CONFIDENCE_OVERRIDE].
     */
    val hardGate: Boolean = false,
) {
    init { require(models.isNotEmpty()) { "models must not be empty" } }

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    suspend fun run(
        wavFile: File,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        _progress.value = 0f
        val model = models.first()
        val t0 = System.currentTimeMillis()
        val (rawSamples, nativeRate) = WavReader.readMono16(wavFile)
        val targetRate = model.expectedSampleRateHz
        val resampled = if (nativeRate == targetRate) {
            rawSamples
        } else {
            Resampler.resample(rawSamples, nativeRate, targetRate)
        }
        Log.d(
            "InferenceTiming",
            "${model.modelId}: WAV read+resample ${System.currentTimeMillis() - t0}ms " +
                "(${rawSamples.size}→${resampled.size} samples, ${nativeRate}→${targetRate}Hz)",
        )

        val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }

        val windowSeconds = model.windowMs / MS_PER_SECOND
        val win = (windowSeconds * targetRate).toInt()
        val hop = (hopSeconds * targetRate).toInt()
        require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }
        val frames = if (normalized.size < win) 0 else 1 + (normalized.size - win) / hop
        if (frames == 0) {
            models.forEach { runCatching { it.close() } }
            _progress.value = 1f
            return emptyList()
        }

        return if (models.size == 1) {
            runSequential(normalized, targetRate, frames, win, hop, model, latitude, longitude, observedAtMillis)
        } else {
            runParallel(normalized, targetRate, frames, win, hop, latitude, longitude, observedAtMillis)
        }
    }

    private suspend fun runSequential(
        normalized: FloatArray,
        targetRate: Int,
        frames: Int,
        win: Int,
        hop: Int,
        model: BioacousticModel,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        val out = ArrayList<WindowPrediction>(frames * 5)
        val loopStart = System.currentTimeMillis()
        var batchStart = loopStart
        try {
            for (f in 0 until frames) {
                val s = f * hop
                val window = normalized.copyOfRange(s, s + win)
                _progress.value = (f + 1).toFloat() / frames
                val gateResult = yamNetGate?.classify(window, targetRate)
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
                    Log.d(
                        "InferenceTiming",
                        "${model.modelId}: window ${f + 1}/$frames " +
                            "${now - batchStart}ms batch / ${now - loopStart}ms total",
                    )
                    batchStart = now
                }
                if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                    if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
                }
                out += predictions
            }
        } finally {
            model.close()
        }
        Log.d(
            "InferenceTiming",
            "${model.modelId}: loop done — $frames windows in ${System.currentTimeMillis() - loopStart}ms",
        )
        return out
    }

    private suspend fun runParallel(
        normalized: FloatArray,
        targetRate: Int,
        frames: Int,
        win: Int,
        hop: Int,
        latitude: Double?,
        longitude: Double?,
        observedAtMillis: Long,
    ): List<WindowPrediction> {
        val counter = AtomicInteger(0)
        val results = coroutineScope {
            models.indices.map { i ->
                val chunkStart = i * frames / models.size
                val chunkEnd = if (i == models.size - 1) frames else (i + 1) * frames / models.size
                async {
                    val chunkOut = ArrayList<WindowPrediction>()
                    try {
                        for (f in chunkStart until chunkEnd) {
                            val s = f * hop
                            val window = normalized.copyOfRange(s, s + win)
                            val gateResult = yamNetGate?.classify(window, targetRate)
                            if (hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                                _progress.value = counter.incrementAndGet().toFloat() / frames
                                continue
                            }
                            val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
                            val endMs = ((s + win).toLong() * MS_PER_SECOND_LONG) / targetRate
                            val predictions = models[i].predict(
                                pcmFloat32 = window,
                                sampleRateHz = targetRate,
                                latitude = latitude,
                                longitude = longitude,
                                observedAtMillis = observedAtMillis,
                                windowStartMs = startMs,
                                windowEndMs = endMs,
                            )
                            _progress.value = counter.incrementAndGet().toFloat() / frames
                            if (!hardGate && gateResult?.recommendation == GateRecommendation.DOWNRANK) {
                                if (predictions.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) continue
                            }
                            chunkOut += predictions
                        }
                    } finally {
                        models[i].close()
                    }
                    chunkOut
                }
            }.awaitAll()
        }
        _progress.value = 1f
        return results.flatten().sortedBy { it.startMs }
    }

    private companion object {
        const val MS_PER_SECOND = 1_000f
        const val MS_PER_SECOND_LONG = 1_000L
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
