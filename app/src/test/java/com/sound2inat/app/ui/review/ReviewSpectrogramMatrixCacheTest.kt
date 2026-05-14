package com.sound2inat.app.ui.review

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ReviewSpectrogramMatrixCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `first call builds and writes cache`() {
        val analyzerCalls = AtomicInteger(0)
        val readSamplesCalls = AtomicInteger(0)
        val cache = ReviewSpectrogramMatrixCache(analyze = { _, config ->
            analyzerCalls.incrementAndGet()
            sampleMatrix(config)
        })
        val audioFile = createAudioFile("first.wav")
        val config = analysisConfig()

        val matrix = runSuspend {
            cache.getOrCreate(audioFile, "draft-1", tmp.root, config) {
                readSamplesCalls.incrementAndGet()
                sampleSamples()
            }
        }

        assertThat(matrix.frames).isGreaterThan(0)
        assertThat(analyzerCalls.get()).isEqualTo(1)
        assertThat(readSamplesCalls.get()).isEqualTo(1)
        assertThat(cache.cacheFile(audioFile, "draft-1", tmp.root, config).exists()).isTrue()
    }

    @Test
    fun `second call with same identity loads cache without reanalyzing`() {
        val analyzerCalls = AtomicInteger(0)
        val readSamplesCalls = AtomicInteger(0)
        val cache = ReviewSpectrogramMatrixCache(analyze = { _, config ->
            analyzerCalls.incrementAndGet()
            sampleMatrix(config)
        })
        val audioFile = createAudioFile("second.wav")
        val config = analysisConfig()

        runSuspend {
            cache.getOrCreate(audioFile, "draft-1", tmp.root, config) {
                readSamplesCalls.incrementAndGet()
                sampleSamples()
            }
        }
        runSuspend {
            cache.getOrCreate(audioFile, "draft-1", tmp.root, config) {
                readSamplesCalls.incrementAndGet()
                sampleSamples()
            }
        }

        assertThat(analyzerCalls.get()).isEqualTo(1)
        assertThat(readSamplesCalls.get()).isEqualTo(1)
    }

    @Test
    fun `corrupt cache file is deleted and rebuilt`() {
        val analyzerCalls = AtomicInteger(0)
        val readSamplesCalls = AtomicInteger(0)
        val cache = ReviewSpectrogramMatrixCache(analyze = { _, config ->
            analyzerCalls.incrementAndGet()
            sampleMatrix(config)
        })
        val audioFile = createAudioFile("corrupt.wav")
        val config = analysisConfig()
        val cacheFile = cache.cacheFile(audioFile, "draft-1", tmp.root, config)
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeBytes(byteArrayOf(1, 2, 3))

        runSuspend {
            cache.getOrCreate(audioFile, "draft-1", tmp.root, config) {
                readSamplesCalls.incrementAndGet()
                sampleSamples()
            }
        }

        assertThat(analyzerCalls.get()).isEqualTo(1)
        assertThat(readSamplesCalls.get()).isEqualTo(1)
        assertThat(cacheFile.exists()).isTrue()
    }

    @Test
    fun `visual-only changes do not affect the matrix cache key`() {
        val cache = ReviewSpectrogramMatrixCache()
        val audioFile = createAudioFile("visuals.wav")
        val bright = ReviewSpectrogramConfig.BirdDefault.copy(palette = com.sound2inat.app.ui.spectrogram.SpectrogramPalette.MAGMA, gainDb = 12f)
        val muted = bright.copy(palette = com.sound2inat.app.ui.spectrogram.SpectrogramPalette.GRAY, gainDb = -6f)
        val brightConfig = ReviewSpectrogramAnalysisConfig.from(bright.displayRange, sampleRateHz = 48_000)
        val mutedConfig = ReviewSpectrogramAnalysisConfig.from(muted.displayRange, sampleRateHz = 48_000)

        assertThat(cache.cacheKey(audioFile, brightConfig)).isEqualTo(cache.cacheKey(audioFile, mutedConfig))
    }

    @Test
    fun `changing display range forces a new matrix cache entry`() {
        val cache = ReviewSpectrogramMatrixCache()
        val audioFile = createAudioFile("display-range.wav")
        val wideConfig = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.FULL, sampleRateHz = 48_000)
        val birdConfig = ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)

        assertThat(cache.cacheKey(audioFile, wideConfig)).isNotEqualTo(cache.cacheKey(audioFile, birdConfig))
    }

    private fun analysisConfig(): ReviewSpectrogramAnalysisConfig =
        ReviewSpectrogramAnalysisConfig.from(SpectrogramDisplayRange.BIRDNET_BIRD, sampleRateHz = 48_000)

    private fun sampleSamples(): FloatArray = FloatArray(48_000) { i -> (i % 128).toFloat() / 128f }

    private fun sampleMatrix(config: ReviewSpectrogramAnalysisConfig): ReviewSpectrogramMatrix {
        val rows = config.displayHeightBins
        val frames = 8
        val values = Array(rows) { row ->
            FloatArray(frames) { frame -> ((row + frame) % 32).toFloat() / 32f }
        }
        return ReviewSpectrogramMatrix(config = config, frames = frames, values = values)
    }

    private fun createAudioFile(name: String): File =
        tmp.newFile(name).apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
