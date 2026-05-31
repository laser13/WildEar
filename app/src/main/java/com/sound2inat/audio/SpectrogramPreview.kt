package com.sound2inat.audio

class SpectrogramPreview(
    val width: Int,
    val height: Int,
    argb: IntArray,
) {
    val argb: IntArray = argb.copyOf()

    init {
        require(width >= 0) { "width must be non-negative" }
        require(height >= 0) { "height must be non-negative" }
        require(argb.size == width * height) { "argb size must match width * height" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpectrogramPreview) return false
        return width == other.width &&
            height == other.height &&
            argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int =
        31 * (31 * width + height) + argb.contentHashCode()

    companion object {
        fun fromRows(rows: Array<IntArray>): SpectrogramPreview {
            if (rows.isEmpty()) return SpectrogramPreview(width = 0, height = 0, argb = IntArray(0))
            val width = rows.first().size
            require(width > 0) { "rows must not be empty" }
            val flat = IntArray(width * rows.size)
            rows.forEachIndexed { rowIndex, row ->
                require(row.size == width) { "rows must all have the same width" }
                System.arraycopy(row, 0, flat, rowIndex * width, width)
            }
            return SpectrogramPreview(width = width, height = rows.size, argb = flat)
        }
    }
}
