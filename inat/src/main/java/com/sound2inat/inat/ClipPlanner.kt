package com.sound2inat.inat

import com.sound2inat.inference.FragmentRange

internal const val CLUSTER_GAP_MS = 30_000L
internal const val MAX_CLIP_S = 60L
internal const val MAX_CLIPS_PER_OBSERVATION = 5
internal const val CLIP_PADDING_MS = 1_000L

/**
 * Maximum spectrogram window length (seconds). `SpectrogramPngRenderer`
 * emits ~94 columns/second, so a 10 s window yields a ~940 px wide PNG —
 * compact enough that iNat's `large` thumbnail (max 1024 px) keeps full
 * resolution without horizontal squashing.
 */
internal const val MAX_SPECTROGRAM_S = 10L

/** A single sound clip range to upload, in millisecond offsets into the source WAV. */
data class ClipRange(val startMs: Long, val endMs: Long) {
    init {
        require(endMs > startMs) { "ClipRange must have positive duration ($startMs..$endMs)" }
    }

    val durationMs: Long get() = endMs - startMs
}

/**
 * Plans 1..N upload clips for a single species' detections.
 *
 * Algorithm:
 *   1. Sort detection [FragmentRange]s by `startMs`.
 *   2. Group into clusters: a new cluster starts whenever the gap between
 *      consecutive ranges is `>= CLUSTER_GAP_MS` (strictly less -> same cluster).
 *   3. For each cluster, compute `[clusterStart - CLIP_PADDING_MS,
 *      clusterEnd + CLIP_PADDING_MS]`, clamped to `[0, recordingDurationMs]`.
 *      If the result is longer than [MAX_CLIP_S] seconds, trim to exactly
 *      `MAX_CLIP_S * 1000` ms centred on the cluster's midpoint.
 *   4. If more than [MAX_CLIPS_PER_OBSERVATION] clusters survive, keep the
 *      top-N by **range count** (cluster density) - clusters with more
 *      detections are more likely to contain a clear call. Ties broken by
 *      earlier startMs so the choice is deterministic and reproducible.
 *
 * Legacy detections (empty fragment ranges) fall back to a single clip.
 */
object ClipPlanner {

    fun plan(
        fragmentRanges: List<FragmentRange>,
        firstSeenMs: Long,
        lastSeenMs: Long,
        recordingDurationMs: Long,
    ): List<ClipRange> {
        val maxClipMs = MAX_CLIP_S * 1000L
        if (fragmentRanges.isEmpty()) {
            val raw = ClipRange(
                startMs = (firstSeenMs - CLIP_PADDING_MS).coerceAtLeast(0L),
                endMs = (lastSeenMs + CLIP_PADDING_MS).coerceAtMost(recordingDurationMs),
            )
            return listOf(trimAroundMidpoint(raw, midMs = (firstSeenMs + lastSeenMs) / 2L, maxClipMs))
        }
        val sorted = fragmentRanges.sortedBy { it.startMs }
        val clusters = mutableListOf<MutableList<FragmentRange>>()
        for (r in sorted) {
            val cur = clusters.lastOrNull()
            // Gap < CLUSTER_GAP_MS -> same cluster. >= -> split.
            if (cur != null && r.startMs - cur.last().endMs < CLUSTER_GAP_MS) {
                cur += r
            } else {
                clusters += mutableListOf(r)
            }
        }
        data class Scored(val clip: ClipRange, val density: Int, val startMs: Long)
        val scored = clusters.map { cluster ->
            val clusterStart = cluster.first().startMs
            val clusterEnd = cluster.last().endMs
            val raw = ClipRange(
                startMs = (clusterStart - CLIP_PADDING_MS).coerceAtLeast(0L),
                endMs = (clusterEnd + CLIP_PADDING_MS).coerceAtMost(recordingDurationMs),
            )
            val midMs = (clusterStart + clusterEnd) / 2L
            Scored(
                clip = trimAroundMidpoint(raw, midMs, maxClipMs),
                density = cluster.size,
                startMs = clusterStart,
            )
        }
        val kept = if (scored.size <= MAX_CLIPS_PER_OBSERVATION) {
            scored
        } else {
            scored
                .sortedWith(compareByDescending<Scored> { it.density }.thenBy { it.startMs })
                .take(MAX_CLIPS_PER_OBSERVATION)
        }
        return kept.map { it.clip }.sortedBy { it.startMs }
    }

