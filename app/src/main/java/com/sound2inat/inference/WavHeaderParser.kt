package com.sound2inat.inference

/** Internal helpers for parsing little-endian WAV header fields. */
internal object WavHeaderParser {
    fun readLeUint16(buf: ByteArray, o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    fun readLeUint32(buf: ByteArray, o: Int): Long =
        (buf[o].toLong() and 0xFF) or
            ((buf[o + 1].toLong() and 0xFF) shl 8) or
            ((buf[o + 2].toLong() and 0xFF) shl 16) or
            ((buf[o + 3].toLong() and 0xFF) shl 24)
}
