package com.sound2inat.app.inference

import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.InferenceJob
import com.sound2inat.inference.InferenceOutcome
import com.sound2inat.inference.InferenceUseCase
import com.sound2inat.inference.PerchAnalysisJob
import com.sound2inat.inference.PerchAnalysisOutcome
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftDetectionCount
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.DraftStatus
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [InferenceQueue].
 *
 * Design note on scope choice:
 * [backgroundScope] is used to match production behaviour (InferenceQueue receives the
 * application scope which is not tied to any particular screen). We intentionally use
 * [runCurrent] instead of [advanceUntilIdle] where the worker must make progress, because
 * [advanceUntilIdle] in kotlinx-coroutines-test stops once only background-scope coroutines
 * are pending — the worker lives in [backgroundScope] and therefore wouldn't be drained.
 * [runCurrent] runs all currently-scheduled tasks regardless of their scope marker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InferenceQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): DraftRepository = DraftRepository(
        drafts = FakeDraftDao(),
        detections = FakeDetectionDao(),
        files = WavFileStore(tmp.root),
        nowMs = { 0L },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun useCase(
        birdnet: InferenceJob = noopJob(),
        birdnetReanalysis: InferenceJob = birdnet,
        perch: PerchAnalysisJob = PerchAnalysisJob { _, _, _, _, _ -> PerchAnalysisOutcome.NotInstalled },
        perchReanalysis: PerchAnalysisJob = perch,
    ): InferenceUseCase = object : InferenceUseCase {
        override val inference = birdnet
        override val inferenceReanalysis = birdnetReanalysis
        override val perchAnalysis = perch
        override val perchReanalysis = perchReanalysis
    }

    private fun noopJob(): InferenceJob = InferenceJob { _, _, _, _, _ ->
        InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
    }

    private fun job(
        draftId: String,
        skipGate: Boolean = true,
        runBirdnet: Boolean = true,
        runPerch: Boolean = false,
    ) = QueuedJob(
        draftId = draftId,
        audioPath = "/tmp/$draftId.wav",
        lat = null,
        lon = null,
        recordedAt = 0L,
        runBirdnet = runBirdnet,
        runPerch = runPerch,
        skipYamNetGate = skipGate,
    )

    @Test
    fun `enqueue returns true and job appears in status`() = runTest {
        val latch = CompletableDeferred<Unit>()
        val blockingJob = InferenceJob { _, _, _, _, _ ->
            latch.await()
            InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
        }
        val queue = InferenceQueue(backgroundScope, useCase(birdnet = blockingJob), repo())

        val result = queue.enqueue(job("d1"))
        runCurrent()

        assertThat(result).isTrue()
        assertThat(queue.status.value).containsKey("d1")

        latch.complete(Unit)
        runCurrent()
        assertThat(queue.status.value).doesNotContainKey("d1")
    }

    @Test
    fun `duplicate enqueue for running job returns false`() = runTest {
        val latch = CompletableDeferred<Unit>()
        val blockingJob = InferenceJob { _, _, _, _, _ ->
            latch.await()
            InferenceOutcome.Success("birdnet_v2_4", "2.4", emptyList())
        }
        val queue = InferenceQueue(backgroundScope, useCase(birdnet = blockingJob), repo())

        val first = queue.enqueue(job("d1"))
        runCurrent()
        val second = queue.enqueue(job("d1"))

        assertThat(first).isTrue()
        assertThat(second).isFalse()

        latch.complete(Unit)
        runCurrent()
    }

    @Test
    fun `cancelQueued removes pending job so worker skips it`() = runTest {
        val latch1 = CompletableDeferred<Unit>()
        var d2Ran = false
        val jobIndex = mutableListOf(
            InferenceJob { _, _, _, _, _ ->
                latch1.await()
                InferenceOutcome.Success("b", "1", emptyList())
            },
            InferenceJob { _, _, _, _, _ ->
                d2Ran = true
                InferenceOutcome.Success("b", "1", emptyList())
            },
        )
        val queue = InferenceQueue(
            backgroundScope,
            useCase(birdnet = InferenceJob { a, b, c, d, e -> jobIndex.removeAt(0).run(a, b, c, d, e) }),
            repo(),
        )
        queue.enqueue(job("d1"))
        runCurrent()
        queue.enqueue(job("d2"))
        runCurrent()

        queue.cancelQueued("d2")

        latch1.complete(Unit)
        runCurrent()

        assertThat(d2Ran).isFalse()
        assertThat(queue.status.value).doesNotContainKey("d2")
    }

    @Test
    fun `skipYamNetGate=false uses inference, true uses inferenceReanalysis`() = runTest {
        var gatedCalls = 0
        var noGateCalls = 0
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    gatedCalls++
                    InferenceOutcome.Success("b", "1", emptyList())
                },
                birdnetReanalysis = InferenceJob { _, _, _, _, _ ->
                    noGateCalls++
                    InferenceOutcome.Success("b", "1", emptyList())
                },
            ),
            repo(),
        )

        queue.enqueue(job("d1", skipGate = false))
        queue.enqueue(job("d2", skipGate = true))
        runCurrent()

        assertThat(gatedCalls).isEqualTo(1)
        assertThat(noGateCalls).isEqualTo(1)
    }

    @Test
    fun `failed inference surfaces Failed status, clearError removes it`() = runTest {
        val queue = InferenceQueue(
            backgroundScope,
            useCase(birdnet = InferenceJob { _, _, _, _, _ -> InferenceOutcome.Failure("Model crash") }),
            repo(),
        )

        queue.enqueue(job("d1"))
        runCurrent()

        val status = queue.status.value["d1"]
        assertThat(status).isInstanceOf(JobStatus.Failed::class.java)
        assertThat((status as JobStatus.Failed).message).isEqualTo("Model crash")

        queue.clearError("d1")
        assertThat(queue.status.value).doesNotContainKey("d1")
    }

    @Test
    fun `re-enqueue after failure clears error and runs again`() = runTest {
        var calls = 0
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    calls++
                    if (calls == 1) {
                        InferenceOutcome.Failure("first attempt")
                    } else {
                        InferenceOutcome.Success("b", "1", emptyList())
                    }
                }
            ),
            repo(),
        )

        queue.enqueue(job("d1"))
        runCurrent()
        assertThat(queue.status.value["d1"]).isInstanceOf(JobStatus.Failed::class.java)

        val requeued = queue.enqueue(job("d1"))
        runCurrent()

        assertThat(requeued).isTrue()
        assertThat(queue.status.value).doesNotContainKey("d1")
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `status shows Running while job executes`() = runTest {
        val latch = CompletableDeferred<Unit>()
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, onProgress ->
                    onProgress(0.5f)
                    latch.await()
                    InferenceOutcome.Success("b", "1", emptyList())
                }
            ),
            repo(),
        )

        queue.enqueue(job("d1"))
        runCurrent()

        val running = queue.status.value["d1"]
        assertThat(running).isInstanceOf(JobStatus.Running::class.java)

        latch.complete(Unit)
        runCurrent()
        assertThat(queue.status.value).doesNotContainKey("d1")
    }

    @Test
    fun `unexpected exception from inferenceJob is caught and surfaces as Failed`() = runTest {
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    throw RuntimeException("TFLite internal error")
                }
            ),
            repo(),
        )

        queue.enqueue(job("d1"))
        runCurrent()

        val status = queue.status.value["d1"]
        assertThat(status).isInstanceOf(JobStatus.Failed::class.java)
        assertThat((status as JobStatus.Failed).message).isEqualTo("TFLite internal error")
    }

    @Test
    fun `runPerch=true without runBirdnet runs only perch path`() = runTest {
        var birdnetCalled = false
        var perchCalled = false
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    birdnetCalled = true
                    InferenceOutcome.Success("b", "1", emptyList())
                },
                perch = PerchAnalysisJob { _, _, _, _, _ ->
                    perchCalled = true
                    PerchAnalysisOutcome.NotInstalled
                },
            ),
            repo(),
        )

        queue.enqueue(job("d1", runBirdnet = false, runPerch = true))
        runCurrent()

        assertThat(birdnetCalled).isFalse()
        assertThat(perchCalled).isTrue()
    }
}

