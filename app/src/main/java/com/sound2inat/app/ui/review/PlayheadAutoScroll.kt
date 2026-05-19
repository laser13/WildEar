package com.sound2inat.app.ui.review

/**
 * Pure decision: given the current cursor position on the spectrogram strip,
 * the current scroll value and viewport, return whether auto-follow should
 * be on and what (if any) new scroll value to apply. Kept Compose-free so it
 * can be unit-tested on the JVM.
 *
 * Behaviour:
 *  - Auto-follow ON  → cursor stays centered in the viewport; near edges the
 *    target is clamped so the cursor can travel from edge to centre and back.
 *  - Auto-follow OFF (user scrolled manually) → no scroll change until the
 *    cursor re-enters the visible window, at which point auto-follow re-arms.
 */
internal object PlayheadAutoScroll {
    data class Decision(
        val newAutoFollow: Boolean,
        val targetScroll: Int?,
    )

    fun decide(
        autoFollow: Boolean,
        cursorPx: Float,
        currentScroll: Int,
        viewportSize: Int,
        maxScroll: Int,
    ): Decision {
        if (viewportSize <= 0 || maxScroll <= 0) {
            return Decision(newAutoFollow = autoFollow, targetScroll = null)
        }

        val left = currentScroll.toFloat()
        val right = left + viewportSize
        val cursorVisible = cursorPx in left..right
        val effectiveAuto = autoFollow || cursorVisible
        if (!effectiveAuto) {
            return Decision(newAutoFollow = false, targetScroll = null)
        }

        val centeredTarget = (cursorPx - viewportSize / 2f).toInt()
            .coerceIn(0, maxScroll)
        val target = if (centeredTarget == currentScroll) null else centeredTarget
        return Decision(newAutoFollow = true, targetScroll = target)
    }
}
