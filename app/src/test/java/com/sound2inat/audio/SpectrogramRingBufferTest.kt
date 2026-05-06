package com.sound2inat.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpectrogramRingBufferTest {
    @Test
    fun `appends columns up to capacity`() {
        val r = SpectrogramRingBuffer(capacity = 3, bins = 4)
        r.append(floatArrayOf(1f, 1f, 1f, 1f))
        r.append(floatArrayOf(2f, 2f, 2f, 2f))
        assertThat(r.size).isEqualTo(2)
        assertThat(r.column(0)[0]).isEqualTo(1f)
        assertThat(r.column(1)[0]).isEqualTo(2f)
    }

    @Test
    fun `overflow drops oldest`() {
        val r = SpectrogramRingBuffer(capacity = 3, bins = 4)
        for (k in 1..5) r.append(FloatArray(4) { k.toFloat() })
        assertThat(r.size).isEqualTo(3)
        assertThat(r.column(0)[0]).isEqualTo(3f)
        assertThat(r.column(2)[0]).isEqualTo(5f)
    }

    @Test
    fun `reject column with wrong bin count`() {
        val r = SpectrogramRingBuffer(capacity = 3, bins = 4)
        try {
            r.append(floatArrayOf(1f, 2f, 3f))
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
