package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavPcmReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeWav(samples: ShortArray, sampleRate: Int = 48_000): File {
        val file = tmp.newFile("pcm_${samples.size}_$sampleRate.wav")
        val writer = WavWriter(file, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        writer.open()
        writer.writeShorts(samples, 0, samples.size)
        writer.close()
        return file
    }

    @Test
    fun `readHeader returns sample rate and total samples`() {
        val samples = ShortArray(4_800) { (it % 50).toShort() }
        val wav = writeWav(samples, sampleRate = 48_000)

        val header = WavPcmReader.readHeader(wav)

        assertThat(header.sampleRateHz).isEqualTo(48_000)
        assertThat(header.totalSamples).isEqualTo(4_800L)
    }

    @Test
    fun `readMono16 returns samples and sample rate`() {
        val samples = ShortArray(1_000) { ((it * 7) % 65_535 - 32_768).toShort() }
        val wav = writeWav(samples, sampleRate = 32_000)

        val (out, sr) = WavPcmReader.readMono16(wav)

        assertThat(sr).isEqualTo(32_000)
        assertThat(out.toList()).isEqualTo(samples.toList())
    }

    @Test
    fun `stream reassembles the full sample sequence in order`() {
        val samples = ShortArray(40_000) { ((it * 13) % 65_535 - 32_768).toShort() }
        val wav = writeWav(samples)

        val collected = ArrayList<Short>(samples.size)
        WavPcmReader.stream(wav) { chunk, startSample ->
            assertThat(startSample.toInt()).isEqualTo(collected.size)
            for (s in chunk) collected.add(s)
        }

        assertThat(collected).isEqualTo(samples.toList())
    }

    @Test
    fun `stream clamps to declared dataSize even if file has trailing bytes`() {
        // Write a valid 100-sample WAV, then append 8 junk bytes after the data
        // chunk. The legacy ReviewViewModel.streamMono16 trusted dataSize but did
        // not guard the read against the actual file length; the new reader must
        // emit exactly the declared 100 samples and ignore trailing junk.
        val samples = ShortArray(100) { (it + 1).toShort() }
        val wav = writeWav(samples)
        wav.appendBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        var total = 0L
        WavPcmReader.stream(wav) { chunk, _ -> total += chunk.size }

        assertThat(total).isEqualTo(100L)
    }

    @Test
    fun `readMono16 and stream agree byte-for-byte`() {
        val samples = ShortArray(20_001) { ((it * 31) % 65_535 - 32_768).toShort() }
        val wav = writeWav(samples)

        val (whole, _) = WavPcmReader.readMono16(wav)
        val streamed = ArrayList<Short>(samples.size)
        WavPcmReader.stream(wav) { chunk, _ -> for (s in chunk) streamed.add(s) }

        assertThat(streamed).isEqualTo(whole.toList())
    }

    @Test
    fun `readHeader rejects stereo WAV`() {
        val file = tmp.newFile("stereo.wav")
        val header = ByteArray(44)
        System.arraycopy("RIFF".toByteArray(Charsets.US_ASCII), 0, header, 0, 4)
        System.arraycopy("WAVE".toByteArray(Charsets.US_ASCII), 0, header, 8, 4)
        System.arraycopy("fmt ".toByteArray(Charsets.US_ASCII), 0, header, 12, 4)
        header[16] = 16
        header[20] = 1
        header[22] = 2 // channels = 2
        header[34] = 16
        System.arraycopy("data".toByteArray(Charsets.US_ASCII), 0, header, 36, 4)
        file.writeBytes(header)

        try {
            WavPcmReader.readHeader(file)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Mono 16-bit PCM only")
        }
    }
}
