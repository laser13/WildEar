package com.sound2inat.app.ui.spectrogram

import com.google.common.truth.Truth.assertThat
import com.sound2inat.audio.SpectrogramRingBuffer
import com.sound2inat.audio.SpectrogramVisualPipeline
import org.junit.Test
import kotlin.math.pow

class SpectrogramVisualPipelineTest {

    @Test
    fun `logBinDown ignores bins below configured minimum frequency`() {
        val src = FloatArray(1025)
        src[4] = 100f // about 94 Hz for 2048-point FFT at 48 kHz
        src[43] = 20f // about 1 kHz

        val out = SpectrogramVisualPipeline.logBinDown(
            src = src,
            outBins = 32,
            sampleRateHz = 48_000,
            minFrequencyHz = 600,
            maxFrequencyHz = 10_000,
        )

        assertThat(out.max()).isEqualTo(20f)
    }

    @Test
    fun `display curve applies gate range and gamma`() {
        assertThat(
            SpectrogramVisualPipeline.displayValue(
                dbAboveFloor = 6f,
                gateDb = 6f,
                displayRangeDb = 30f,
                gamma = 1.5f,
            ),
        ).isEqualTo(0f)
        assertThat(
            SpectrogramVisualPipeline.displayValue(
                dbAboveFloor = 18f,
                gateDb = 6f,
                displayRangeDb = 30f,
                gamma = 1.5f,
            ),
        ).isWithin(1e-5f).of((12f / 30f).pow(1.5f))
    }

    @Test
    fun `smoothedRingValue averages a local neighborhood`() {
        val ring = SpectrogramRingBuffer(capacity = 3, bins = 3)
        ring.append(floatArrayOf(1f, 2f, 3f))
        ring.append(floatArrayOf(4f, 5f, 6f))
        ring.append(floatArrayOf(7f, 8f, 9f))

        val value = SpectrogramVisualPipeline.smoothedRingValue(
            ring = ring,
            x = 1,
            y = 1,
            timeRadius = 1,
            frequencyRadius = 1,
        )

        assertThat(value).isWithin(1e-5f).of(5f)
    }
}
