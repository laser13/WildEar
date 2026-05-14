package com.sound2inat.app.ui.review

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Loads or builds reusable spectrogram matrices for immutable audio artifacts.
 */
class ReviewSpectrogramMatrixCache(
    private val analyze: (FloatArray, ReviewSpectrogramAnalysisConfig) -> ReviewSpectrogramMatrix =
        ReviewSpectrogramAnalyzer()::analyze,
) {
    suspend fun getOrCreate(
        audioFile: File,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramAnalysisConfig,
        readSamples: suspend (File) -> FloatArray,
    ): ReviewSpectrogramMatrix {
        val cacheFile = cacheFile(audioFile, draftId, filesDir, config)
        readMatrixFromCache(cacheFile, expectedMetadata(audioFile, config))?.let { return it }

        val samples = readSamples(audioFile)
        val matrix = analyze(samples, config)
        writeCache(cacheFile, expectedMetadata(audioFile, config), matrix)
        return matrix
    }

    internal fun cacheKey(audioFile: File, config: ReviewSpectrogramAnalysisConfig): String =
        sha256Hex(expectedMetadata(audioFile, config))

    internal fun cacheFile(
        audioFile: File,
        draftId: String,
        filesDir: File,
        config: ReviewSpectrogramAnalysisConfig,
    ): File = File(File(File(filesDir, CACHE_ROOT), draftId), "${cacheKey(audioFile, config)}.wsmc")

    private fun expectedMetadata(
        audioFile: File,
        config: ReviewSpectrogramAnalysisConfig,
    ): String = buildString {
        appendLine("cache_version=${config.version}")
        appendLine("audio_path=${audioFile.absolutePath}")
        appendLine("audio_size=${audioFile.length()}")
        appendLine("audio_last_modified=${audioFile.lastModified()}")
        appendLine("fft_size=${config.fftSize}")
        appendLine("hop_size=${config.hopSize}")
        appendLine("display_height_bins=${config.displayHeightBins}")
        appendLine("min_frequency_hz=${config.minFrequencyHz}")
        appendLine("max_frequency_hz=${config.maxFrequencyHz}")
        appendLine("sample_rate_hz=${config.sampleRateHz}")
    }

    private fun readMatrixFromCache(
        cacheFile: File,
        expectedMetadata: String,
    ): ReviewSpectrogramMatrix? {
        if (!cacheFile.exists()) return null
        return try {
            DataInputStream(FileInputStream(cacheFile)).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC) return deleteAndNull(cacheFile)
                val version = input.readInt()
                if (version != FILE_FORMAT_VERSION) return deleteAndNull(cacheFile)
                val metadata = input.readUTF()
                if (metadata != expectedMetadata) return deleteAndNull(cacheFile)
                val rows = input.readInt()
                val frames = input.readInt()
                val config = readConfig(input)
                val values = Array(rows) { row ->
                    FloatArray(frames) { frame -> input.readFloat() }
                }
                ReviewSpectrogramMatrix(
                    config = config,
                    frames = frames,
                    values = values,
                )
            }
        } catch (_: EOFException) {
            deleteAndNull(cacheFile)
        } catch (_: Throwable) {
            deleteAndNull(cacheFile)
        }
    }

    private fun writeCache(
        cacheFile: File,
        metadata: String,
        matrix: ReviewSpectrogramMatrix,
    ) {
        cacheFile.parentFile?.mkdirs()
        val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            DataOutputStream(FileOutputStream(tmpFile)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FILE_FORMAT_VERSION)
                output.writeUTF(metadata)
                output.writeInt(matrix.values.size)
                output.writeInt(matrix.frames)
                writeConfig(output, matrix.config)
                for (row in matrix.values) {
                    for (value in row) output.writeFloat(value)
                }
                output.flush()
            }
            if (cacheFile.exists()) cacheFile.delete()
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.delete()
            }
        } catch (_: Throwable) {
            tmpFile.delete()
        }
    }

    private fun readConfig(input: DataInputStream): ReviewSpectrogramAnalysisConfig =
        ReviewSpectrogramAnalysisConfig(
            fftSize = input.readInt(),
            hopSize = input.readInt(),
            displayHeightBins = input.readInt(),
            minFrequencyHz = input.readInt(),
            maxFrequencyHz = input.readInt(),
            sampleRateHz = input.readInt(),
            version = input.readInt(),
        )

    private fun writeConfig(output: DataOutputStream, config: ReviewSpectrogramAnalysisConfig) {
        output.writeInt(config.fftSize)
        output.writeInt(config.hopSize)
        output.writeInt(config.displayHeightBins)
        output.writeInt(config.minFrequencyHz)
        output.writeInt(config.maxFrequencyHz)
        output.writeInt(config.sampleRateHz)
        output.writeInt(config.version)
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
        }
    }

    private fun deleteAndNull(file: File): ReviewSpectrogramMatrix? {
        file.delete()
        return null
    }

    companion object {
        private const val CACHE_ROOT = "spectrogram_matrix_cache"
        private const val MAGIC = 0x57534D43 // "WSMC"
        private const val FILE_FORMAT_VERSION = 1
    }
}
