package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.audio.WavPcmReader
import com.sound2inat.audio.WavWindowReader
import com.sound2inat.recorder.WavWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import kotlin.math.sin

/**
 * Direct tests for [WavWindowReader]. Covers:
 * - Byte-exact equivalence with [com.sound2inat.audio.WavPcmReader.readMono16] when windowing overlapping ranges.
 * - Zero-padding of the trailing partial window (the legacy full-buffer path produced
 *   the same behaviour implicitly because `ShortArray.copyOfRange` past the end is
 *   not allowed — the runner historically clipped frames; this test pins the contract
 *   for the explicit out-of-range read.
 * - Random-access seeks deep into a synthetic 60 s WAV.
 */
class WavReaderStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeWav(samples: ShortArray, sampleRate: Int = 48_000): File {
        val file = tmp.newFile("test_${samples.size}.wav")
        val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.writeShorts(samples, 0, samples.size)
        writer.close()
        return file
    }

    private fun sineSamples(durationSec: Int, sampleRate: Int = 48_000, freq: Double = 440.0): ShortArray {
        val n = durationSec * sampleRate
        return ShortArray(n) { i ->
            (10_000.0 * sin(2.0 * Math.PI * freq * i / sampleRate)).toInt().toShort()
        }
    }

    @Test
    fun `reads windows from a clean WAV produced by WavWriter and matches the full-buffer reader`() {
        val samples = sineSamples(durationSec = 2, sampleRate = 48_000, freq = 880.0)
        val wav = writeWav(samples)

        val (full, sr) = WavPcmReader.readMono16(wav)
        assertThat(sr).isEqualTo(48_000)
        assertThat(full.size).isEqualTo(samples.size)

        WavWindowReader.open(wav).use { reader ->
            assertThat(reader.sampleRate).isEqualTo(48_000)
            assertThat(reader.totalSamples).isEqualTo(samples.size)

            // Three overlapping windows, 3 s window @ wouldn't fit — use 500 ms / 250 ms hop.
            val win = 24_000 // 500 ms
            val hop = 12_000 // 250 ms
            for (f in 0..5) {
                val start = f * hop
                val expected = full.copyOfRange(start, start + win)
                val actual = reader.readNative(start, win)
                assertThat(actual.toList()).isEqualTo(expected.toList())
            }
        }
    }

    @Test
    fun `last partial window is zero-padded past totalSamples`() {
        // 1.5 windows worth of data — 1.5 * 24_000 = 36_000 samples.
        val samples = ShortArray(36_000) { i -> (i % 100).toShort() }
        val wav = writeWav(samples)

        WavWindowReader.open(wav).use { reader ->
            val win = 24_000
            // First window: fully in range.
            val w0 = reader.readNative(0, win)
            assertThat(w0.size).isEqualTo(win)
            for (i in 0 until win) assertThat(w0[i].toInt()).isEqualTo(i % 100)

            // Second window starts at 24_000, covers 12_000 valid + 12_000 zero-padded.
            val w1 = reader.readNative(win, win)
            assertThat(w1.size).isEqualTo(win)
            for (i in 0 until 12_000) {
                assertThat(w1[i].toInt()).isEqualTo((i + win) % 100)
            }
            for (i in 12_000 until win) {
                assertThat(w1[i].toInt()).isEqualTo(0)
            }
        }
    }

    @Test
    fun `seek to high offset returns expected samples in a 60 s WAV`() {
        val sr = 48_000
        // 60 s at 48 kHz = 2_880_000 samples = 5.76 MB on disk. Use a deterministic
        // pattern so we can assert exact equality without re-computing.
        val total = 60 * sr
        val samples = ShortArray(total) { i -> ((i * 31) % 65_535 - 32_768).toShort() }
        val wav = writeWav(samples, sampleRate = sr)

        WavWindowReader.open(wav).use { reader ->
            assertThat(reader.totalSamples).isEqualTo(total)

            // Read a 3 s window starting at 30 s in.
            val start = 30 * sr
            val win = 3 * sr
            val read = reader.readNative(start, win)
            assertThat(read.size).isEqualTo(win)
            for (i in 0 until win) {
                assertThat(read[i].toInt()).isEqualTo(samples[start + i].toInt())
            }
        }
    }

    @Test
    fun `reading zero count yields empty array without seeking`() {
        val wav = writeWav(ShortArray(1_000) { it.toShort() })
        WavWindowReader.open(wav).use { reader ->
            assertThat(reader.readNative(500, 0)).isEmpty()
        }
    }

    @Test
    fun `reading entirely past totalSamples returns all zeros`() {
        val wav = writeWav(ShortArray(100) { (it + 1).toShort() })
        WavWindowReader.open(wav).use { reader ->
            val out = reader.readNative(200, 50)
            assertThat(out.size).isEqualTo(50)
            for (s in out) assertThat(s.toInt()).isEqualTo(0)
        }
    }

    @Test
    fun `streaming resampler 48k to 32k matches legacy whole-file resample`() {
        // 4 s of sine at 48 kHz → 32 kHz. Compare each 1 s window (no overlap)
        // against the legacy path: whole-file read → Resampler.resample →
        // /Short.MAX_VALUE normalisation → copyOfRange.
        val sr = 48_000
        val target = 32_000
        val samples = sineSamples(durationSec = 4, sampleRate = sr, freq = 440.0)
        val wav = writeWav(samples, sampleRate = sr)

        // Legacy path.
        val (raw, gotSr) = WavPcmReader.readMono16(wav)
        assertThat(gotSr).isEqualTo(sr)
        val resampled = Resampler.resample(raw, sr, target)
        val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }

        val win = target // 1 s at target rate
        val ratio = sr.toDouble() / target.toDouble()
        WavWindowReader.open(wav).use { reader ->
            for (f in 0 until 3) {
                val s = f * win
                val streamWin = StreamingResampler.readResampledWindow(reader, ratio, s, win)
                val legacyWin = normalized.copyOfRange(s, s + win)
                assertThat(streamWin.size).isEqualTo(legacyWin.size)
                for (i in 0 until win) {
                    assertThat(streamWin[i]).isEqualTo(legacyWin[i])
                }
            }
        }
    }

    @Test
    fun `streaming resampler equals legacy at the very last window past resampled length`() {
        // Tail window: ensure that when lastSrc maps onto totalNative-1 (or just past),
        // the streaming resampler still produces the same clamped/zero-padded values as
        // the legacy whole-file path would.
        val sr = 48_000
        val target = 32_000
        val samples = sineSamples(durationSec = 2, sampleRate = sr, freq = 880.0)
        val wav = writeWav(samples, sampleRate = sr)

        val (raw, _) = WavPcmReader.readMono16(wav)
        val resampled = Resampler.resample(raw, sr, target)
        val normalized = FloatArray(resampled.size) { i -> resampled[i] / Short.MAX_VALUE.toFloat() }

        val ratio = sr.toDouble() / target.toDouble()
        // Pick a window aligned with the very end of the resampled buffer.
        val win = 16_000 // 500 ms
        val resampledStart = resampled.size - win
        WavWindowReader.open(wav).use { reader ->
            val streamWin = StreamingResampler.readResampledWindow(reader, ratio, resampledStart, win)
            val legacyWin = normalized.copyOfRange(resampledStart, resampledStart + win)
            for (i in 0 until win) {
                assertThat(streamWin[i]).isEqualTo(legacyWin[i])
            }
        }
    }

    @Test
    fun `open rejects non-WAV file`() {
        val file = tmp.newFile("garbage.wav")
        file.writeBytes(ByteArray(44)) // zeroed header, no RIFF/WAVE magic
        try {
            WavWindowReader.open(file)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("WAV")
        }
    }

    @Test
    fun `open rejects stereo WAV`() {
        val file = tmp.newFile("stereo.wav")
        // Build a minimal 44-byte stereo WAV header (ch=2) followed by some data.
        val header = ByteArray(44)
        System.arraycopy("RIFF".toByteArray(Charsets.US_ASCII), 0, header, 0, 4)
        // riffSize at 4..7 (don't care for the test)
        System.arraycopy("WAVE".toByteArray(Charsets.US_ASCII), 0, header, 8, 4)
        System.arraycopy("fmt ".toByteArray(Charsets.US_ASCII), 0, header, 12, 4)
        // fmt chunk size 16
        header[16] = 16
        // PCM format = 1 at offset 20
        header[20] = 1
        // ch = 2 at offset 22
        header[22] = 2
        // bits = 16 at offset 34
        header[34] = 16
        System.arraycopy("data".toByteArray(Charsets.US_ASCII), 0, header, 36, 4)
        file.writeBytes(header)
        try {
            WavWindowReader.open(file).close()
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Mono 16-bit PCM only")
        }
    }

    @Test
    fun `open rejects truncated file`() {
        val file = tmp.newFile("trunc.wav")
        file.writeBytes(ByteArray(20)) // < 44 bytes
        try {
            WavWindowReader.open(file).close()
            error("Expected EOFException")
        } catch (e: EOFException) {
            // expected — truncated header triggers readFully to throw EOFException
        }
    }
}