    /**
     * Picks the densest sub-window of [clipRange] to render the spectrogram
     * PNG from, using [fragmentRanges] (in absolute WAV milliseconds) as the
     * density signal. Returns at most [MAX_SPECTROGRAM_S] seconds of audio
     * centred on the highest-density region of the clip; if [clipRange] is
     * already shorter than the cap, returns it unchanged.
     *
     * When no entry of [fragmentRanges] intersects [clipRange] (e.g. legacy
     * detections), falls back to the clip's midpoint as the centre.
     *
     * **Tiebreak on density:** when multiple candidate windows hit the same
     * range count, the centre is taken from the **median-by-position** tied
     * candidate, so the window straddles the cluster's geometric centre
     * rather than hugging one edge. This guarantees that a cluster wider than
     * `maxMs / 2` (i.e. detections spread across more than 5 s in a 10 s
     * spectrogram window) still gets a window covering the whole cluster
     * where possible, not one that clips the tail off.
     */
    fun peakSpectrogramWindow(
        clipRange: ClipRange,
        fragmentRanges: List<FragmentRange>,
    ): ClipRange {
        val maxMs = MAX_SPECTROGRAM_S * 1000L
        if (clipRange.durationMs <= maxMs) return clipRange
        val inClip = fragmentRanges.filter {
            it.endMs > clipRange.startMs && it.startMs < clipRange.endMs
        }
        val centre: Long = if (inClip.isEmpty()) {
            (clipRange.startMs + clipRange.endMs) / 2L
        } else {
            // Score each candidate by the count of inClip ranges intersecting
            // the window centred on its midpoint, clamped to clipRange.
            data class Scored(val centreMs: Long, val density: Int, val orderIdx: Int)
            val candidates = inClip.mapIndexed { idx, r ->
                val cMid = (r.startMs + r.endMs) / 2L
                val winStart = (cMid - maxMs / 2L).coerceAtLeast(clipRange.startMs)
                val winEnd = (winStart + maxMs).coerceAtMost(clipRange.endMs)
                val actualStart = (winEnd - maxMs).coerceAtLeast(clipRange.startMs)
                val density = inClip.count { it.endMs > actualStart && it.startMs < winEnd }
                Scored(centreMs = (actualStart + winEnd) / 2L, density = density, orderIdx = idx)
            }
            val maxDensity = candidates.maxOf { it.density }
            val tied = candidates.filter { it.density == maxDensity }.sortedBy { it.centreMs }
            // Median by position among ties — straddles the cluster's centre.
            tied[tied.size / 2].centreMs
        }
        val half = maxMs / 2L
        val rawStart = (centre - half).coerceAtLeast(clipRange.startMs)
        val rawEnd = (rawStart + maxMs).coerceAtMost(clipRange.endMs)
        val finalStart = (rawEnd - maxMs).coerceAtLeast(clipRange.startMs)
        return ClipRange(startMs = finalStart, endMs = rawEnd)
    }

    // invariant: midMs in raw.startMs..raw.endMs
    private fun trimAroundMidpoint(raw: ClipRange, midMs: Long, maxClipMs: Long): ClipRange {
        if (raw.durationMs <= maxClipMs) return raw
        val half = maxClipMs / 2L
        val centred = ClipRange(
            startMs = (midMs - half).coerceAtLeast(raw.startMs),
            endMs = (midMs + half).coerceAtMost(raw.endMs),
        )
        val needLeft = maxClipMs - centred.durationMs
        return when {
            needLeft <= 0 -> centred
            centred.endMs == raw.endMs ->
                ClipRange(startMs = (centred.startMs - needLeft).coerceAtLeast(raw.startMs), endMs = centred.endMs)
            centred.startMs == raw.startMs ->
                ClipRange(startMs = centred.startMs, endMs = (centred.endMs + needLeft).coerceAtMost(raw.endMs))
            // Unreachable on valid input: when both ends sit strictly inside
            // `raw`, `centred` is already `maxClipMs` wide and `needLeft <= 0`
            // handles it above. The only way to reach this branch is `midMs`
            // outside `[raw.startMs, raw.endMs]` — which violates the
            // invariant documented above. The only caller ([ClipPlanner.plan])
            // builds `midMs` from a cluster's own range, so it cannot happen.
            else -> error(
                "trimAroundMidpoint: unreachable else branch " +
                    "(raw=$raw, mid=$midMs, max=$maxClipMs, centred=$centred)",
            )
        }
    }
}
