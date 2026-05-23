package com.sound2inat.inat

import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftObservationCount
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.InatObservationDao
import com.sound2inat.storage.InatObservationEntity
import com.sound2inat.storage.InatUploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared in-memory test doubles for the INat submitter test suite.
 *
 * The four submitter test files previously each carried their own
 * `FakeDraftDao` / `FakeInatDao` (or `LocalFake…` / `…Incremental` /
 * `…Multi`) duplicates because the originals were private to a single
 * test file. Consolidated here once detekt's duplication threshold was
 * past the "extract" line.
 *
 * Behaviour union:
 *   * `update(...)` upserts (insert-if-missing). Two of the four old
 *     copies behaved this way; the strict-no-op-on-missing variant in
 *     the original `INatSubmitterTest` is a subset of upsert because
 *     that test always pre-`insert`s its drafts before any `update`.
 *   * `insert(...)` on the iNat DAO auto-increments `id` and stores the
 *     `row.copy(id = ...)` copy — matches Room's `@Insert` contract.
 */

internal class InMemoryDraftDao : DraftDao {
    val inserted: MutableList<DraftEntity> = mutableListOf()

    override fun insert(d: DraftEntity) { inserted += d }

    override fun update(d: DraftEntity) {
        val i = inserted.indexOfFirst { it.id == d.id }
        if (i >= 0) inserted[i] = d else inserted += d
    }

    override fun delete(d: DraftEntity) { inserted.removeAll { it.id == d.id } }

    override fun getById(id: String): DraftEntity? = inserted.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<DraftEntity>> = flowOf(inserted.toList())

    override fun deleteById(id: String): Int =
        if (inserted.removeAll { it.id == id }) 1 else 0

    override fun updateStatusConditional(
        id: String,
        newStatus: DraftStatus,
        expectedStatus: DraftStatus,
    ): Int {
        val i = inserted.indexOfFirst { it.id == id && it.status == expectedStatus }
        if (i < 0) return 0
        inserted[i] = inserted[i].copy(status = newStatus)
        return 1
    }

    override fun updatePalette(id: String, name: String?, ts: Long): Int = 0
    override fun updateSpectrogramGain(id: String, gain: Float?, ts: Long): Int = 0
}

internal class InMemoryInatObservationDao : InatObservationDao {
    val rows: MutableList<InatObservationEntity> = mutableListOf()
    private var nextId = 1L

    override fun insert(row: InatObservationEntity): Long {
        // Match Room's auto-increment: never collide with rows already in the
        // list (tests sometimes pre-seed via `rows += entity.copy(id = N)`).
        val seededMax = rows.maxOfOrNull { it.id } ?: 0L
        if (seededMax >= nextId) nextId = seededMax + 1L
        val id = nextId++
        rows += row.copy(id = id)
        return id
    }

    override fun listForDraft(draftId: String): List<InatObservationEntity> =
        rows.filter { it.draftId == draftId }

    override fun findForDraftAndSpecies(draftId: String, species: String): InatObservationEntity? =
        rows.firstOrNull { it.draftId == draftId && it.taxonScientificName == species }

    override fun observeForDraft(draftId: String): Flow<List<InatObservationEntity>> =
        flowOf(listForDraft(draftId))

    override fun deleteForDraft(draftId: String): Int {
        val before = rows.size
        rows.removeAll { it.draftId == draftId }
        return before - rows.size
    }

    override fun deleteForDraftAndSpecies(draftId: String, species: String): Int {
        val before = rows.size
        rows.removeAll { it.draftId == draftId && it.taxonScientificName == species }
        return before - rows.size
    }

    override fun markComplete(rowId: Long): Int {
        val i = rows.indexOfFirst { it.id == rowId }
        if (i < 0) return 0
        rows[i] = rows[i].copy(uploadStatus = InatUploadStatus.COMPLETE)
        return 1
    }

    override fun observeIncompleteForDraft(draftId: String): Flow<List<InatObservationEntity>> =
        flowOf(rows.filter { it.draftId == draftId && it.uploadStatus == InatUploadStatus.INCOMPLETE })

    override fun deleteById(rowId: Long): Int {
        val before = rows.size
        rows.removeAll { it.id == rowId }
        return before - rows.size
    }

    override fun observeCountsByDraft(): Flow<List<DraftObservationCount>> =
        flowOf(
            rows.groupBy { it.draftId }
                .map { (id, list) -> DraftObservationCount(id, list.size) },
        )
}
