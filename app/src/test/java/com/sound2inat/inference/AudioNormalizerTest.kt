package com.sound2inat.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioNormalizerTest {

    @get:Rule val tmp = TemporaryFolder()

    // Helpers
    private fun wavFile(samples: ShortArray, sampleRate: Int = 44100): File {
        val f = tmp.newFile("in.wav")
        writeWav(samples, sampleRate, f)
        return f
    }

    @Test
    fun `normalizeSamples scales peak to Short MAX`() {
        val input = shortArrayOf(0, 100, -200, 50)
        val result = AudioNormalizer.normalizeSamples(input)
        assertEquals(Short.MAX_VALUE.toInt(), kotlin.math.abs(result[2].toInt()))
    }

    @Test
    fun `normalizeSamples passes through silence unchanged`() {
        val input = ShortArray(10) { 0 }
        val result = AudioNormalizer.normalizeSamples(input)
        assertTrue(result.all { it == 0.toShort() })
    }

    @Test
    fun `normalizeSamples does not clip already-at-max signal`() {
        // Peak is Short.MAX_VALUE (32767); scale = 1.0 exactly → no change.
        val input = shortArrayOf(Short.MAX_VALUE, -16383)
        val result = AudioNormalizer.normalizeSamples(input)
        assertEquals(Short.MAX_VALUE.toInt(), result[0].toInt())
        assertEquals(-16383, result[1].toInt())  // scale=1.0 so input is unchanged
    }

    @Test
    fun `normalizeFile produces WAV with peak at Short MAX`() {
        val src = wavFile(shortArrayOf(0, 1000, -500, 200))
        val dst = tmp.newFile("out.wav")
        AudioNormalizer.normalizeFile(src, dst)
        val (samples, _) = readWavForTest(dst)
        assertEquals(Short.MAX_VALUE.toInt(), samples.maxOf { kotlin.math.abs(it.toInt()) })
    }

    @Test
    fun `normalizeFile passes through silent WAV unchanged`() {
        val src = wavFile(ShortArray(100) { 0 })
        val dst = tmp.newFile("out.wav")
        AudioNormalizer.normalizeFile(src, dst)
        val (samples, _) = readWavForTest(dst)
        assertTrue(samples.all { it == 0.toShort() })
    }

    private fun readWavForTest(f: File) = WavReader.readMono16(f)
}
