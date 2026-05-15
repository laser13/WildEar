package com.sound2inat.recorder

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

interface Recorder {
    val rmsLevel: StateFlow<Float>

    /**
     * Rolling history of the last [HISTORY_SIZE] RMS samples (oldest first).
     * Lets the UI render a live waveform/VU bar without re-deriving anything.
     * Reset to an empty array on each [start].
     */
    val rmsHistory: StateFlow<FloatArray>

    /**
     * Hot stream of raw PCM blocks scaled by `Short.MAX_VALUE` to floats in
     * approximately `[-1, 1]` (the most-negative sample maps to ≈ -1.00003 — one LSB
     * past the unit interval). Live consumers (spectrogram, BirdNET) collect this
     * in parallel with the WAV writer. Backed by a SharedFlow with replay = 1
     * (so a late subscriber gets the most recent block) and DROP_OLDEST overflow,
     * so a slow consumer cannot stall recording. Empty before the first block
     * arrives after [start].
     */
    val audioBlocks: SharedFlow<FloatArray>

    /** Sample rate of [audioBlocks] (Hz). Same as the WAV file's sample rate. */
    val sampleRate: Int

    suspend fun start(target: File)
    suspend fun stop(): RecordingResult
    fun cancel()

    companion object {
        const val HISTORY_SIZE = 200
    }
}

interface AudioRecordSource {
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    suspend fun start()
    suspend fun read(buf: ShortArray, off: Int, len: Int): Int
    suspend fun stop()
}

interface Clock {
    fun nowMs(): Long
}

class SystemClock : Clock {
    override fun nowMs() = System.currentTimeMillis()
}

class DefaultRecorder(
    private val source: AudioRecordSource,
    private val clock: Clock = SystemClock(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val externalScope: CoroutineScope? = null,
    private val wavWriterFactory: (File) -> WavWriter = { file ->
        WavWriter(file, source.sampleRate, source.channels, source.bitsPerSample)
    },
) : Recorder {
    init {
        require(Recorder.HISTORY_SIZE > 0) { "HISTORY_SIZE must be positive" }
    }
    private val _rms = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rms

    private val _rmsHistory = MutableStateFlow(FloatArray(0))
    override val rmsHistory: StateFlow<FloatArray> = _rmsHistory

    // Ring buffer for RMS history — fixed allocation, no grow-copy per block.
    private val ringBuf = FloatArray(Recorder.HISTORY_SIZE)
    private var ringHead = 0 // next write position
    private var ringSize = 0 // current valid count, ≤ HISTORY_SIZE

    private val _audioBlocks = MutableSharedFlow<FloatArray>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val audioBlocks: SharedFlow<FloatArray> = _audioBlocks.asSharedFlow()

    override val sampleRate: Int get() = source.sampleRate

    private var writer: WavWriter? = null
    private var target: File? = null
    private var startMs = 0L
    private var job: Job? = null
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    override suspend fun start(target: File) {
        this.target = target
        writer = wavWriterFactory(target).also { it.open() }
        startMs = clock.nowMs()
        ringBuf.fill(0f)
        ringHead = 0
        ringSize = 0
        _rmsHistory.value = FloatArray(0)
        source.start()
        job = scope.launch { pump() }
    }

    private suspend fun pump() {
        val buf = ShortArray(BUFFER_FRAMES)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n <= 0) break
            writer?.writeShorts(buf, 0, n)
            // Emit float copy in parallel — separate array because the short
            // buffer is reused across iterations.
            val floatBlock = FloatArray(n) { i -> buf[i] / Short.MAX_VALUE.toFloat() }
            _audioBlocks.tryEmit(floatBlock)
            val rms = computeRms(buf, n)
            _rms.value = rms
            pushRms(rms)
        }
    }

    private fun pushRms(value: Float) {
        ringBuf[ringHead] = value
        ringHead = (ringHead + 1) % Recorder.HISTORY_SIZE
        if (ringSize < Recorder.HISTORY_SIZE) ringSize++

        // One allocation per push (StateFlow consumers expect a fresh array
        // for diffing). Previously we did two: copy-grow + the new array.
        val snapshot = FloatArray(ringSize)
        if (ringSize < Recorder.HISTORY_SIZE) {
            // Buffer not yet full: valid data is [0, ringSize) written in order
            System.arraycopy(ringBuf, 0, snapshot, 0, ringSize)
        } else {
            // Buffer full: unroll [head, end) then [0, head) to get oldest-first order
            val tail = Recorder.HISTORY_SIZE - ringHead
            System.arraycopy(ringBuf, ringHead, snapshot, 0, tail)
            System.arraycopy(ringBuf, 0, snapshot, tail, ringHead)
        }
        _rmsHistory.value = snapshot
    }

    private fun computeRms(buf: ShortArray, len: Int): Float {
        var acc = 0.0
        for (i in 0 until len) {
            val v = buf[i] / Short.MAX_VALUE.toFloat()
            acc += (v * v).toDouble()
        }
        return sqrt(acc / len).toFloat()
    }

    override suspend fun stop(): RecordingResult = withContext(ioDispatcher) {
        source.stop()
        job?.cancelAndJoin()
        job = null
        // Tolerate IOException from close (e.g. disk full during patchHeader).
        // The WAV payload is already on disk; only the header update may be stale.
        // We still return a RecordingResult so the draft is persisted and the user
        // doesn't lose the recording entirely. The downstream inference path can
        // fail gracefully on a stale header rather than us losing the file.
        runCatching { writer?.close() }
            .onFailure { Log.w(TAG, "writer.close() failed; WAV may have stale header", it) }
        writer = null
        val durationMs = clock.nowMs() - startMs
        RecordingResult(target!!.absolutePath, durationMs, source.sampleRate, source.channels)
    }

    override fun cancel() {
        job?.cancel()
        job = null
        runCatching { writer?.close() }
        writer = null
        target?.delete()
        target = null
    }

    companion object {
        const val BUFFER_FRAMES = 4096
        private const val TAG = "DefaultRecorder"
    }
}
