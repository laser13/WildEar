package com.sound2inat.app.recording

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeRecordingController : RecordingController {
    var startCalled = false
    var startCount = 0
    var stopCalled = false
    var cancelCalled = false

    private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    override val state: StateFlow<RecordingSessionState> = _state
    override val rmsHistory: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0))
    override val audioBlocks: SharedFlow<FloatArray> = MutableSharedFlow()
    override val sampleRateHz: Int = 48_000

    override suspend fun start() { startCalled = true; startCount++ }
    override suspend fun stop() { stopCalled = true }
    override fun cancel() { cancelCalled = true }

    fun setState(s: RecordingSessionState) { _state.value = s }
}
