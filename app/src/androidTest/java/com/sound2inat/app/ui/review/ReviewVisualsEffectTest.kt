package com.sound2inat.app.ui.review

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReviewVisualsEffectTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activePageWithAudio_triggersEnsureVisualsOnce() {
        var calls = 0

        composeRule.setContent {
            ReviewVisualsEffect(
                isActive = true,
                audioPath = "/tmp/audio.wav",
                filesDir = File("/tmp"),
                ensureVisuals = { calls++ },
            )
        }

        composeRule.waitForIdle()

        assertEquals(1, calls)
    }

    @Test
    fun inactivePageWithAudio_doesNotTriggerEnsureVisuals() {
        var calls = 0

        composeRule.setContent {
            ReviewVisualsEffect(
                isActive = false,
                audioPath = "/tmp/audio.wav",
                filesDir = File("/tmp"),
                ensureVisuals = { calls++ },
            )
        }

        composeRule.waitForIdle()

        assertEquals(0, calls)
    }

    @Test
    fun activePageWithoutAudio_doesNotTriggerEnsureVisuals() {
        var calls = 0

        composeRule.setContent {
            ReviewVisualsEffect(
                isActive = true,
                audioPath = null,
                filesDir = File("/tmp"),
                ensureVisuals = { calls++ },
            )
        }

        composeRule.waitForIdle()

        assertEquals(0, calls)
    }
}
