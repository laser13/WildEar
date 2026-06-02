package com.sound2inat.storage

import com.google.common.truth.Truth.assertThat
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
}
