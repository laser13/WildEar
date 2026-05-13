package com.sound2inat.app.ui.review

import kotlin.math.roundToLong

internal object ReviewSpectrogramTimeline {
    private const val MIN_CONTENT_WIDTH_PX = 1_200
    private const val MAX_CONTENT_WIDTH_PX = 12_000
    private const val PX_PER_MS_DIVISOR = 10L

    fun contentWidthPx(durationMs: Long): Int {
        val scaledWidthPx = durationMs / PX_PER_MS_DIVISOR
        return scaledWidthPx
            .coerceIn(MIN_CONTENT_WIDTH_PX.toLong(), MAX_CONTENT_WIDTH_PX.toLong())
            .toInt()
    }

    fun seekMsFromTap(
        tapX: Float,
        horizontalScrollPx: Float,
        contentWidthPx: Int,
        durationMs: Long,
    ): Long {
        if (contentWidthPx <= 0 || durationMs <= 0L) {
            return 0L
        }

        val absoluteX = (tapX + horizontalScrollPx)
            .coerceIn(0f, contentWidthPx.toFloat())
        val seekRatio = absoluteX / contentWidthPx.toFloat()

        return (seekRatio * durationMs.toDouble())
            .roundToLong()
            .coerceIn(0L, durationMs)
    }
}
