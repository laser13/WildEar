package com.sound2inat.app.recording

import com.sound2inat.inference.RegionalStatus

/**
 * Pure-domain projection of a live [com.sound2inat.inference.AggregatedDetection]
 * plus its resolved [RegionalStatus]. Held inside [RecordingSessionState.Recording];
 * the UI layer maps this to its own card type ([com.sound2inat.app.ui.recording.LiveCard]).
 * Keeping UI types out of the controller is what lets `app.recording` move to its
 * own module without depending on `app.ui` (Phase 5).
 */
data class LiveDetection(
    val scientificName: String,
    val commonName: String?,
    val count: Int,
    val peakConfidence: Float,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val regionalStatus: RegionalStatus? = null,
)

/** Domain GPS fix snapshot (no UI status semantics). */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
)
