package com.sound2inat.inat

import com.google.common.truth.Truth.assertThat
import com.sound2inat.recorder.WavWriter
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavTrimmerTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * Writes a 1-second mono-16 WAV at 48 kHz where each sample equals its
     * index (mod 32767). That gives the trim test something distinguishable
     * to verify offsets — sample[k] in the source must equal sample[k - start]
     * in the trimmed output.
     */
    private fun makeRamp(): java.io.File {
        val src = tmp.newFile("src.wav")
        val rate = 48_000
        val total = rate
        val data = ShortArray(total) { (it % Short.MAX_VALUE.toInt()).toShort() }
        val w = WavWriter(src, sampleRate = rate, channels = 1, bitsPerSample = 16)
        w.open()
        w.writeShorts(data, 0, data.size)
        w.close()
        return src
    }

    @Test fun `trim within bounds produces correct slice`() {
        val src = makeRamp()
        val dst = tmp.newFile("dst.wav")
        WavTrimmer.trimMono16(src.absolutePath, dst, startMs = 100, endMs = 200)
        // 100 ms @ 48 kHz = 4800 samples; expected duration = 100 ms ± rounding.
        val raf = java.io.RandomAccessFile(dst, "r")
        raf.use { f ->
            val header = ByteArray(44).also { f.readFully(it) }
            val dataSize = (header[40].toInt() and 0xFF) or
                ((header[41].toInt() and 0xFF) shl 8) or
                ((header[42].toInt() and 0xFF) shl 16) or
                ((header[43].toInt() and 0xFF) shl 24)
            assertThat(dataSize / 2).isEqualTo(4800)
            // First sample of the trimmed file = original sample at index 4800.
            val raw = ByteArray(2).also { f.readFully(it) }
            val first = ((raw[1].toInt() shl 8) or (raw[0].toInt() and 0xFF)).toShort()
            assertThat(first).isEqualTo(4800.toShort())
        }
    }

    @Test fun `trim clamps to file length`() {
        val src = makeRamp()
        val dst = tmp.newFile("dst.wav")
        // File is 1 s; ask for 800..2000 ms — should clamp the end to 1000 ms.
        WavTrimmer.trimMono16(src.absolutePath, dst, startMs = 800, endMs = 2000)
        val len = dst.length()
        // 200 ms × 48 kHz × 2 bytes = 19_200 + 44 header
        assertThat(len).isEqualTo(19_200 + 44)
    }

    @Test fun `empty range throws`() {
        val src = makeRamp()
        val dst = tmp.newFile("dst.wav")
        val ex = runCatching {
            WavTrimmer.trimMono16(src.absolutePath, dst, startMs = 2000, endMs = 3000)
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalStateException::class.java)
    }
}
