package com.sound2inat.storage

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class DraftRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val instant = InstantTaskExecutorRule()

    private lateinit var db: Sound2iNatDb
    private lateinit var repo: DraftRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        repo = DraftRepository(db.drafts(), db.detections(), WavFileStore(tmp.root), nowMs = { 0L })
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `create then attach detections updates status and saves rows`() = runTest {
        repo.create("d1", "/tmp/a.wav", 100L, 3000L, null, null, null)
        repo.attachDetections(
            "d1",
            "m",
            "1.0",
            listOf(
                AggregatedDetection("Sylvia melanothorax", "Cyprus Warbler", 0.9f, 5, 0L, 5_000L),
            ),
        )
        val d = db.drafts().getById("d1")!!
        assertThat(d.status).isEqualTo(DraftStatus.PENDING_REVIEW)
        assertThat(d.modelId).isEqualTo("m")
        val detections = db.detections().listForDraft("d1")
        assertThat(detections).hasSize(1)
        assertThat(detections[0].taxonScientificName).isEqualTo("Sylvia melanothorax")
    }

    @Test
    fun `delete cascades detections`() = runTest {
        repo.create("d1", "/tmp/a.wav", 100L, 3000L, null, null, null)
        repo.attachDetections(
            "d1",
            "m",
            "1.0",
            listOf(
                AggregatedDetection("A", null, 0.5f, 1, 0L, 3000L),
            ),
        )
        repo.delete("d1")
        assertThat(db.drafts().getById("d1")).isNull()
        assertThat(db.detections().listForDraft("d1")).isEmpty()
    }

    @Test
    fun `observeAll emits drafts ordered by recordedAt desc`() = runTest {
        repo.create("a", "/tmp/a.wav", 100L, 1000L, null, null, null)
        repo.create("b", "/tmp/b.wav", 200L, 1000L, null, null, null)
        val list = repo.observeAll().first()
        assertThat(list.map { it.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `createWithDetections inserts draft and detections in PENDING_REVIEW`() = runTest {
        val det = AggregatedDetection(
            taxonScientificName = "Turdus merula",
            taxonCommonName = "Blackbird",
            maxConfidence = 0.8f,
            detectedWindows = 2,
            firstSeenMs = 0L,
            lastSeenMs = 4500L,
        )
        repo.createWithDetections(
            id = "d1",
            audioPath = "/x.wav",
            recordedAtUtcMs = 1L,
            durationMs = 5_000L,
            latitude = 50.0,
            longitude = 14.0,
            accuracyMeters = 5f,
            modelId = "birdnet_v2_4",
            modelVersion = "2.4",
            detections = listOf(det),
        )
        val saved = db.drafts().getById("d1")!!
        assertThat(saved.status).isEqualTo(DraftStatus.PENDING_REVIEW)
        assertThat(saved.modelId).isEqualTo("birdnet_v2_4")
        assertThat(saved.modelVersion).isEqualTo("2.4")
        val detections = db.detections().listForDraft("d1")
        assertThat(detections).hasSize(1)
        assertThat(detections[0].taxonScientificName).isEqualTo("Turdus merula")
        assertThat(detections[0].detectedWindows).isEqualTo(2)
    }

    @Test
    fun `markReviewed transitions status`() = runTest {
        repo.create("d1", "/tmp/a.wav", 100L, 3000L, null, null, null)
        repo.attachDetections("d1", "m", "1.0", emptyList())
        repo.markReviewed("d1")
        assertThat(db.drafts().getById("d1")!!.status).isEqualTo(DraftStatus.REVIEWED)
    }
}
