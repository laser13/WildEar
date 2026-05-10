package com.sound2inat.storage

import android.util.Log
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.FragmentRanges
import com.sound2inat.inference.SourceStat
import com.sound2inat.inference.SourceStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DraftWithDetections(
    val draft: DraftEntity,
    val detections: List<DetectionEntity>,
)

class DraftRepository(
    private val drafts: DraftDao,
    private val detections: DetectionDao,
    private val files: WavFileStore,
    private val photosDao: DraftPhotoDao? = null,
    private val photoStore: PhotoFileStore? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Wraps multi-step writes (draft + detections) in a single SQLite
     * transaction. Production passes [Sound2iNatDb.runInTransaction];
     * tests with fake DAOs default to inline execution.
     */
    private val runInTransaction: (block: () -> Unit) -> Unit = { it() },
) {

    /**
     * Serialises [mergeAndPersist] across concurrent callers (BirdNET and Perch
     * jobs). Without this, a quick second analysis trigger can race the
     * in-flight read-merge-write cycle and clobber the other job's results.
     */
    private val persistMutex = Mutex()
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
    suspend fun create(
        id: String,
        audioPath: String,
        recordedAtUtcMs: Long,
        durationMs: Long,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
    ) = withContext(ioDispatcher) {
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

    /**
     * Atomic equivalent of [create] + [attachDetections] for the live recording
     * path. Inserts a draft that is already in [DraftStatus.PENDING_REVIEW]
     * with the given live detections, so the offline [com.sound2inat.inference.InferenceJob]
     * never picks it up. Use this only when detections were produced inline
     * (RecordingViewModel + LiveInferenceEngine); offline flows still call
     * [create] then [attachDetections].
     */
    @Suppress("LongParameterList")
    suspend fun createWithDetections(
        id: String,
        audioPath: String,
        recordedAtUtcMs: Long,
        durationMs: Long,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
        modelId: String,
        modelVersion: String,
        detections: List<AggregatedDetection>,
    ) = withContext(ioDispatcher) {
        val now = nowMs()
        val detectionDao = this@DraftRepository.detections
        runInTransaction {
            drafts.insert(
                DraftEntity(
                    id = id,
                    audioPath = audioPath,
                    recordedAtUtcMs = recordedAtUtcMs,
                    durationMs = durationMs,
                    latitude = latitude,
                    longitude = longitude,
                    locationAccuracyMeters = accuracyMeters,
                    status = DraftStatus.PENDING_REVIEW,
                    modelId = modelId,
                    modelVersion = modelVersion,
                    createdAtUtcMs = now,
                    updatedAtUtcMs = now,
                ),
            )
            detectionDao.insertAll(detections.map { it.toEntity(id) })
        }
    }

    suspend fun attachDetections(
        draftId: String,
        modelId: String,
        modelVersion: String,
        items: List<AggregatedDetection>,
        promoteToReviewed: Boolean = false,
    ) = withContext(ioDispatcher) {
        val now = nowMs()
        val finalStatus = if (promoteToReviewed && items.isNotEmpty()) DraftStatus.REVIEWED else DraftStatus.PENDING_REVIEW
        runInTransaction {
            val current = drafts.getById(draftId) ?: error("draft $draftId missing")
            drafts.update(
                current.copy(
                    status = finalStatus,
                    modelId = modelId,
                    modelVersion = modelVersion,
                    updatedAtUtcMs = now,
                ),
            )
            detections.deleteForDraft(draftId)
            detections.insertAll(items.map { it.toEntity(draftId) })
        }
    }

    suspend fun setSelection(detectionId: Long, selected: Boolean) =
        withContext(ioDispatcher) { detections.setSelected(detectionId, selected) }

    suspend fun markReviewed(draftId: String) = withContext(ioDispatcher) {
        val affected = drafts.updateStatusConditional(
            draftId,
            DraftStatus.REVIEWED,
            DraftStatus.PENDING_REVIEW,
        )
        check(affected > 0) { "Status transition failed for draft $draftId — unexpected current status" }
    }

    suspend fun delete(draftId: String) = withContext(ioDispatcher) {
        drafts.deleteById(draftId) // cascade removes draft_photos rows automatically
        val deleted = files.deleteAllFor(draftId)
        if (!deleted) Log.w(TAG, "WAV file deletion failed for draft $draftId")
        photoStore?.deletePhotosFor(draftId) // files last — best-effort, irreversible
    }

    /**
     * Reads the existing detections for [draftId], merges them with [freshDetections]
     * using [mergeBySpecies], then atomically replaces the stored detections and
     * updates the draft's model lineage.
     *
     * Model lineage is accumulated rather than replaced: if the draft already has
     * `birdnet_v2_4` and a Perch run arrives, [attachDetections] is called with
     * `birdnet_v2_4,perch_v2` so the persisted `modelId` records both contributors.
     *
     * When [promoteToReviewed] is true and the merged set is non-empty the draft is
     * also marked REVIEWED inside the same [runInTransaction] block as the detection
     * insert, so the [observeWithDetections] flow never emits an intermediate
     * `PENDING_REVIEW` snapshot between the two writes.
     *
     * Serialised by [persistMutex] so concurrent BirdNET and Perch jobs do not race
     * the read-merge-write cycle. The full read-merge-write cycle runs inside a single
     * [runInTransaction] block so it cannot be interleaved with other writers.
     */
    suspend fun mergeAndPersist(
        draftId: String,
        newModelId: String,
        newModelVersion: String,
        freshDetections: List<AggregatedDetection>,
        promoteToReviewed: Boolean = false,
    ) = persistMutex.withLock {
        val now = nowMs()
        withContext(ioDispatcher) {
            runInTransaction {
                val draft = drafts.getById(draftId)
                    ?: error("draft $draftId missing in mergeAndPersist")
                val existing = detections.listForDraft(draftId).map { it.toAggregated() }
                val merged = mergeBySpecies(existing, freshDetections)
                val combinedModelId = combineModelIds(draft.modelId, newModelId)
                val combinedVersion = combineVersions(draft.modelVersion ?: "", newModelVersion)
                val finalStatus = if (promoteToReviewed && merged.isNotEmpty()) {
                    DraftStatus.REVIEWED
                } else {
                    DraftStatus.PENDING_REVIEW
                }
                drafts.update(
                    draft.copy(
                        status = finalStatus,
                        modelId = combinedModelId,
                        modelVersion = combinedVersion.trim('+'),
                        updatedAtUtcMs = now,
                    ),
                )
                detections.deleteForDraft(draftId)
                detections.insertAll(merged.map { it.toEntity(draftId) })
            }
        }
    }

    private fun DetectionEntity.toAggregated(): AggregatedDetection {
        val fullStats = SourceStats.decode(sources)
        return AggregatedDetection(
            taxonScientificName = taxonScientificName,
            taxonCommonName = taxonCommonName,
            maxConfidence = maxConfidence,
            detectedWindows = detectedWindows,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            confidenceBySource = if (fullStats.isNotEmpty()) {
                fullStats.mapValues { it.value.maxConf }
            } else {
                SourceStats.decodeConfidenceOnly(sources)
            },
            windowsBySource = fullStats.mapValues { it.value.windows },
            firstSeenBySource = fullStats.mapValues { it.value.firstSeenMs },
            lastSeenBySource = fullStats.mapValues { it.value.lastSeenMs },
            fragmentRanges = FragmentRanges.decode(fragmentRanges),
            aggregatedConfidence = aggregatedConfidence,
        )
    }

    private fun combineModelIds(prior: String?, newId: String): String = when {
        prior.isNullOrBlank() -> newId
        prior.split(',', '+').contains(newId) -> prior
        else -> "$prior,$newId"
    }

    private fun combineVersions(prior: String, newVersion: String): String = when {
        prior.isBlank() -> newVersion
        newVersion.isBlank() -> prior
        prior.contains(newVersion) -> prior
        else -> "$prior+$newVersion"
    }

    /**
     * Merges two lists of [AggregatedDetection] by taxon name. For species that
     * appear in both lists the per-source confidence, window counts, and time
     * ranges are unioned; [maxConfidence] and window timestamps take the
     * max/min of both sides. Species that appear in only one list are kept
     * as-is. Result is sorted by [AggregatedDetection.maxConfidence] descending.
     */
    internal fun mergeBySpecies(
        existing: List<AggregatedDetection>,
        incoming: List<AggregatedDetection>,
    ): List<AggregatedDetection> {
        val byName = LinkedHashMap<String, AggregatedDetection>()
        for (d in existing) byName[d.taxonScientificName] = d
        for (d in incoming) {
            val prior = byName[d.taxonScientificName]
            if (prior == null) {
                byName[d.taxonScientificName] = d
                continue
            }
            val allSourceKeys = prior.confidenceBySource.keys + d.confidenceBySource.keys
            val mergedSources = allSourceKeys.associateWith { key ->
                maxOf(prior.confidenceBySource[key] ?: 0f, d.confidenceBySource[key] ?: 0f)
            }
            val mergedWindows = (prior.windowsBySource.keys + d.windowsBySource.keys)
                .associateWith { key ->
                    (prior.windowsBySource[key] ?: 0) + (d.windowsBySource[key] ?: 0)
                }
            val mergedFirstSeen = (prior.firstSeenBySource.keys + d.firstSeenBySource.keys)
                .associateWith { key ->
                    minOf(prior.firstSeenBySource[key] ?: Long.MAX_VALUE, d.firstSeenBySource[key] ?: Long.MAX_VALUE)
                }
            val mergedLastSeen = (prior.lastSeenBySource.keys + d.lastSeenBySource.keys)
                .associateWith { key ->
                    maxOf(prior.lastSeenBySource[key] ?: 0L, d.lastSeenBySource[key] ?: 0L)
                }
            byName[d.taxonScientificName] = AggregatedDetection(
                taxonScientificName = d.taxonScientificName,
                taxonCommonName = prior.taxonCommonName ?: d.taxonCommonName,
                maxConfidence = maxOf(prior.maxConfidence, d.maxConfidence),
                detectedWindows = prior.detectedWindows + d.detectedWindows,
                firstSeenMs = minOf(prior.firstSeenMs, d.firstSeenMs),
                lastSeenMs = maxOf(prior.lastSeenMs, d.lastSeenMs),
                confidenceBySource = mergedSources,
                windowsBySource = mergedWindows,
                firstSeenBySource = mergedFirstSeen,
                lastSeenBySource = mergedLastSeen,
                fragmentRanges = prior.fragmentRanges + d.fragmentRanges,
                aggregatedConfidence = maxOf(prior.aggregatedConfidence, d.aggregatedConfidence),
            )
        }
        return byName.values.sortedByDescending { it.maxConfidence }
    }

    private fun AggregatedDetection.toEntity(draftId: String): DetectionEntity = DetectionEntity(
        draftId = draftId,
        taxonScientificName = taxonScientificName,
        taxonCommonName = taxonCommonName,
        maxConfidence = maxConfidence,
        detectedWindows = detectedWindows,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        isSelectedByUser = false,
        sources = SourceStats.encode(
            confidenceBySource.mapValues { (src, conf) ->
                SourceStat(
                    maxConf = conf,
                    windows = windowsBySource[src] ?: 0,
                    firstSeenMs = firstSeenBySource[src] ?: this.firstSeenMs,
                    lastSeenMs = lastSeenBySource[src] ?: this.lastSeenMs,
                )
            },
        ),
        fragmentRanges = FragmentRanges.encode(fragmentRanges),
        aggregatedConfidence = aggregatedConfidence,
    )

    private companion object {
        const val TAG = "DraftRepository"
    }
}
