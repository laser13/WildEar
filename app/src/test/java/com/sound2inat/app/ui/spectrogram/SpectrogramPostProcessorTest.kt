package com.sound2inat.app.ui.spectrogram

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpectrogramPostProcessorTest {

    @Test
    fun `subtractFrequencyMedian returns empty for empty input`() {
        val result = SpectrogramPostProcessor.subtractFrequencyMedian(emptyArray())
        assertThat(result).isEmpty()
    }

    @Test
    fun `subtractFrequencyMedian handles empty row`() {
        val mel = arrayOf(FloatArray(0))
        val result = SpectrogramPostProcessor.subtractFrequencyMedian(mel)
        assertThat(result[0]).isEmpty()
    }

    @Test
    fun `subtractFrequencyMedian constant row becomes all zeros`() {
        val mel = arrayOf(FloatArray(10) { -40f })
        val result = SpectrogramPostProcessor.subtractFrequencyMedian(mel)
        result[0].forEach { assertThat(it).isWithin(1e-5f).of(0f) }
    }

    @Test
    fun `subtractFrequencyMedian removes constant background preserving peaks`() {
        // Row: background at -60 dB, single peak at -20 dB in the middle
        val background = -60f
        val peak = -20f
        val row = FloatArray(10) { background }
        row[5] = peak
        val mel = arrayOf(row)

        val result = SpectrogramPostProcessor.subtractFrequencyMedian(mel)[0]

        // Median of the row is background (-60), so background bins → 0
        result.forEachIndexed { i, v ->
            if (i != 5) assertThat(v).isWithin(1e-5f).of(0f)
        }
        // Peak survives: -20 - (-60) = 40 dB above floor
        assertThat(result[5]).isWithin(1e-5f).of(peak - background)
    }

    @Test
    fun `subtractFrequencyMedian does not mutate original input`() {
        val row = FloatArray(6) { it.toFloat() }
        val original = row.copyOf()
        val mel = arrayOf(row)
        SpectrogramPostProcessor.subtractFrequencyMedian(mel)
        assertThat(mel[0]).isEqualTo(original)
    }

    @Test
    fun `subtractFrequencyMedian processes each row independently`() {
        // Two rows with different baselines
        val mel = arrayOf(
            FloatArray(4) { -50f }, // row 0: all -50 dB
            FloatArray(4) { -30f }, // row 1: all -30 dB
        )
        val result = SpectrogramPostProcessor.subtractFrequencyMedian(mel)
        result.forEach { row -> row.forEach { assertThat(it).isWithin(1e-5f).of(0f) } }
    }

    @Test
    fun `applyNoiseFloor NONE returns input unchanged`() {
        val mel = arrayOf(FloatArray(4) { it.toFloat() })
        val result = SpectrogramPostProcessor.applyNoiseFloor(mel, SpectrogramNoiseFloorMode.NONE)
        assertThat(result).isSameInstanceAs(mel)
    }

    @Test
    fun `applyNoiseFloor PER_COLUMN_MEDIAN returns input unchanged (live-only mode)`() {
        val mel = arrayOf(FloatArray(4) { it.toFloat() })
        val result = SpectrogramPostProcessor.applyNoiseFloor(mel, SpectrogramNoiseFloorMode.PER_COLUMN_MEDIAN)
        assertThat(result).isSameInstanceAs(mel)
    }

    @Test
    fun `applyNoiseFloor PER_FREQUENCY_MEDIAN delegates to subtractFrequencyMedian`() {
        val mel = arrayOf(FloatArray(4) { -50f })
        val result = SpectrogramPostProcessor.applyNoiseFloor(mel, SpectrogramNoiseFloorMode.PER_FREQUENCY_MEDIAN)
        result[0].forEach { assertThat(it).isWithin(1e-5f).of(0f) }
    }

    @Test
    fun `subtractFrequencyPercentile uses lower background estimate than median`() {
        val row = floatArrayOf(-70f, -70f, -60f, -30f, -20f)
        val result = SpectrogramPostProcessor.subtractFrequencyPercentile(
            arrayOf(row),
            percentile = 20f,
        )[0]

        assertThat(result[0]).isWithin(1e-5f).of(0f)
        assertThat(result[2]).isWithin(1e-5f).of(10f)
        assertThat(result[4]).isWithin(1e-5f).of(50f)
    }

    @Test
    fun `applyNoiseFloor PER_FREQUENCY_PERCENTILE delegates to configured percentile`() {
        val row = floatArrayOf(-70f, -70f, -60f, -30f, -20f)
        val result = SpectrogramPostProcessor.applyNoiseFloor(
            mel = arrayOf(row),
            mode = SpectrogramNoiseFloorMode.PER_FREQUENCY_PERCENTILE,
            noiseFloorPercentile = 20f,
        )[0]

        assertThat(result[2]).isWithin(1e-5f).of(10f)
    }

    @Test
    fun `applyDisplayCurve gates weak differences and gamma softens them`() {
        val values = floatArrayOf(0f, 6f, 8f, 18f, 38f, 50f)
        val result = SpectrogramPostProcessor.applyDisplayCurve(
            values = values,
            gateDb = 8f,
            displayRangeDb = 30f,
            gamma = 1.5f,
        )

        assertThat(result[0]).isEqualTo(0f)
        assertThat(result[1]).isEqualTo(0f)
        assertThat(result[2]).isEqualTo(0f)
        assertThat(result[3]).isWithin(1e-5f).of(0.19245f)
        assertThat(result[4]).isEqualTo(1f)
        assertThat(result[5]).isEqualTo(1f)
    }

    @Test
    fun `smoothNormalized averages a subtle local neighbourhood without mutating input`() {
        val matrix = arrayOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0f),
        )
        val smoothed = SpectrogramPostProcessor.smoothNormalized(
            matrix,
            timeRadius = 1,
            frequencyRadius = 1,
        )

        assertThat(smoothed[1][1]).isWithin(1e-5f).of(1f / 9f)
        assertThat(matrix[1][1]).isEqualTo(1f)
    }
}
