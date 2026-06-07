package com.sound2inat.app.ui.photos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.inat.PhotoObservationSyncUseCase
import com.sound2inat.inat.PhotoSyncResult
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
class PhotosViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val instant = InstantTaskExecutorRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: Sound2iNatDb
    private lateinit var repo: PhotoDraftRepository
    private var nextId = 0

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
            nowMs = { 10L },
            idFactory = { "id${++nextId}" },
            ioDispatcher = dispatcher,
            runInTransaction = { block -> db.runInTransaction(block) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fakeSyncUseCase(onSync: (List<Pair<String, Long>>) -> PhotoSyncResult): PhotoObservationSyncUseCase =
        object : PhotoObservationSyncUseCase(
            object : com.sound2inat.inat.INaturalistClient(OkHttpClient(), ioDispatcher = dispatcher) {},
            repo,
            dispatcher,
        ) {
            override suspend fun syncAll(targets: List<Pair<String, Long>>): PhotoSyncResult = onSync(targets)
        }

    @Test
    fun `photos tab shows summaries newest first`() = runTest {
        val oldId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val newId = repo.createDraft(2L, latitude = null, longitude = null, accuracyMeters = null)

        val vm = PhotosViewModel(
            repo,
            fakeSyncUseCase { PhotoSyncResult(0, 0) },
            externalScope = TestScope(UnconfinedTestDispatcher()),
        )

        assertThat(vm.state.value.drafts.map { it.id }).containsExactly(newId, oldId).inOrder()
    }

    @Test
    fun `refresh only targets drafts with an inat observation id and reports result`() = runTest {
        val uploaded = repo.createDraft(observedAtUtcMs = 1L, latitude = null, longitude = null, accuracyMeters = null)
        repo.markUploaded(uploaded, observationId = 77L, observationUuid = "u", observationUrl = "url")
        repo.createDraft(observedAtUtcMs = 2L, latitude = null, longitude = null, accuracyMeters = null)

        val seen = mutableListOf<List<Pair<String, Long>>>()
        val fakeUseCase = fakeSyncUseCase { targets ->
            seen += targets
            PhotoSyncResult(synced = targets.size, failed = 0)
        }
        val vm = PhotosViewModel(repo, fakeUseCase, externalScope = TestScope(UnconfinedTestDispatcher()))
        vm.state.first { !it.isLoading }

        vm.refresh()

        assertThat(seen).hasSize(1)
        assertThat(seen.first()).containsExactly(uploaded to 77L)
        assertThat(vm.state.value.lastSyncResult).isEqualTo(PhotoSyncResult(1, 0))
        assertThat(vm.state.value.isRefreshing).isFalse()
    }
}
