package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetectionAggregatorTest {
    private val agg = DetectionAggregator(minConfidence = 0.10f)

    @Test
    fun `aggregates per species with max confidence and time bounds`() {
        val preds = listOf(
            wp(0, 3_000, "Parus major", 0.8f),
            wp(1_000, 4_000, "Parus major", 0.6f),
            wp(2_000, 5_000, "Sylvia atricapilla", 0.3f),
            wp(3_000, 6_000, "Parus major", 0.9f),
        )
        val out = agg.aggregate(preds).associateBy { it.taxonScientificName }
        val a = out.getValue("Parus major")
        assertThat(a.maxConfidence).isEqualTo(0.9f)
        assertThat(a.detectedWindows).isEqualTo(3)
        assertThat(a.firstSeenMs).isEqualTo(0L)
        assertThat(a.lastSeenMs).isEqualTo(6_000L)
        assertThat(out["Sylvia atricapilla"]!!.detectedWindows).isEqualTo(1)
    }

    @Test
    fun `drops predictions below min confidence`() {
        val preds = listOf(
            wp(0, 3_000, "Parus major", 0.05f),
            wp(0, 3_000, "Sylvia atricapilla", 0.20f),
        )
        val out = agg.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("Sylvia atricapilla")
    }

    @Test
    fun `sorted by max confidence desc`() {
        val preds = listOf(
            wp(0, 3_000, "Genus low", 0.3f),
            wp(0, 3_000, "Genus high", 0.9f),
            wp(0, 3_000, "Genus mid", 0.6f),
        )
        val out = agg.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("Genus high", "Genus mid", "Genus low").inOrder()
    }

    @Test
    fun `drops BirdNET noise labels (single-token names like Fireworks Engine)`() {
        // BirdNET v2.4 labels.txt mixes real binomials ("Parus major") with
        // single-word noise classes ("Fireworks", "Engine", "Siren"). The
        // single-word ones must never reach the user's draft, otherwise
        // iNat happily resolves "Fireworks" to a plant (Solidago rugosa).
        val preds = listOf(
            wp(0, 3_000, "Parus major", 0.8f),
            wp(0, 3_000, "Fireworks", 0.7f),
            wp(0, 3_000, "Engine", 0.6f),
            wp(0, 3_000, "Sylvia atricapilla", 0.5f),
            wp(0, 3_000, "Siren", 0.4f),
        )
        val out = agg.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("Parus major", "Sylvia atricapilla").inOrder()
    }

    @Test
    fun `minWindows filters species seen fewer times than threshold`() {
        val agg2 = DetectionAggregator(minConfidence = 0.10f, minWindows = 2)
        val preds = listOf(
            wp(0, 3_000, "Parus major", 0.8f),
            wp(1_000, 4_000, "Parus major", 0.6f),   // 2 windows — passes
            wp(0, 3_000, "Sylvia atricapilla", 0.9f), // 1 window — filtered
        )
        val out = agg2.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("Parus major")
    }

    @Test
    fun `minWindows defaults to 1 — single-window species pass`() {
        val agg1 = DetectionAggregator(minConfidence = 0.10f) // default minWindows = 1
        val preds = listOf(wp(0, 3_000, "Parus major", 0.8f))
        val out = agg1.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("Parus major")
    }

    private fun wp(s: Long, e: Long, t: String, c: Float) =
        WindowPrediction(s, e, t, t, c)
}
