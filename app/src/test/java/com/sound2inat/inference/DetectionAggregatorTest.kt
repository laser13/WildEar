package com.sound2inat.inference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetectionAggregatorTest {
    private val agg = DetectionAggregator(minConfidence = 0.10f)

    @Test
    fun `aggregates per species with max confidence and time bounds`() {
        val preds = listOf(
            wp(0, 3_000, "A", 0.8f),
            wp(1_000, 4_000, "A", 0.6f),
            wp(2_000, 5_000, "B", 0.3f),
            wp(3_000, 6_000, "A", 0.9f),
        )
        val out = agg.aggregate(preds).associateBy { it.taxonScientificName }
        val a = out.getValue("A")
        assertThat(a.maxConfidence).isEqualTo(0.9f)
        assertThat(a.detectedWindows).isEqualTo(3)
        assertThat(a.firstSeenMs).isEqualTo(0L)
        assertThat(a.lastSeenMs).isEqualTo(6_000L)
        assertThat(out["B"]!!.detectedWindows).isEqualTo(1)
    }

    @Test
    fun `drops predictions below min confidence`() {
        val preds = listOf(
            wp(0, 3_000, "A", 0.05f),
            wp(0, 3_000, "B", 0.20f),
        )
        val out = agg.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("B")
    }

    @Test
    fun `sorted by max confidence desc`() {
        val preds = listOf(
            wp(0, 3_000, "low", 0.3f),
            wp(0, 3_000, "high", 0.9f),
            wp(0, 3_000, "mid", 0.6f),
        )
        val out = agg.aggregate(preds).map { it.taxonScientificName }
        assertThat(out).containsExactly("high", "mid", "low").inOrder()
    }

    private fun wp(s: Long, e: Long, t: String, c: Float) =
        WindowPrediction(s, e, t, t, c)
}
