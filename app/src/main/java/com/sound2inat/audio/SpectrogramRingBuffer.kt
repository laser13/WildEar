package com.sound2inat.audio

/**
 * Fixed-capacity ring buffer of dB columns, oldest-first when read by index.
 * Used by `LiveSpectrogramView` to render the last N columns as a scrolling
 * spectrogram. NOT thread-safe — synchronise externally if multiple threads
 * append.
 */
class SpectrogramRingBuffer(
    val capacity: Int,
    val bins: Int,
) {
    private val data = Array(capacity) { FloatArray(bins) }
    private var head = 0   // index of next slot to write
    var size: Int = 0
        private set

    fun append(column: FloatArray) {
        require(column.size == bins) { "expected $bins bins, got ${column.size}" }
        System.arraycopy(column, 0, data[head], 0, bins)
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Read column by logical index 0..size-1, oldest first. */
    fun column(index: Int): FloatArray {
        require(index in 0 until size)
        val pos = (head - size + index + capacity) % capacity
        return data[pos]
    }
}
