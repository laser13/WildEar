package com.sound2inat.app.ui.photos

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.storage.PhotoDraftRepository
import com.sound2inat.storage.PhotoObservationFileStore
import com.sound2inat.storage.Sound2iNatDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class PhotoCaptureViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val instant = InstantTaskExecutorRule()

    private lateinit var db: Sound2iNatDb
    private lateinit var fileStore: PhotoObservationFileStore
    private lateinit var repo: PhotoDraftRepository
    private var nextId = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            Sound2iNatDb::class.java,
        ).allowMainThreadQueries().build()
        fileStore = PhotoObservationFileStore(tmp.root)
        repo = PhotoDraftRepository(
            draftDao = db.photoDrafts(),
            imageDao = db.photoDraftImages(),
            fileStore = fileStore,
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
    fun `capture is ready when camera permission is granted`() = runTest {
        val vm = viewModel()

        vm.initWithPermissions(FakePermissions(Permission.CAMERA to PermissionStatus.GRANTED))

        assertThat(vm.state.value.canBindCamera).isTrue()
        assertThat(vm.state.value.showCameraPermissionDenied).isFalse()
        assertThat(vm.state.value.draftId).isEqualTo("id1")
    }

    @Test
    fun `capture shows denial state when camera permission is denied`() = runTest {
        val vm = viewModel()

        vm.initWithPermissions(FakePermissions(Permission.CAMERA to PermissionStatus.DENIED))

        assertThat(vm.state.value.canBindCamera).isFalse()
        assertThat(vm.state.value.showCameraPermissionDenied).isTrue()
    }

    @Test
    fun `done stays disabled until first photo is saved`() = runTest {
        val vm = viewModel()
        vm.initWithPermissions(FakePermissions(Permission.CAMERA to PermissionStatus.GRANTED))

        assertThat(vm.state.value.doneEnabled).isFalse()

        val prepared = vm.prepareOutputFile()
        prepared.file.writeText("jpeg")
        vm.onPhotoSaved(prepared.photoId, prepared.file, width = 4000, height = 3000)

        assertThat(vm.state.value.doneEnabled).isTrue()
        assertThat(vm.state.value.photoCount).isEqualTo(1)
    }

    @Test
    fun `existing draft id is reused when adding more photos`() = runTest {
        val existingId = repo.createDraft(1L, latitude = null, longitude = null, accuracyMeters = null)
        val vm = viewModel(SavedStateHandle(mapOf("draftId" to existingId)))

        vm.initWithPermissions(FakePermissions(Permission.CAMERA to PermissionStatus.GRANTED))

        assertThat(vm.state.value.draftId).isEqualTo(existingId)
        assertThat(db.photoDrafts().observeAll().first()).hasSize(1)
    }

    private fun viewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        locationProvider: LocationProvider = FakeLocation(Fix(35.1, 33.3, 4f, 1L)),
    ): PhotoCaptureViewModel = PhotoCaptureViewModel(
        savedStateHandle = savedStateHandle,
        repo = repo,
        fileStore = fileStore,
        locationProvider = locationProvider,
        nowMs = { 100L },
    )
}

private class FakePermissions(
    vararg initial: Pair<Permission, PermissionStatus>,
) : PermissionsController {
    private val _state = MutableStateFlow(initial.toMap())
    override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _state

    override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> =
        permissions.associateWith { _state.value[it] ?: PermissionStatus.DENIED }

    override fun openAppSettings() = Unit
}

private class FakeLocation(private val fix: Fix?) : LocationProvider {
    override suspend fun getCurrent(timeoutMs: Long): Fix? = fix
}
