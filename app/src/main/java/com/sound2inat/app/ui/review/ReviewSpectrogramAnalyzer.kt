package com.sound2inat.app.ui.review

import com.sound2inat.app.ui.spectrogram.SpectrogramVisualPipeline
import com.sound2inat.audio.Spectrogram

/**
 * Builds reusable review spectrogram matrices from mono audio samples.
 */
class ReviewSpectrogramAnalyzer {
    fun analyze(
        samples: FloatArray,
        config: ReviewSpectrogramAnalysisConfig,
    ): ReviewSpectrogramMatrix {
        if (samples.size < config.fftSize) {
            return emptyMatrix(config)
        }

        val columns = Spectrogram(
            fftSize = config.fftSize,
            hopSize = config.hopSize,
            sampleRateHz = config.sampleRateHz,
        ).process(samples)

        val frames = columns.size
        if (frames == 0) return emptyMatrix(config)

        val matrix = Array(config.displayHeightBins) { FloatArray(frames) }
        columns.forEachIndexed { frame, column ->
            val binned = SpectrogramVisualPipeline.logBinDown(
                src = column,
                outBins = config.displayHeightBins,
                sampleRateHz = config.sampleRateHz,
                minFrequencyHz = config.minFrequencyHz,
                maxFrequencyHz = config.maxFrequencyHz,
            )
            for (row in 0 until config.displayHeightBins) {
                matrix[row][frame] = binned[row]
            }
        }
        return ReviewSpectrogramMatrix(config = config, frames = frames, values = matrix)
    }

    private fun emptyMatrix(config: ReviewSpectrogramAnalysisConfig): ReviewSpectrogramMatrix =
        ReviewSpectrogramMatrix(
            config = config,
            frames = 0,
            values = Array(config.displayHeightBins) { FloatArray(0) },
        )
}
