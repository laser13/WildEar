package com.sound2inat.app.ui.review

import com.sound2inat.inference.WavReader
import com.sound2inat.inference.denoiseFull
import com.sound2inat.recorder.WavWriter
import java.io.File

/**
 * On-disk artifacts produced for the Review-screen denoise preview:
 * a denoised mono 16-bit PCM WAV and a PNG spectrogram rendered from it.
 */
data class DenoisedArtifacts(
    val audioFile: File,
    val spectrogramFile: File,
)

/**
 * Builds (and caches under [filesDir]) the denoise-preview artifacts for a draft.
 * Reads the original WAV via [WavReader.readMono16], runs [denoiseFull], and
 * writes the result back as 16-bit PCM. Renders a PNG spectrogram from the
 * denoised samples via [SpectrogramRenderer] / [SpectrogramBitmap].
 *
 * Cache: existing files are returned as-is, the (potentially heavy) FFT/render
 * pass runs only once per draft per app install. Bust the cache by deleting
 * the files on disk if the algorithm changes.
 */
internal object DenoisedArtifactsBuilder {

    private const val DENOISE_AUDIO_DIR = "denoised_preview"
    private const val DENOISE_SPEC_DIR = "denoised_spectrograms"

    fun build(
        sourceWav: File,
        draftId: String,
        filesDir: File,
        renderer: SpectrogramRenderer = SpectrogramRenderer(),
    ): DenoisedArtifacts {
        val audioDir = File(filesDir, DENOISE_AUDIO_DIR).apply { mkdirs() }
        val pngDir = File(filesDir, DENOISE_SPEC_DIR).apply { mkdirs() }
        val audioFile = File(audioDir, "$draftId.wav")
        val pngFile = File(pngDir, "$draftId.png")

        val audioCached = audioFile.exists() && audioFile.length() > 0L
        val pngCached = pngFile.exists() && pngFile.length() > 0L
        if (audioCached && pngCached) return DenoisedArtifacts(audioFile, pngFile)

        val (shorts, sampleRate) = WavReader.readMono16(sourceWav)
        val floats = FloatArray(shorts.size) { i -> shorts[i] / Short.MAX_VALUE.toFloat() }
        val denoised = denoiseFull(floats, sampleRate)

        if (!audioCached) writeWav(audioFile, denoised, sampleRate)
        if (!pngCached) {
            val pixels = renderer.render(denoised)
            if (pixels.isNotEmpty()) SpectrogramBitmap.writePng(pixels, pngFile)
        }
        return DenoisedArtifacts(audioFile, pngFile)
    }

    private fun writeWav(target: File, floats: FloatArray, sampleRate: Int) {
        val writer = WavWriter(target, sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        val shorts = ShortArray(floats.size)
        for (i in floats.indices) {
            val v = (floats[i] * Short.MAX_VALUE.toFloat()).toInt()
            shorts[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        writer.writeShorts(shorts, 0, shorts.size)
        writer.close()
    }
}
