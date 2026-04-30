package com.sound2inat.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Slices a mono 16-bit WAV into fixed windows hopped by [hopSeconds], optionally
 * applies spectral subtraction and a YAMNet biological gate, and calls [model]
 * per window that passes the gate.
 *
 * Pipeline per run:
 *   1. Read + resample to model rate (ShortArray).
 *   2. Normalize to [-1, 1] and apply high-pass filter to full signal (FloatArray).
 *   3. Per window: SpectralSubtractor → YamNetGate check → model.predict().
 *
 * Pass null for [spectralSubtractor] or [yamNetGate] to skip those steps.
 * Fail-open: the gate's own error handling returns true on any exception.
 */
class InferenceRunner(
    private val model: BioacousticModel,
    private val hopSeconds: Float = 1f,
    private val spectralSubtractor: SpectralSubtractor? = null,
    private val yamNetGate: YamNetGate? = null,
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
        val (rawSamples, nativeRate) = WavReader.readMono16(wavFile)
        val targetRate = model.expectedSampleRateHz
        val resampled = if (nativeRate == targetRate) {
            rawSamples
        } else {
            Resampler.resample(rawSamples, nativeRate, targetRate)
        }

        // Normalize and apply high-pass filter to the full signal before slicing
        // so the IIR filter has no edge effects at window boundaries.
        val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }
        val filtered = highPassFilter(normalized, targetRate)

        val windowSeconds = model.windowMs / MS_PER_SECOND
        val win = (windowSeconds * targetRate).toInt()
        val hop = (hopSeconds * targetRate).toInt()
        require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }
        val frames = if (filtered.size < win) 0 else 1 + (filtered.size - win) / hop
        if (frames == 0) {
            _progress.value = 1f
            return emptyList()
        }
        val out = ArrayList<WindowPrediction>(frames * 5)
        for (f in 0 until frames) {
            val s = f * hop
            var window = filtered.copyOfRange(s, s + win)
            window = spectralSubtractor?.process(window) ?: window
            _progress.value = (f + 1).toFloat() / frames
            if (yamNetGate?.isBiological(window, targetRate) == false) continue
            val startMs = (s.toLong() * MS_PER_SECOND_LONG) / targetRate
            val endMs = ((s + win).toLong() * MS_PER_SECOND_LONG) / targetRate
            out += model.predict(
                pcmFloat32 = window,
                sampleRateHz = targetRate,
                latitude = latitude,
                longitude = longitude,
                observedAtMillis = observedAtMillis,
                windowStartMs = startMs,
                windowEndMs = endMs,
            )
        }
        return out
    }

    private companion object {
        const val MS_PER_SECOND = 1_000f
        const val MS_PER_SECOND_LONG = 1_000L
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
