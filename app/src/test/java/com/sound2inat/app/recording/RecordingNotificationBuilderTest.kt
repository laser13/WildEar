package com.sound2inat.app.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingNotificationBuilderTest {

    private fun state(
        elapsedMs: Long = 0L,
        lastDetection: LiveDetection? = null,
    ) = RecordingSessionState.Recording(
        draftId = "d1",
        recordingStartMs = 0L,
        elapsedMs = elapsedMs,
        rms = 0f,
        gpsFix = null,
        locationTimedOut = false,
        warningSoftLimit = false,
        backlogWindows = 0,
        detections = emptyList(),
        lastDetection = lastDetection,
    )

    private fun detection(scientific: String, common: String?) = LiveDetection(
        scientificName = scientific,
        commonName = common,
        count = 1,
        peakConfidence = 0.8f,
        firstSeenMs = 0L,
        lastSeenMs = 1000L,
    )

    @Test
    fun `elapsed only when no last detection`() {
        val text = RecordingNotificationBuilder.buildContentText(state(elapsedMs = 75_000L))
        assertThat(text).isEqualTo("1:15")
    }

    @Test
    fun `elapsed plus common name when detection has common name`() {
        val text = RecordingNotificationBuilder.buildContentText(
            state(elapsedMs = 65_000L, lastDetection = detection("Turdus merula", "Common Blackbird")),
        )
        assertThat(text).isEqualTo("1:05 · Common Blackbird")
    }

    @Test
    fun `falls back to scientific name when common name is null`() {
        val text = RecordingNotificationBuilder.buildContentText(
            state(elapsedMs = 0L, lastDetection = detection("Turdus merula", null)),
        )
        assertThat(text).isEqualTo("0:00 · Turdus merula")
    }

    @Test
    fun `formats under one hour as M SS`() {
        assertThat(RecordingNotificationBuilder.formatElapsed(0L)).isEqualTo("0:00")
        assertThat(RecordingNotificationBuilder.formatElapsed(59_999L)).isEqualTo("0:59")
        assertThat(RecordingNotificationBuilder.formatElapsed(60_000L)).isEqualTo("1:00")
        assertThat(RecordingNotificationBuilder.formatElapsed(3_599_000L)).isEqualTo("59:59")
    }

    @Test
    fun `formats one hour and beyond as H MM SS`() {
        assertThat(RecordingNotificationBuilder.formatElapsed(3_600_000L)).isEqualTo("1:00:00")
        assertThat(RecordingNotificationBuilder.formatElapsed(5_025_000L)).isEqualTo("1:23:45")
    }
}
