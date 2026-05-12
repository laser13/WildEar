package com.sound2inat.app.ui.photos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class PhotosViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val instant = InstantTaskExecutorRule()

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
            ioDispatcher = UnconfinedTestDispatcher(),
            runInTransaction = { block -> db.runInTransaction(block) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `photos tab shows summaries newest first`() = runTest {
        val oldId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val newId = repo.createDraft(2L, latitude = null, longitude = null, accuracyMeters = null)

        val vm = PhotosViewModel(repo, externalScope = TestScope(UnconfinedTestDispatcher()))

        assertThat(vm.state.value.drafts.map { it.id }).containsExactly(newId, oldId).inOrder()
    }
}
