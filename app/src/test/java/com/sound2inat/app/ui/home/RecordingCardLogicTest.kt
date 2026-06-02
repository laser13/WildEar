package com.sound2inat.app.ui.home

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.RegionalStatus
import org.junit.Test

class RecordingCardLogicTest {

    private fun item(name: String, conf: Float, status: RegionalStatus?) =
        RecordingSpeciesItem(name, null, conf, status)

    @Test
    fun `confirmed and unverified and null sort first by score, not_confirmed last`() {
        val input = listOf(
            item("D_notconf_high", 0.95f, RegionalStatus.NOT_CONFIRMED),
            item("A_confirmed_mid", 0.70f, RegionalStatus.CONFIRMED),
            item("B_unverified_high", 0.90f, RegionalStatus.UNVERIFIED),
            item("C_null_low", 0.50f, null),
            item("E_notconf_low", 0.40f, RegionalStatus.NOT_CONFIRMED),
        )

        val sorted = sortRecordingSpecies(input)

        // Shown group first (UNVERIFIED 0.90, CONFIRMED 0.70, null 0.50 by score DESC),
        // then NOT_CONFIRMED group (0.95, 0.40 by score DESC).
        assertThat(sorted.map { it.scientificName }).containsExactly(
            "B_unverified_high",
            "A_confirmed_mid",
            "C_null_low",
            "D_notconf_high",
            "E_notconf_low",
        ).inOrder()
    }

    @Test
    fun `model badges map birdnet and perch keys, fold meta into birdnet`() {
        assertThat(modelBadgesOf(setOf("birdnet_v2_4"))).containsExactly(ModelBadge.BIRDNET)
        assertThat(modelBadgesOf(setOf("perch_v2"))).containsExactly(ModelBadge.PERCH)
        assertThat(modelBadgesOf(setOf("birdnet_v2_4", "birdnet_v2_4_meta", "perch_v2")))
            .containsExactly(ModelBadge.BIRDNET, ModelBadge.PERCH)
        assertThat(modelBadgesOf(emptySet())).isEmpty()
        assertThat(modelBadgesOf(setOf("unknown_model"))).isEmpty()
    }
}
