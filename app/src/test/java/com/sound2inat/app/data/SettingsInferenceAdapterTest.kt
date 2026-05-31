package com.sound2inat.app.data

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SettingsInferenceAdapterTest {

    @Test
    fun `forwards every flow value from Settings`() = runTest {
        val settings = mockk<Settings>(relaxed = true) {
            every { minConfidenceDisplay } returns flowOf(0.33f)
            every { minWindows } returns flowOf(4)
            every { yamNetGateEnabled } returns flowOf(true)
            every { birdNetMetaEnabled } returns flowOf(false)
            every { lastKnownLat } returns flowOf(12.5)
            every { lastKnownLon } returns flowOf(-7.25)
        }
        val adapter = SettingsInferenceAdapter(settings)

        assertThat(adapter.minConfidenceDisplay.first()).isEqualTo(0.33f)
        assertThat(adapter.minWindows.first()).isEqualTo(4)
        assertThat(adapter.yamNetGateEnabled.first()).isTrue()
        assertThat(adapter.birdNetMetaEnabled.first()).isFalse()
        assertThat(adapter.lastKnownLat.first()).isEqualTo(12.5)
        assertThat(adapter.lastKnownLon.first()).isEqualTo(-7.25)
    }

    @Test
    fun `setLastKnownCoords delegates to Settings`() = runTest {
        val settings = mockk<Settings>(relaxed = true)
        coEvery { settings.setLastKnownCoords(any(), any()) } returns Unit
        val adapter = SettingsInferenceAdapter(settings)

        adapter.setLastKnownCoords(1.0, 2.0)

        coVerify { settings.setLastKnownCoords(1.0, 2.0) }
    }
}
