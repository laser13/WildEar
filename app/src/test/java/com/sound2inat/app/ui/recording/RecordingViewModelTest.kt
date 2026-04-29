package com.sound2inat.app.ui.recording

import com.google.common.truth.Truth.assertThat
import com.sound2inat.app.permissions.Permission
import com.sound2inat.app.permissions.PermissionStatus
import com.sound2inat.app.permissions.PermissionsController
import com.sound2inat.location.Fix
import com.sound2inat.location.LocationProvider
import com.sound2inat.recorder.Recorder
import com.sound2inat.recorder.RecordingResult
import com.sound2inat.storage.DetectionDao
import com.sound2inat.storage.DetectionEntity
import com.sound2inat.storage.DraftDao
import com.sound2inat.storage.DraftEntity
import com.sound2inat.storage.DraftRepository
import com.sound2inat.storage.WavFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class FakePerms(
    private val grants: Map<Permission, PermissionStatus>,
) : PermissionsController {
    private val _statuses = MutableStateFlow(grants)
    override val statuses: StateFlow<Map<Permission, PermissionStatus>> = _statuses
    override suspend fun request(permissions: Set<Permission>): Map<Permission, PermissionStatus> = grants
    override fun openAppSettings() = Unit
}

private class FakeRecorder : Recorder {
    private val _rms = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rms
    var startCalled = false
    var stopCalled = false
    var cancelled = false
    var lastTarget: File? = null

    override suspend fun start(target: File) {
        startCalled = true
        lastTarget = target
        target.createNewFile()
    }

    override suspend fun stop(): RecordingResult {
        stopCalled = true
        val t = lastTarget!!
        return RecordingResult(t.absolutePath, durationMs = 1234L, sampleRate = 48_000, channels = 1)
    }

    override fun cancel() {
        cancelled = true
        lastTarget?.delete()
    }
}

private class FakeLocation(private val out: Fix?) : LocationProvider {
    override suspend fun getCurrent(timeoutMs: Long): Fix? = out
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var files: WavFileStore
    private lateinit var drafts: DraftRepository
    private lateinit var draftDao: FakeDraftDao
    private lateinit var detectionDao: FakeDetectionDao

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        files = WavFileStore(tmp.root)
        draftDao = FakeDraftDao()
        detectionDao = FakeDetectionDao()
        drafts = DraftRepository(draftDao, detectionDao, files) { 0L }
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `start without RECORD_AUDIO surfaces Error`() = runTest {
        val vm = RecordingViewModel(
            perms = FakePerms(mapOf(Permission.RECORD_AUDIO to PermissionStatus.DENIED)),
            recorder = FakeRecorder(),
            location = FakeLocation(null),
            files = files,
            drafts = drafts,
        )
        vm.start()
        advanceUntilIdle()
        val s = vm.state.value as RecordingUiState.Error
        assertThat(s.message).contains("Microphone")
    }

    @Test
    fun `happy path produces Done with draft ID and persists draft`() = runTest {
        val rec = FakeRecorder()
        val vm = RecordingViewModel(
            perms = FakePerms(
                mapOf(
                    Permission.RECORD_AUDIO to PermissionStatus.GRANTED,
                    Permission.ACCESS_FINE_LOCATION to PermissionStatus.GRANTED,
                ),
            ),
            recorder = rec,
            location = FakeLocation(Fix(34.7, 33.04, 5f, 1L)),
            files = files,
            drafts = drafts,
            nowMs = { 0L },
        )
        vm.start()
        advanceUntilIdle()
        vm.stop()
        advanceUntilIdle()
        val done = vm.state.value as RecordingUiState.Done
        assertThat(done.draftId).isNotEmpty()
        assertThat(rec.startCalled).isTrue()
        assertThat(rec.stopCalled).isTrue()
        assertThat(draftDao.inserted).hasSize(1)
        val saved = draftDao.inserted.first()
        assertThat(saved.id).isEqualTo(done.draftId)
        assertThat(saved.latitude).isEqualTo(34.7)
    }

    @Test
    fun `cancel returns to Idle and deletes file`() = runTest {
        val rec = FakeRecorder()
        val vm = RecordingViewModel(
            perms = FakePerms(
                mapOf(
                    Permission.RECORD_AUDIO to PermissionStatus.GRANTED,
                    Permission.ACCESS_FINE_LOCATION to PermissionStatus.GRANTED,
                ),
            ),
            recorder = rec,
            location = FakeLocation(null),
            files = files,
            drafts = drafts,
        )
        vm.start()
        advanceUntilIdle()
        vm.cancel()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(RecordingUiState.Idle)
        assertThat(rec.cancelled).isTrue()
    }
}

// In-memory DAOs for the test (no Room here — it's a VM unit test, not storage test).
private class FakeDraftDao : DraftDao {
    val inserted = mutableListOf<DraftEntity>()

    override fun insert(d: DraftEntity) { inserted += d }
    override fun update(d: DraftEntity) = Unit
    override fun delete(d: DraftEntity) = Unit
    override fun getById(id: String): DraftEntity? = inserted.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<DraftEntity>> = flowOf(inserted.toList())
    override fun deleteById(id: String): Int = if (inserted.removeAll { it.id == id }) 1 else 0
}

private class FakeDetectionDao : DetectionDao {
    override fun insertAll(items: List<DetectionEntity>) = Unit
    override fun observeForDraft(draftId: String): Flow<List<DetectionEntity>> = flowOf(emptyList())
    override fun listForDraft(draftId: String): List<DetectionEntity> = emptyList()
    override fun setSelected(id: Long, selected: Boolean): Int = 0
    override fun deleteForDraft(draftId: String): Int = 0
}
