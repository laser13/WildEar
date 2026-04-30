package com.sound2inat.storage

import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.SourceConfidences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

data class DraftWithDetections(
    val draft: DraftEntity,
    val detections: List<DetectionEntity>,
)

class DraftRepository(
    private val drafts: DraftDao,
    private val detections: DetectionDao,
    private val files: WavFileStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observeAll(): Flow<List<DraftEntity>> = drafts.observeAll()

    /**
     * Emits the latest [DraftWithDetections] each time the draft row OR its detections change.
     * Skips emissions where the draft row no longer exists — that race is normal during
     * delete (the detections-flow lingers until the VM scope is cancelled).
     */
    fun observeWithDetections(id: String): Flow<DraftWithDetections> =
        detections.observeForDraft(id).mapNotNull { ds ->
            val d = drafts.getById(id) ?: return@mapNotNull null
            DraftWithDetections(d, ds)
        }.flowOn(ioDispatcher)

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
                    sources = SourceConfidences.encode(it.confidenceBySource),
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
