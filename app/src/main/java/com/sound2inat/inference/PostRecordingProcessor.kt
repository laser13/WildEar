package com.sound2inat.inference

import android.util.Log
import com.sound2inat.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class PostRecordingProcessor(
    private val settings: Settings,
) {
    /**
     * Applies active post-recording settings to [wavFile] in-place:
     * 1. Spectral denoising (if [Settings.spectralSubtractionEnabled]).
     * 2. Peak normalization (if [Settings.normalizeAudio]).
     * Writes to a temp file, replaces original atomically on success.
     * On failure: logs the error and leaves the original intact.
     */
    suspend fun process(wavFile: File) = withContext(Dispatchers.IO) {
        val spectralEnabled = settings.spectralSubtractionEnabled.first()
        val normalizeEnabled = settings.normalizeAudio.first()
        if (!spectralEnabled && !normalizeEnabled) return@withContext

        runCatching {
            val (rawShorts, sampleRate) = WavReader.readMono16(wavFile)

            var shorts = rawShorts

            if (spectralEnabled) {
                val floats = FloatArray(rawShorts.size) { i ->
                    rawShorts[i] / Short.MAX_VALUE.toFloat()
                }
                val denoised = denoiseFull(floats, sampleRate)
                shorts = ShortArray(denoised.size) { i ->
                    (denoised[i] * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            if (normalizeEnabled) {
                shorts = AudioNormalizer.normalizeSamples(shorts)
            }

            val tmp = File(wavFile.parent, "${wavFile.nameWithoutExtension}.processing.tmp")
            writeWav(shorts, sampleRate, tmp)
            if (!tmp.renameTo(wavFile)) {
                // renameTo can fail across filesystems; fallback to copy+delete
                tmp.copyTo(wavFile, overwrite = true)
                tmp.delete()
            }
        }.onFailure { e ->
            Log.e(TAG, "Post-recording processing failed for ${wavFile.name}; leaving original intact", e)
        }
    }

    private companion object {
        const val TAG = "PostRecordingProcessor"
    }
}
