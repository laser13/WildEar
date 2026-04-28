package com.sound2inat.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class WavWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `header is 44 bytes with correct RIFF and data chunk sizes`() {
        val file = tmp.newFile("rec.wav")
        val writer = WavWriter(file, sampleRate = 48_000, channels = 1, bitsPerSample = 16)
        writer.open()
        val silence = ByteArray(48_000 * 2)
        writer.writeBytes(silence, 0, silence.size)
        writer.close()

        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44).also { raf.readFully(it) }
            assertThat(String(header, 0, 4)).isEqualTo("RIFF")
            assertThat(String(header, 8, 4)).isEqualTo("WAVE")
            assertThat(String(header, 12, 4)).isEqualTo("fmt ")
            assertThat(String(header, 36, 4)).isEqualTo("data")
            val riffSize = readLeUint32(header, 4)
            val dataSize = readLeUint32(header, 40)
            assertThat(dataSize).isEqualTo(silence.size)
            assertThat(riffSize).isEqualTo(silence.size + 36)
        }
        assertThat(file.length()).isEqualTo(44L + silence.size)
    }

    @Test
    fun `writeShorts encodes little-endian`() {
        val file = tmp.newFile("rec.wav")
        val w = WavWriter(file, 48_000, 1, 16)
        w.open()
        w.writeShorts(shortArrayOf(0x1234, -1, 0x7FFF), 0, 3)
        w.close()
        val bytes = file.readBytes()
        val data = bytes.copyOfRange(44, bytes.size)
        assertThat(data.toList()).containsExactly(
            0x34.toByte(),
            0x12.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x7F.toByte(),
        ).inOrder()
    }
}

private fun readLeUint32(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)
