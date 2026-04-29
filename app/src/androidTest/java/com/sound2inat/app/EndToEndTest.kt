package com.sound2inat.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end Hilt-driven flow exercised entirely with fakes (recorder, model,
 * location, model manager). Real Room/DataStore/files are kept so the persistence
 * paths are also covered.
 *
 * Manual on-device verification is deferred to Task 18 — this file is here so the
 * compile + KSP graph stays green. CI does not run instrumented tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private val hiltRule = HiltAndroidRule(this)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ))
        .around(hiltRule)
        .around(composeRule)

    @Test
    fun recordReviewSaveDelete_flow() {
        // Home: tap RECORD.
        composeRule.onNodeWithText(RECORD_LABEL).performClick()

        // Recording: stop button appears once recording starts.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(STOP_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(STOP_LABEL).performClick()

        // Review: wait for inference progress to disappear and species rows to render.
        composeRule.waitUntil(timeoutMillis = INFERENCE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(SPECIES_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = INFERENCE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BLACKBIRD_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(BLACKBIRD_LABEL).assertIsDisplayed()

        // Tap Save.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(SAVE_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(SAVE_LABEL).performClick()

        // Back on Home: the draft row exists; locate it via the species top-label and reopen it.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(RECORD_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BLACKBIRD_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(BLACKBIRD_LABEL).onFirst().performClick()

        // Review (re-opened): tap delete (trash glyph).
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(DELETE_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(DELETE_LABEL).performClick()

        // Back on Home: the species top-label is gone — only the bare RECORD button remains.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BLACKBIRD_LABEL).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(RECORD_LABEL).assertIsDisplayed()
    }

    private companion object {
        const val RECORD_LABEL = "● RECORD"
        const val STOP_LABEL = "■ STOP"
        const val SAVE_LABEL = "Save"
        const val SPECIES_LABEL = "Detected species"
        const val BLACKBIRD_LABEL = "Common Blackbird"
        const val DELETE_LABEL = "🗑"
        const val SCREEN_TIMEOUT_MS = 10_000L
        const val INFERENCE_TIMEOUT_MS = 15_000L
    }
}
