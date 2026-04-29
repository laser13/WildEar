package com.sound2inat.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * Slices a mono 16-bit WAV into fixed `windowSeconds`-second windows hopped by
 * `hopSeconds`, calls [model] per window, and aggregates predictions.
 *
 * Progress is reported in [progress] as a fraction in `[0, 1]`. The runner is
 * NOT designed to be invoked concurrently for the same instance — there is one
 * progress flow per runner.
 */
class InferenceRunner(
    private val model: BioacousticModel,
    private val windowSeconds: Float = 3f,
    private val hopSeconds: Float = 1f,
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
        val (samples, sampleRate) = WavReader.readMono16(wavFile)
        val win = (windowSeconds * sampleRate).toInt()
        val hop = (hopSeconds * sampleRate).toInt()
        require(win > 0 && hop > 0) { "Invalid window/hop: win=$win hop=$hop" }
        val frames = if (samples.size < win) 0 else 1 + (samples.size - win) / hop
        if (frames == 0) {
            _progress.value = 1f
            return emptyList()
        }
        val out = ArrayList<WindowPrediction>(frames * 5)
        for (f in 0 until frames) {
            val s = f * hop
            val window = FloatArray(win) { idx -> samples[s + idx] / Short.MAX_VALUE.toFloat() }
            val startMs = (s.toLong() * 1000L) / sampleRate
            val endMs = ((s + win).toLong() * 1000L) / sampleRate
            out += model.predict(
                pcmFloat32 = window,
                sampleRateHz = sampleRate,
                latitude = latitude,
                longitude = longitude,
                observedAtMillis = observedAtMillis,
                windowStartMs = startMs,
                windowEndMs = endMs,
            )
            _progress.value = (f + 1).toFloat() / frames
        }
        return out
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
