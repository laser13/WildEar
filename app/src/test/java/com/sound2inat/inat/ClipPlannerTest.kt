package com.sound2inat.inat

import com.sound2inat.inference.FragmentRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPlannerTest {

    private val durationMs = 600_000L // 10 min file

    @Test
    fun `legacy detection without fragment ranges yields one clip from firstSeen,lastSeen window`() {
        val plan = ClipPlanner.plan(
            fragmentRanges = emptyList(),
            firstSeenMs = 10_000L,
            lastSeenMs = 15_000L,
            recordingDurationMs = durationMs,
        )
        assertEquals(1, plan.size)
        assertEquals(9_000L, plan[0].startMs) // 10 s − 1 s padding
        assertEquals(16_000L, plan[0].endMs) // 15 s + 1 s padding
    }

    @Test
    fun `two clusters separated by 45s gap produce two clips`() {
        val ranges = listOf(
            FragmentRange(10_000L, 13_000L),
            FragmentRange(13_000L, 16_000L),
            FragmentRange(61_000L, 64_000L),
            FragmentRange(64_000L, 67_000L),
        )
        val plan = ClipPlanner.plan(
            fragmentRanges = ranges,
            firstSeenMs = 10_000L,
            lastSeenMs = 67_000L,
            recordingDurationMs = durationMs,
        )
        assertEquals(2, plan.size)
        assertEquals(9_000L, plan[0].startMs)
        assertEquals(17_000L, plan[0].endMs)
        assertEquals(60_000L, plan[1].startMs)
        assertEquals(68_000L, plan[1].endMs)
    }

    @Test
    fun `exactly CLUSTER_GAP_MS gap splits clusters (boundary check)`() {
        val ranges = listOf(
            FragmentRange(10_000L, 13_000L),
            FragmentRange(43_000L, 46_000L),
        )
        val plan = ClipPlanner.plan(
            fragmentRanges = ranges,
            firstSeenMs = 10_000L,
            lastSeenMs = 46_000L,
            recordingDurationMs = durationMs,
        )
        assertEquals(2, plan.size)
    }

    @Test
    fun `cluster wider than MAX_CLIP_S is shrunk to 60s around midpoint`() {
        val ranges = (0L..29L).map { i ->
            FragmentRange(i * 3_000L, i * 3_000L + 3_000L)
        }
        val plan = ClipPlanner.plan(
            fragmentRanges = ranges,
            firstSeenMs = 0L,
            lastSeenMs = 90_000L,
            recordingDurationMs = durationMs,
        )
        assertEquals(1, plan.size)
        val clip = plan[0]
        assertEquals(60_000L, clip.endMs - clip.startMs)
        assertTrue(
            "cluster midpoint (45 s) lies inside the clip",
            clip.startMs <= 45_000L && 45_000L <= clip.endMs,
        )
    }

    @Test
    fun `more than MAX_CLIPS_PER_OBSERVATION clusters keeps top-5 by density`() {
        val sparse = (0L until 3L).map { i ->
            FragmentRange(i * 60_000L, i * 60_000L + 3_000L)
        }
        val dense = (3L until 8L).flatMap { i ->
            val base = i * 60_000L
            listOf(
                FragmentRange(base, base + 3_000L),
                FragmentRange(base + 3_000L, base + 6_000L),
                FragmentRange(base + 6_000L, base + 9_000L),
            )
        }
        val ranges = sparse + dense
        val plan = ClipPlanner.plan(
            fragmentRanges = ranges,
            firstSeenMs = 0L,
            lastSeenMs = 429_000L,
            recordingDurationMs = durationMs,
        )
        assertEquals(5, plan.size)
        val starts = plan.map { it.startMs }.sorted()
        assertEquals(listOf(179_000L, 239_000L, 299_000L, 359_000L, 419_000L), starts)
    }

    // peakSpectrogramWindow

    @Test
    fun `peakSpectrogramWindow returns full clip when clip is shorter than MAX_SPECTROGRAM_S`() {
        val clip = ClipRange(0L, 8_000L)
        val ranges = listOf(FragmentRange(2_000L, 5_000L))
        assertEquals(clip, ClipPlanner.peakSpectrogramWindow(clip, ranges))
    }

    @Test
    fun `peakSpectrogramWindow returns clip-centred 10s window when no fragment ranges intersect`() {
        val clip = ClipRange(0L, 60_000L)
        val result = ClipPlanner.peakSpectrogramWindow(clip, fragmentRanges = emptyList())
        assertEquals(10_000L, result.durationMs)
        assertEquals(25_000L, result.startMs)
        assertEquals(35_000L, result.endMs)
    }

    @Test
    fun `peakSpectrogramWindow centres on densest 10s region`() {
        // 60 s clip with one outlier at t≈6 s and five tightly-packed ranges
        // spanning 42–49 s. The 10 s window must cover the whole 42–49 s
        // cluster (median tiebreak straddles its geometric centre).
        val clip = ClipRange(0L, 60_000L)
        val ranges = listOf(
            FragmentRange(5_000L, 8_000L), // outlier
            FragmentRange(42_000L, 45_000L), // dense cluster start
            FragmentRange(45_000L, 48_000L),
            FragmentRange(43_000L, 46_000L),
            FragmentRange(44_000L, 47_000L),
            FragmentRange(46_000L, 49_000L),
        )
        val result = ClipPlanner.peakSpectrogramWindow(clip, ranges)
        assertEquals(10_000L, result.durationMs)
        assertTrue("window starts no later than 42 s", result.startMs <= 42_000L)
        assertTrue("window ends no earlier than 49 s", result.endMs >= 49_000L)
    }
}
