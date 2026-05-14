package com.sound2inat.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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
            composeRule.onAllNodesWithContentDescription(STOP_DESC).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(STOP_DESC).performClick()

        // Review: wait for inference progress to disappear and species rows to render.
        composeRule.waitUntil(timeoutMillis = INFERENCE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(SPECIES_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = INFERENCE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BLACKBIRD_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(BLACKBIRD_LABEL).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SETTINGS_DESC).assertIsDisplayed()

        // Save was removed — VM auto-promotes status after inference. Go back via the back arrow.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(BACK_DESC).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(BACK_DESC).performClick()

        // Back on Home: the draft row exists; locate it via the species top-label and reopen it.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(RECORD_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BLACKBIRD_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(BLACKBIRD_LABEL).onFirst().performClick()

        // Review (re-opened): tap delete (trash icon).
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(DELETE_DESC).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(DELETE_DESC).performClick()

        // Back on Home: the species top-label is gone — only the bare RECORD button remains.
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(BLACKBIRD_LABEL).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(RECORD_LABEL).assertIsDisplayed()
    }

    private companion object {
        const val RECORD_LABEL = "Record"
        const val STOP_DESC = "Stop"
        const val BACK_DESC = "Back"
        const val DELETE_DESC = "Delete"
        const val SETTINGS_DESC = "Settings"
        const val SPECIES_LABEL = "Detected species"
        const val BLACKBIRD_LABEL = "Common Blackbird"
        const val SCREEN_TIMEOUT_MS = 10_000L
        const val INFERENCE_TIMEOUT_MS = 15_000L
    }
}
