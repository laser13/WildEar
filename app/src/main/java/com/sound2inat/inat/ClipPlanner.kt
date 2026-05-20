package com.sound2inat.inat

import com.sound2inat.inference.FragmentRange

internal const val CLUSTER_GAP_MS = 30_000L
internal const val MAX_CLIP_S = 60L
internal const val MAX_CLIPS_PER_OBSERVATION = 5
internal const val CLIP_PADDING_MS = 1_000L

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
