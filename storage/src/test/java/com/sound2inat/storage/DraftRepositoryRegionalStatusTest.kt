package com.sound2inat.storage

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.RegionalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftRepositoryRegionalStatusTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeDetectionDao : DetectionDao {
        val rows = mutableListOf<DetectionEntity>()
        private val emitter = MutableStateFlow<List<DetectionEntity>>(emptyList())
        override fun insertAll(items: List<DetectionEntity>) {
            // assign incremental ids so updates can target rows uniquely
            var nextId = (rows.maxOfOrNull { it.id } ?: 0L)
            rows += items.map { it.copy(id = ++nextId) }
            emitter.value = rows.toList()
        }
        override fun observeForDraft(draftId: String): Flow<List<DetectionEntity>> =
            emitter.map { all -> all.filter { it.draftId == draftId } }
        override fun listForDraft(draftId: String): List<DetectionEntity> =
            rows.filter { it.draftId == draftId }
        override fun setSelected(id: Long, selected: Boolean): Int = 0
        override fun deleteForDraft(draftId: String): Int {
            val before = rows.size
            rows.removeAll { it.draftId == draftId }
            emitter.value = rows.toList()
            return before - rows.size
        }
        override fun observeCountsByDraft(): Flow<List<DraftDetectionCount>> =
            MutableStateFlow(emptyList())
        override fun updateRegionalStatusBySpecies(draftId: String, name: String, status: String?): Int {
            var n = 0
            for (i in rows.indices) {
                if (rows[i].draftId == draftId && rows[i].taxonScientificName == name) {
                    rows[i] = rows[i].copy(regionalStatus = status)
                    n++
                }
            }
            emitter.value = rows.toList()
            return n
        }
    }

    private class FakeDraftDao : DraftDao {
        private val store = mutableMapOf<String, DraftEntity>()
        override fun insert(d: DraftEntity) { store[d.id] = d }
        override fun update(d: DraftEntity) { store[d.id] = d }
        override fun delete(d: DraftEntity) { store.remove(d.id) }
        override fun getById(id: String): DraftEntity? = store[id]
        override fun observeAll(): Flow<List<DraftEntity>> = MutableStateFlow(store.values.toList())
        override fun deleteById(id: String): Int = if (store.remove(id) != null) 1 else 0
        override fun updateStatusConditional(id: String, n: DraftStatus, e: DraftStatus): Int = 0
        override fun updatePalette(id: String, name: String?, ts: Long): Int = 0
        override fun updateSpectrogramGain(id: String, gain: Float?, ts: Long): Int = 0
    }

    private fun repoWith(det: FakeDetectionDao): DraftRepository = DraftRepository(
        drafts = FakeDraftDao(),
        detections = det,
        files = WavFileStore(tmp.root),
        nowMs = { 0L },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `updateRegionalStatuses writes status by scientific name`() = runTest {
        val det = FakeDetectionDao()
        det.insertAll(
            listOf(
                DetectionEntity(
                    draftId = "d1",
                    taxonScientificName = "Parus major",
                    taxonCommonName = "Great Tit",
                    maxConfidence = 0.9f,
                    detectedWindows = 1,
                    firstSeenMs = 0,
                    lastSeenMs = 1,
                    isSelectedByUser = false
                ),
                DetectionEntity(
                    draftId = "d1",
                    taxonScientificName = "Sylvia atricapilla",
                    taxonCommonName = "Blackcap",
                    maxConfidence = 0.8f,
                    detectedWindows = 1,
                    firstSeenMs = 0,
                    lastSeenMs = 1,
                    isSelectedByUser = false
                ),
            ),
        )
        val repo = repoWith(det)

        repo.updateRegionalStatuses(
            "d1",
            mapOf(
                "Parus major" to RegionalStatus.CONFIRMED,
                "Sylvia atricapilla" to RegionalStatus.NOT_CONFIRMED,
            ),
        )

        val byName = det.rows.associateBy { it.taxonScientificName }
        assertThat(byName["Parus major"]!!.regionalStatus).isEqualTo("CONFIRMED")
        assertThat(byName["Sylvia atricapilla"]!!.regionalStatus).isEqualTo("NOT_CONFIRMED")
    }

    @Test
    fun `updateRegionalStatuses ignores species not present`() = runTest {
        val det = FakeDetectionDao()
        det.insertAll(
            listOf(
                DetectionEntity(
                    draftId = "d1",
                    taxonScientificName = "Parus major",
                    taxonCommonName = null,
                    maxConfidence = 0.9f,
                    detectedWindows = 1,
                    firstSeenMs = 0,
                    lastSeenMs = 1,
                    isSelectedByUser = false
                ),
            ),
        )
        val repo = repoWith(det)

        repo.updateRegionalStatuses("d1", mapOf("Unknown species" to RegionalStatus.CONFIRMED))

        assertThat(det.rows.single().regionalStatus).isNull()
    }

    /**
     * Regression test for FIX C: a two-phase analysis (BirdNET then Perch) must NOT
     * wipe regionalStatus that was persisted by [DraftRepository.updateRegionalStatuses]
     * between the two mergeAndPersist calls.
     *
     * Steps:
     * 1. First mergeAndPersist (BirdNET) — persists "Parus major" detection
     * 2. updateRegionalStatuses — marks it CONFIRMED
     * 3. Second mergeAndPersist (Perch) — adds Perch data for the SAME species
     * 4. Assert: regionalStatus is still CONFIRMED after the second merge
     */
    @Test
    fun `mergeAndPersist preserves regionalStatus set between two analysis phases`() = runTest {
        val fakeDraftDao = FakeDraftDao()
        val det = FakeDetectionDao()
        val repo = DraftRepository(
            drafts = fakeDraftDao,
            detections = det,
            files = WavFileStore(tmp.root),
            nowMs = { 0L },
            ioDispatcher = Dispatchers.Unconfined,
        )

        // Seed draft
        fakeDraftDao.insert(
            DraftEntity(
                id = "d1",
                audioPath = "/tmp/a.wav",
                recordedAtUtcMs = 0L,
                durationMs = 5_000L,
                latitude = null,
                longitude = null,
                locationAccuracyMeters = null,
                status = DraftStatus.PENDING_INFERENCE,
                modelId = null,
                modelVersion = null,
                createdAtUtcMs = 0L,
                updatedAtUtcMs = 0L,
            ),
        )

        val birdnetDetection = AggregatedDetection(
            taxonScientificName = "Parus major",
            taxonCommonName = "Great Tit",
            maxConfidence = 0.8f,
            detectedWindows = 2,
            firstSeenMs = 0L,
            lastSeenMs = 2_000L,
            confidenceBySource = mapOf("birdnet_v2_4" to 0.8f),
            windowsBySource = mapOf("birdnet_v2_4" to 2),
            firstSeenBySource = mapOf("birdnet_v2_4" to 0L),
            lastSeenBySource = mapOf("birdnet_v2_4" to 2_000L),
        )

        // Phase 1: BirdNET mergeAndPersist
        repo.mergeAndPersist(
            draftId = "d1",
            newModelId = "birdnet_v2_4",
            newModelVersion = "2.4",
            freshDetections = listOf(birdnetDetection),
        )

        // Annotate regional status between phases (as InferenceQueue does)
        repo.updateRegionalStatuses("d1", mapOf("Parus major" to RegionalStatus.CONFIRMED))

        // Verify it was written
        val afterAnnotation = det.rows.filter { it.draftId == "d1" }
        assertThat(afterAnnotation.single().regionalStatus).isEqualTo("CONFIRMED")

        // Phase 2: Perch mergeAndPersist for the same species (incoming has no regionalStatus)
        val perchDetection = AggregatedDetection(
            taxonScientificName = "Parus major",
            taxonCommonName = "Great Tit",
            maxConfidence = 0.9f,
            detectedWindows = 1,
            firstSeenMs = 1_000L,
            lastSeenMs = 2_000L,
            confidenceBySource = mapOf("perch_v2" to 0.9f),
            windowsBySource = mapOf("perch_v2" to 1),
            firstSeenBySource = mapOf("perch_v2" to 1_000L),
            lastSeenBySource = mapOf("perch_v2" to 2_000L),
        )

        repo.mergeAndPersist(
            draftId = "d1",
            newModelId = "perch_v2",
            newModelVersion = "perch",
            freshDetections = listOf(perchDetection),
        )

        // Assert: regionalStatus must be preserved (not wiped to null)
        val afterSecondMerge = det.rows.filter { it.draftId == "d1" }
        assertThat(afterSecondMerge).hasSize(1)
        assertThat(afterSecondMerge.single().regionalStatus).isEqualTo("CONFIRMED")
    }
}
