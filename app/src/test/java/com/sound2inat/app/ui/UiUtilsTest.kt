package com.sound2inat.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiUtilsTest {
    @Test
    fun `formats sub-minute durations as m colon ss`() {
        assertThat(formatDurationMs(0L)).isEqualTo("0:00")
        assertThat(formatDurationMs(5_000L)).isEqualTo("0:05")
        assertThat(formatDurationMs(59_000L)).isEqualTo("0:59")
    }

    @Test
    fun `formats multi-minute durations`() {
        assertThat(formatDurationMs(60_000L)).isEqualTo("1:00")
        assertThat(formatDurationMs(125_000L)).isEqualTo("2:05")
        assertThat(formatDurationMs(600_000L)).isEqualTo("10:00")
    }

    @Test
    fun `clamps negative durations to zero`() {
        assertThat(formatDurationMs(-1_000L)).isEqualTo("0:00")
    }
}
