package com.sound2inat.inat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
class PhotoObservationSyncUseCaseTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: Sound2iNatDb
    private lateinit var repo: PhotoDraftRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        repo = PhotoDraftRepository(
            draftDao = db.photoDrafts(),
            imageDao = db.photoDraftImages(),
            fileStore = PhotoObservationFileStore(tmp.root),
            ioDispatcher = dispatcher,
            runInTransaction = { it() },
        )
    }

    @After fun tearDown() = db.close()

    private fun clientReturning(byId: Map<Long, ObservationDetail>) =
        object : INaturalistClient(OkHttpClient(), ioDispatcher = dispatcher) {
            override suspend fun getObservation(idOrUuid: String): ObservationDetail {
                val id = idOrUuid.toLong()
                return byId[id] ?: error("no stub for $id")
            }
        }

    private fun detail(name: String?, common: String? = null) =
        ObservationDetail(
            qualityGrade = "needs_id",
            agreeingIdCount = 0,
            commentsCount = 0,
            comments = emptyList(),
            taxonName = name,
            taxonCommonName = common,
        )

    @Test
    fun `syncAll persists confirmed taxa and counts them`() = runTest {
        val a = repo.createDraft(observedAtUtcMs = 1L, latitude = null, longitude = null, accuracyMeters = null)
        val b = repo.createDraft(observedAtUtcMs = 2L, latitude = null, longitude = null, accuracyMeters = null)
        val client = clientReturning(mapOf(10L to detail("Turdus merula", "Blackbird"), 20L to detail(null)))
        val useCase = PhotoObservationSyncUseCase(client, repo, dispatcher)

        val result = useCase.syncAll(listOf(a to 10L, b to 20L))

        assertThat(db.photoDrafts().getById(a)!!.taxonScientificName).isEqualTo("Turdus merula")
        assertThat(db.photoDrafts().getById(b)!!.taxonScientificName).isNull()
        assertThat(result.synced).isEqualTo(1)
        assertThat(result.failed).isEqualTo(0)
    }

    @Test
    fun `syncAll is best-effort - one failure does not abort the batch`() = runTest {
        val a = repo.createDraft(observedAtUtcMs = 1L, latitude = null, longitude = null, accuracyMeters = null)
        val b = repo.createDraft(observedAtUtcMs = 2L, latitude = null, longitude = null, accuracyMeters = null)
        val client = object : INaturalistClient(OkHttpClient(), ioDispatcher = dispatcher) {
            override suspend fun getObservation(idOrUuid: String): ObservationDetail {
                if (idOrUuid == "10") throw RuntimeException("boom")
                return detail("Parus major", "Great Tit")
            }
        }
        val useCase = PhotoObservationSyncUseCase(client, repo, dispatcher)

        val result = useCase.syncAll(listOf(a to 10L, b to 20L))

        assertThat(result.synced).isEqualTo(1)
        assertThat(result.failed).isEqualTo(1)
        assertThat(db.photoDrafts().getById(b)!!.taxonScientificName).isEqualTo("Parus major")
    }
}
