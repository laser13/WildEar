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

    @Test
    fun `incremental addWindow yields same result as batch aggregate`() {
        val preds = listOf(
            WindowPrediction(0L, 3000L, "Turdus merula", "Blackbird", 0.8f, "birdnet_v2_4"),
            WindowPrediction(1500L, 4500L, "Turdus merula", "Blackbird", 0.6f, "birdnet_v2_4"),
            WindowPrediction(0L, 3000L, "Erithacus rubecula", "Robin", 0.7f, "birdnet_v2_4"),
        )
        val batch = DetectionAggregator(minConfidence = 0.5f).aggregate(preds)
        val incremental = DetectionAggregator(minConfidence = 0.5f)
        var snapshot: List<AggregatedDetection> = emptyList()
        for (p in preds) snapshot = incremental.addWindow(p)
        assertThat(snapshot).containsExactlyElementsIn(batch).inOrder()
    }

    @Test
    fun `addWindow filters below threshold and noise labels`() {
        val agg = DetectionAggregator(minConfidence = 0.5f)
        agg.addWindow(WindowPrediction(0L, 3000L, "Fireworks", null, 0.9f, "birdnet_v2_4"))
        agg.addWindow(WindowPrediction(0L, 3000L, "Turdus merula", null, 0.3f, "birdnet_v2_4"))
        val snap = agg.addWindow(WindowPrediction(1500L, 4500L, "Turdus merula", null, 0.7f, "birdnet_v2_4"))
        assertThat(snap).hasSize(1)
        assertThat(snap[0].taxonScientificName).isEqualTo("Turdus merula")
        assertThat(snap[0].detectedWindows).isEqualTo(1)
    }

    @Test
    fun `reset clears incremental state`() {
        val agg = DetectionAggregator(minConfidence = 0.0f)
        agg.addWindow(WindowPrediction(0L, 3000L, "Turdus merula", null, 0.9f, "x"))
        agg.reset()
        val snap = agg.snapshot()
        assertThat(snap).isEmpty()
    }

    @Test
    fun `snapshot returns last aggregated state without adding`() {
        val agg = DetectionAggregator(minConfidence = 0.0f)
        agg.addWindow(WindowPrediction(0L, 3000L, "Parus major", null, 0.8f, "x"))
        val a = agg.snapshot()
        val b = agg.snapshot()
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `fragment ranges accumulated per window`() {
        val preds = listOf(
            wp(0, 3_000, "Parus major", 0.8f),
            wp(1_000, 4_000, "Parus major", 0.6f),
            wp(2_000, 5_000, "Parus major", 0.9f),
        )
        val out = agg.aggregate(preds).first()
        assertThat(out.fragmentRanges).containsExactly(
            FragmentRange(0L, 3_000L),
            FragmentRange(1_000L, 4_000L),
            FragmentRange(2_000L, 5_000L),
        ).inOrder()
    }

    @Test
    fun `aggregated confidence is average of all window confidences`() {
        val preds = listOf(
            wp(0, 3_000, "Parus major", 0.8f),
            wp(1_000, 4_000, "Parus major", 0.6f),
            wp(2_000, 5_000, "Parus major", 0.7f),
        )
        val out = agg.aggregate(preds).first()
        // (0.8 + 0.6 + 0.7) / 3 = 0.7
        assertThat(out.aggregatedConfidence).isWithin(1e-6f).of(0.7f)
    }

    @Test
    fun `fragment ranges sorted by startMs`() {
        // Feed windows out of chronological order
        val preds = listOf(
            wp(2_000, 5_000, "Parus major", 0.7f),
            wp(0, 3_000, "Parus major", 0.8f),
            wp(1_000, 4_000, "Parus major", 0.6f),
        )
        val out = agg.aggregate(preds).first()
        val starts = out.fragmentRanges.map { it.startMs }
        assertThat(starts).containsExactly(0L, 1_000L, 2_000L).inOrder()
    }

    private fun wp(s: Long, e: Long, t: String, c: Float) =
        WindowPrediction(s, e, t, t, c)
}
