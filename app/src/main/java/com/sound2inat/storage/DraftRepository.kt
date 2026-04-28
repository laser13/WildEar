package com.sound2inat.storage

import com.sound2inat.inference.AggregatedDetection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DraftWithDetections(
    val draft: DraftEntity,
    val detections: List<DetectionEntity>,
)

class DraftRepository(
    private val drafts: DraftDao,
    private val detections: DetectionDao,
    private val files: WavFileStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun observeAll(): Flow<List<DraftEntity>> = drafts.observeAll()

    /**
     * Emits the latest [DraftWithDetections] each time the draft row OR its detections change.
     * The draft row is loaded synchronously inside the flow (no separate observable for the
     * single row in v1). Returns null inside the flow if the draft no longer exists (deleted).
     */
    fun observeWithDetections(id: String): Flow<DraftWithDetections> =
        detections.observeForDraft(id).map { ds ->
            val d = drafts.getById(id) ?: error("draft $id missing")
            DraftWithDetections(d, ds)
        }

    @Suppress("LongParameterList")
    fun create(
        id: String,
        audioPath: String,
        recordedAtUtcMs: Long,
        durationMs: Long,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
    ) {
        val now = nowMs()
        drafts.insert(
            DraftEntity(
                id = id,
                audioPath = audioPath,
                recordedAtUtcMs = recordedAtUtcMs,
                durationMs = durationMs,
                latitude = latitude,
                longitude = longitude,
                locationAccuracyMeters = accuracyMeters,
                status = DraftStatus.PENDING_INFERENCE,
                modelId = null,
                modelVersion = null,
                createdAtUtcMs = now,
                updatedAtUtcMs = now,
            ),
        )
    }

    fun attachDetections(
        draftId: String,
        modelId: String,
        modelVersion: String,
        items: List<AggregatedDetection>,
    ) {
        val now = nowMs()
        val current = drafts.getById(draftId) ?: error("draft $draftId missing")
        drafts.update(
            current.copy(
                status = DraftStatus.PENDING_REVIEW,
                modelId = modelId,
                modelVersion = modelVersion,
                updatedAtUtcMs = now,
            ),
        )
        detections.deleteForDraft(draftId)
        detections.insertAll(
            items.map {
                DetectionEntity(
                    draftId = draftId,
                    taxonScientificName = it.taxonScientificName,
                    taxonCommonName = it.taxonCommonName,
                    maxConfidence = it.maxConfidence,
                    detectedWindows = it.detectedWindows,
                    firstSeenMs = it.firstSeenMs,
                    lastSeenMs = it.lastSeenMs,
                    isSelectedByUser = false,
                )
            },
        )
    }

    fun setSelection(detectionId: Long, selected: Boolean) {
        detections.setSelected(detectionId, selected)
    }

    fun markReviewed(draftId: String) {
        val d = drafts.getById(draftId) ?: error("draft $draftId missing")
        drafts.update(d.copy(status = DraftStatus.REVIEWED, updatedAtUtcMs = nowMs()))
    }

    fun delete(draftId: String) {
        drafts.deleteById(draftId)
        files.deleteAllFor(draftId)
    }
}
