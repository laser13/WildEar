package com.sound2inat.storage

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.inference.AggregatedDetection
import com.sound2inat.inference.FragmentRange
import com.sound2inat.inference.SourceStats
import kotlinx.coroutines.Dispatchers
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
class DraftRepositoryMergeTest {

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
        repo = DraftRepository(
            drafts = db.drafts(),
            detections = db.detections(),
            files = WavFileStore(tmp.root),
            nowMs = { 0L },
            runInTransaction = { block -> db.runInTransaction(block) },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() { db.close() }

    /**
     * Seed BirdNET detections for draft d1, then mergeAndPersist Perch detections
     * for the same taxon. Assert that:
     * - sources string contains both birdnet and perch
     * - maxConfidence is the max of the two
     * - detectedWindows is the sum of both
     * - fragment ranges from both runs are concatenated
     */
    @Test
    fun `mergeAndPersist unions sources, max confidence, and concatenates windows`() = runTest {
        // Create draft in PENDING_INFERENCE state
        repo.create("d1", "/tmp/a.wav", 100L, 5_000L, null, null, null)

        // Attach initial BirdNET detections
        repo.attachDetections(
            draftId = "d1",
            modelId = "birdnet_v2_4",
            modelVersion = "2.4",
            items = listOf(
                AggregatedDetection(
                    taxonScientificName = "Turdus merula",
                    taxonCommonName = "Blackbird",
                    maxConfidence = 0.7f,
                    detectedWindows = 3,
                    firstSeenMs = 0L,
                    lastSeenMs = 3_000L,
                    confidenceBySource = mapOf("birdnet_v2_4" to 0.7f),
                    windowsBySource = mapOf("birdnet_v2_4" to 3),
                    firstSeenBySource = mapOf("birdnet_v2_4" to 0L),
                    lastSeenBySource = mapOf("birdnet_v2_4" to 3_000L),
                    fragmentRanges = listOf(FragmentRange(0L, 3_000L)),
                    aggregatedConfidence = 0.65f,
                ),
            ),
        )

        // Now merge Perch detections — same species with higher confidence
        val perchDetections = listOf(
            AggregatedDetection(
                taxonScientificName = "Turdus merula",
                taxonCommonName = "Blackbird",
                maxConfidence = 0.9f,
                detectedWindows = 2,
                firstSeenMs = 1_000L,
                lastSeenMs = 4_000L,
                confidenceBySource = mapOf("perch_v2" to 0.9f),
                windowsBySource = mapOf("perch_v2" to 2),
                firstSeenBySource = mapOf("perch_v2" to 1_000L),
                lastSeenBySource = mapOf("perch_v2" to 4_000L),
                fragmentRanges = listOf(FragmentRange(1_000L, 2_000L), FragmentRange(3_000L, 4_000L)),
                aggregatedConfidence = 0.88f,
            ),
        )

        repo.mergeAndPersist(
            draftId = "d1",
            newModelId = "perch_v2",
            newModelVersion = "perch",
            freshDetections = perchDetections,
        )

        val detections = db.detections().listForDraft("d1")
        assertThat(detections).hasSize(1)

        val row = detections[0]
        assertThat(row.taxonScientificName).isEqualTo("Turdus merula")

        // Max confidence should be 0.9 (perch wins)
        assertThat(row.maxConfidence).isEqualTo(0.9f)

        // Total windows: 3 (birdnet) + 2 (perch) = 5
        assertThat(row.detectedWindows).isEqualTo(5)

        // First seen: min(0, 1000) = 0
        assertThat(row.firstSeenMs).isEqualTo(0L)

        // Last seen: max(3000, 4000) = 4000
        assertThat(row.lastSeenMs).isEqualTo(4_000L)

        // Sources must contain both birdnet and perch
        val sources = SourceStats.decode(row.sources)
        assertThat(sources).containsKey("birdnet_v2_4")
        assertThat(sources).containsKey("perch_v2")
        assertThat(sources["birdnet_v2_4"]!!.maxConf).isEqualTo(0.7f)
        assertThat(sources["perch_v2"]!!.maxConf).isEqualTo(0.9f)

        // Fragment ranges from both runs must be concatenated (1 from birdnet + 2 from perch = 3)
        val decodedRanges = com.sound2inat.inference.FragmentRanges.decode(row.fragmentRanges)
        assertThat(decodedRanges).hasSize(3)
        assertThat(decodedRanges).contains(com.sound2inat.inference.FragmentRange(0L, 3_000L))
        assertThat(decodedRanges).contains(com.sound2inat.inference.FragmentRange(1_000L, 2_000L))
        assertThat(decodedRanges).contains(com.sound2inat.inference.FragmentRange(3_000L, 4_000L))

        // Aggregated confidence must be max(0.65, 0.88) = 0.88
        assertThat(row.aggregatedConfidence).isEqualTo(0.88f)

        // modelId on draft should be combined
        val draft = db.drafts().getById("d1")!!
        assertThat(draft.modelId).isEqualTo("birdnet_v2_4,perch_v2")
        assertThat(draft.modelVersion).isEqualTo("2.4+perch")
    }

    @Test
    fun `mergeAndPersist with non-overlapping species adds both rows`() = runTest {
        repo.create("d1", "/tmp/a.wav", 100L, 5_000L, null, null, null)

        repo.attachDetections(
            draftId = "d1",
            modelId = "birdnet_v2_4",
            modelVersion = "2.4",
            items = listOf(
                AggregatedDetection(
                    taxonScientificName = "Sylvia communis",
                    taxonCommonName = "Whitethroat",
                    maxConfidence = 0.6f,
                    detectedWindows = 2,
                    firstSeenMs = 0L,
                    lastSeenMs = 2_000L,
                    confidenceBySource = mapOf("birdnet_v2_4" to 0.6f),
                    windowsBySource = mapOf("birdnet_v2_4" to 2),
                    firstSeenBySource = mapOf("birdnet_v2_4" to 0L),
                    lastSeenBySource = mapOf("birdnet_v2_4" to 2_000L),
                ),
            ),
        )

        repo.mergeAndPersist(
            draftId = "d1",
            newModelId = "perch_v2",
            newModelVersion = "perch",
            freshDetections = listOf(
                AggregatedDetection(
                    taxonScientificName = "Turdus merula",
                    taxonCommonName = "Blackbird",
                    maxConfidence = 0.8f,
                    detectedWindows = 1,
                    firstSeenMs = 500L,
                    lastSeenMs = 1_500L,
                    confidenceBySource = mapOf("perch_v2" to 0.8f),
                    windowsBySource = mapOf("perch_v2" to 1),
                    firstSeenBySource = mapOf("perch_v2" to 500L),
                    lastSeenBySource = mapOf("perch_v2" to 1_500L),
                ),
            ),
        )

        val detections = db.detections().listForDraft("d1")
        // Should have both species, sorted by maxConfidence desc
        assertThat(detections).hasSize(2)
        assertThat(detections[0].taxonScientificName).isEqualTo("Turdus merula") // 0.8 > 0.6
        assertThat(detections[1].taxonScientificName).isEqualTo("Sylvia communis")
    }

    @Test
    fun `mergeAndPersist with promoteToReviewed marks draft as REVIEWED`() = runTest {
        repo.create("d1", "/tmp/a.wav", 100L, 5_000L, null, null, null)

        repo.mergeAndPersist(
            draftId = "d1",
            newModelId = "birdnet_v2_4",
            newModelVersion = "2.4",
            freshDetections = listOf(
                AggregatedDetection(
                    taxonScientificName = "Parus major",
                    taxonCommonName = "Great Tit",
                    maxConfidence = 0.85f,
                    detectedWindows = 4,
                    firstSeenMs = 0L,
                    lastSeenMs = 4_000L,
                    confidenceBySource = mapOf("birdnet_v2_4" to 0.85f),
                    windowsBySource = mapOf("birdnet_v2_4" to 4),
                    firstSeenBySource = mapOf("birdnet_v2_4" to 0L),
                    lastSeenBySource = mapOf("birdnet_v2_4" to 4_000L),
                ),
            ),
            promoteToReviewed = true,
        )

        val draft = db.drafts().getById("d1")!!
        assertThat(draft.status).isEqualTo(DraftStatus.REVIEWED)
    }

    @Test
    fun `mergeAndPersist empty detections does not promote to reviewed`() = runTest {
        repo.create("d1", "/tmp/a.wav", 100L, 5_000L, null, null, null)

        repo.mergeAndPersist(
            draftId = "d1",
            newModelId = "birdnet_v2_4",
            newModelVersion = "2.4",
            freshDetections = emptyList(),
            promoteToReviewed = true,
        )

        // With empty merged result, status should NOT be promoted to REVIEWED
        val draft = db.drafts().getById("d1")!!
        assertThat(draft.status).isEqualTo(DraftStatus.PENDING_REVIEW)
    }
}