// ── Fake DAOs ──────────────────────────────────────────────────────────────────
// Local copies — DraftDao and DetectionDao are private in ReviewViewModelTest.kt.
// Implements ALL methods from the DAO interfaces.

private class FakeDraftDao : DraftDao {
    private val rows = mutableMapOf<String, DraftEntity>()
    private val emitter = MutableStateFlow<List<DraftEntity>>(emptyList())

    override fun insert(d: DraftEntity) {
        rows[d.id] = d
        emitter.value = rows.values.toList()
    }

    override fun update(d: DraftEntity) {
        rows[d.id] = d
        emitter.value = rows.values.toList()
    }

    override fun delete(d: DraftEntity) {
        rows.remove(d.id)
        emitter.value = rows.values.toList()
    }

    /**
     * Returns the stored entity if present, or a minimal stub DraftEntity for unknown IDs.
     * The stub allows [DraftRepository.mergeAndPersist] → [observeWithDetections] to emit
     * without hanging (the flow would otherwise block forever on a missing draft).
     */
    override fun getById(id: String): DraftEntity? = rows[id] ?: DraftEntity(
        id = id,
        audioPath = "",
        recordedAtUtcMs = 0L,
        durationMs = 0L,
        latitude = null,
        longitude = null,
        locationAccuracyMeters = null,
        status = DraftStatus.PENDING_INFERENCE,
        modelId = null,
        modelVersion = null,
        createdAtUtcMs = 0L,
        updatedAtUtcMs = 0L,
    )

    override fun observeAll(): Flow<List<DraftEntity>> = emitter

    override fun deleteById(id: String): Int =
        if (rows.remove(id) != null) {
            emitter.value = rows.values.toList()
            1
        } else {
            0
        }

    override fun updateStatusConditional(
        id: String,
        newStatus: DraftStatus,
        expectedStatus: DraftStatus,
    ): Int {
        val current = rows[id] ?: return 0
        if (current.status != expectedStatus) return 0
        rows[id] = current.copy(status = newStatus)
        emitter.value = rows.values.toList()
        return 1
    }
}

private class FakeDetectionDao : DetectionDao {
    private val rows = mutableListOf<DetectionEntity>()
    private val emitter = MutableStateFlow<List<DetectionEntity>>(emptyList())

    override fun insertAll(items: List<DetectionEntity>) {
        rows += items
        emitter.value = rows.toList()
    }

    override fun observeForDraft(draftId: String): Flow<List<DetectionEntity>> =
        emitter.map { all -> all.filter { it.draftId == draftId } }

    override fun listForDraft(draftId: String): List<DetectionEntity> =
        rows.filter { it.draftId == draftId }

    override fun setSelected(id: Long, selected: Boolean): Int {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        rows[idx] = rows[idx].copy(isSelectedByUser = selected)
        emitter.value = rows.toList()
        return 1
    }

    override fun deleteForDraft(draftId: String): Int {
        val before = rows.size
        rows.removeAll { it.draftId == draftId }
        emitter.value = rows.toList()
        return before - rows.size
    }

    override fun observeCountsByDraft(): Flow<List<DraftDetectionCount>> =
        flowOf(emptyList())
}
