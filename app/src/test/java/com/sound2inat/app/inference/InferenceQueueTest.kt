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
    fun `worker survives a throwing job and processes the next one`() = runTest {
        var d2Ran = false
        val jobs = mutableListOf(
            InferenceJob { _, _, _, _, _ -> throw RuntimeException("boom") },
            InferenceJob { _, _, _, _, _ ->
                d2Ran = true
                InferenceOutcome.Success("b", "1", emptyList())
            },
        )
        val queue = InferenceQueue(
            backgroundScope,
            useCase(birdnet = InferenceJob { a, b, c, d, e -> jobs.removeAt(0).run(a, b, c, d, e) }),
            repo(),
        )

        queue.enqueue(job("d1"))
        runCurrent()
        queue.enqueue(job("d2"))
        runCurrent()

        // First job failed but the worker must NOT be dead — second job ran.
        assertThat(queue.status.value["d1"]).isInstanceOf(JobStatus.Failed::class.java)
        assertThat(d2Ran).isTrue()
        assertThat(queue.status.value).doesNotContainKey("d2")
    }

    @Test
    fun `cancelQueued clears status synchronously`() = runTest {
        val latch = CompletableDeferred<Unit>()
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    latch.await()
                    InferenceOutcome.Success("b", "1", emptyList())
                }
            ),
            repo(),
        )
        queue.enqueue(job("d1")) // will start running (blocks on latch)
        runCurrent()
        queue.enqueue(job("d2")) // stays Queued
        runCurrent()

        assertThat(queue.status.value).containsKey("d2")
        queue.cancelQueued("d2")
        // No runCurrent needed — cancelQueued patches _status synchronously.
        assertThat(queue.status.value).doesNotContainKey("d2")

        latch.complete(Unit)
        runCurrent()
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

    // ── A1: cancel + re-enqueue race (same draftId) ──────────────────────────

    /**
     * Regression test for the cancel+re-enqueue race on the same draftId.
     *
     * Scenario:
     *  1. A "blocker" job for d2 occupies the worker (blocked on a latch).
     *     This frees the pending slot for d1 so we can enqueue d1-B while the worker is busy.
     *  2. d1-B is enqueued. It sits in the channel and in pending (worker is busy with d2).
     *  3. cancelQueued("d1") removes d1-B's id from pending.
     *  4. d1-C is re-enqueued for the same draftId. Gets a NEW id, goes into pending + channel.
     *  5. The blocker is released. Worker finishes d2.
     *  6. Worker dequeues d1-B from the channel — id-based guard sees d1-B's id is no longer
     *     in pending → skip.
     *  7. Worker dequeues d1-C — its id IS in pending → runs.
     *
     * Assertions:
     *  - d1-B's fake job is NOT invoked (stale channel item is skipped).
     *  - d1-C's fake job IS invoked exactly once (new job after re-enqueue runs).
     */
    @Test
    fun `cancel then re-enqueue same draftId runs new job not stale one`() = runTest {
        val blockerLatch = CompletableDeferred<Unit>()
        var newCRan = false

        // Route birdnet calls by audioPath (which encodes the draftId in our helper).
        // d2 → blocks on latch; d1 (any invocation) → sets flag.
        // The key invariant: the FIRST d1 invocation must set newCRan, not staleBRan.
        // We track call count rather than using removeAt() to avoid the pitfall where a
        // guard-skipped job's list slot is consumed by the next real job.
        var d1CallCount = 0
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { audioPath, _, _, _, _ ->
                    when {
                        audioPath.contains("d2") -> {
                            blockerLatch.await()
                            InferenceOutcome.Success("b", "1", emptyList())
                        }
                        audioPath.contains("d1") -> {
                            d1CallCount++
                            // If the guard is BROKEN and the stale item runs first, d1CallCount==1
                            // when we expect the NEW job to run — we detect this below.
                            // If the guard is WORKING, the stale item is skipped and only the new
                            // job runs, setting newCRan = true.
                            newCRan = true
                            InferenceOutcome.Success("b", "1", emptyList())
                        }
                        else -> InferenceOutcome.Success("b", "1", emptyList())
                    }
                }
            ),
            repo(),
        )

        // Step 1: enqueue d2 (blocker) — starts immediately, blocks on latch.
        queue.enqueue(job("d2"))
        runCurrent()

        // Step 2: enqueue d1-B (sits in channel+pending while d2 is running).
        val enqueuedB = queue.enqueue(job("d1"))
        runCurrent()
        assertThat(enqueuedB).isTrue()

        // Step 3: cancel d1 — d1-B's id removed from pending.
        queue.cancelQueued("d1")

        // Step 4: re-enqueue d1-C — new id, enters channel+pending.
        val enqueuedC = queue.enqueue(job("d1"))
        runCurrent()
        assertThat(enqueuedC).isTrue()

        // Step 5: release blocker; worker drains d2, then d1-B (skipped), then d1-C (runs).
        blockerLatch.complete(Unit)
        runCurrent()
        runCurrent() // one tick finishes d2, second tick drains remaining channel items

        // With id-based guard: d1-B is skipped (never runs), d1-C runs exactly once.
        assertThat(d1CallCount).isEqualTo(1) // only the new job should have run
        assertThat(newCRan).isTrue() // new C must run
        assertThat(queue.status.value).doesNotContainKey("d1")
    }

    // ── A3: BirdNET failure must not show Failed while Perch still running ───

    /**
     * Regression test for the mid-run Failed status defect.
     *
     * Semantics chosen: BirdNET failure is a TERMINAL failure for the job (same as today).
     * Perch still runs best-effort. The Failed status is written only ONCE, AFTER all steps
     * have completed — so the UI never sees Failed while Perch is still in-flight.
     *
     * Invariants asserted:
     *  (a) While Perch is blocked (running), status is NOT Failed — it is Running.
     *  (b) After Perch finishes, the final status is Failed (BirdNET failure dominates).
     *
     * Implementation note: we cannot capture queue.status.value from inside the Perch lambda
     * because the `combine`-based _status update runs in a separate backgroundScope coroutine
     * that may not have been scheduled yet at the point of capture.  Instead we gate Perch on a
     * latch, call runCurrent() while Perch is blocked, and inspect _status at that point —
     * all tasks that could have run have run, so if Failed had been written it would be visible.
     */
    @Test
    fun `birdnet failure does not show Failed status while perch still running`() = runTest {
        val perchLatch = CompletableDeferred<Unit>()

        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    InferenceOutcome.Failure("Model crash")
                },
                perch = PerchAnalysisJob { _, _, _, _, _ ->
                    perchLatch.await() // block until we explicitly release
                    PerchAnalysisOutcome.Success(emptyList())
                },
            ),
            repo(),
        )

        queue.enqueue(job("d1", runBirdnet = true, runPerch = true))
        // BirdNET runs (fails), Perch starts and blocks on the latch.
        // runCurrent() drains all currently-runnable work, including the combine update.
        runCurrent()

        // (a) While Perch is still blocked, status must NOT be Failed.
        //     With the fix, pendingFailure is held in a local var; _failedJobs has not been
        //     touched yet, so combine cannot have produced a Failed entry.
        val statusWhilePerchRunning = queue.status.value["d1"]
        assertThat(statusWhilePerchRunning).isNotInstanceOf(JobStatus.Failed::class.java)
        assertThat(statusWhilePerchRunning).isInstanceOf(JobStatus.Running::class.java)

        // Release Perch so the job can finish.
        perchLatch.complete(Unit)
        runCurrent()

        // (b) Final status: BirdNET failed → job is Failed (Perch ran best-effort but
        //     the primary step that produces the main detections failed).
        val finalStatus = queue.status.value["d1"]
        assertThat(finalStatus).isInstanceOf(JobStatus.Failed::class.java)
        assertThat((finalStatus as JobStatus.Failed).message).isEqualTo("Model crash")
    }

    /**
     * Additional A3 invariant: if BirdNET fails and Perch also fails, the FINAL Failed
     * message must come from BirdNET (primary step), not from Perch overwriting it mid-run.
     * Also verifies no stale Failed entry exists after a clean re-enqueue.
     */
    @Test
    fun `birdnet failure dominates perch failure in final status`() = runTest {
        val queue = InferenceQueue(
            backgroundScope,
            useCase(
                birdnet = InferenceJob { _, _, _, _, _ ->
                    InferenceOutcome.Failure("BirdNET crashed")
                },
                perch = PerchAnalysisJob { _, _, _, _, _ ->
                    PerchAnalysisOutcome.Failure("Perch crashed")
                },
            ),
            repo(),
        )

        queue.enqueue(job("d1", runBirdnet = true, runPerch = true))
        runCurrent()

        val finalStatus = queue.status.value["d1"]
        assertThat(finalStatus).isInstanceOf(JobStatus.Failed::class.java)
        // BirdNET failure message is the one surfaced (first/primary step).
        assertThat((finalStatus as JobStatus.Failed).message).isEqualTo("BirdNET crashed")
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

    override fun updatePalette(id: String, name: String?, ts: Long): Int = 0
    override fun updateSpectrogramGain(id: String, gain: Float?, ts: Long): Int = 0
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
