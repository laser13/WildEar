package com.sound2inat.inference

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streaming live inference: buffers float audio blocks, slices into
 * [windowSamples]-long windows hopped by [hopSamples], optionally applies
 * HPF + [SpectralSubtractor] when [usePreprocessing] is true, then
 * [YamNetGate] + [model] per window. Emits raw [WindowPrediction] with no
 * confidence threshold filtering — downstream `DetectionAggregator` handles
 * thresholds.
 *
 * **Default is raw-first**: [usePreprocessing] defaults to `false` so the
 * model receives unprocessed (normalized-only) samples. Set to `true` for the
 * experimental benchmark path that applies high-pass + spectral subtraction.
 *
 * Backpressure: a [Channel] with capacity [queueCapacity] and DROP_OLDEST
 * overflow buffers windows for the worker. With this configuration `trySend`
 * always succeeds for an open channel — when the buffer is full, the channel
 * silently evicts the oldest queued window (so consumer-side drops surface as
 * holes in the prediction stream rather than as `trySend` failures).
 * [backlog] reports the approximate queue depth (consumers can show a UI hint).
 * The only path on which `trySend` can return failure is a closed channel
 * (post-[stop]); if that ever fires, [droppedOnFull] is incremented to keep
 * the producer/consumer accounting consistent. In `assertions enabled` builds
 * (e.g. unit tests), this also trips a Kotlin `assert` to surface architectural
 * drift if the channel configuration is ever changed away from
 * `BUFFERED + DROP_OLDEST`.
 *
 * Lifecycle: [start] launches the worker on the supplied scope. [feed]
 * appends audio blocks (non-blocking). [stop] closes the queue, waits up to
 * `DRAIN_TIMEOUT_MS` for the worker to drain pending windows, then cancels.
 *
 * NOT thread-safe wrt [feed] — call from a single producer (the recorder pump).
 */
@Suppress("VariableNaming") // backing property pattern: _predictions / _backlog
open class LiveInferenceEngine(
    private val model: BioacousticModel,
    private val yamNetGate: YamNetGate?,
    private val spectralSubtractor: SpectralSubtractor?,
    private val sampleRateHz: Int = 48_000,
    private val windowSamples: Int = sampleRateHz * 3,
    private val hopSamples: Int = sampleRateHz * 3 / 2,
    /**
     * When true, apply high-pass filter + spectral subtraction before
     * passing audio to the gate and model. Defaults to false (raw-first).
     */
    val usePreprocessing: Boolean = false,
    private val queueCapacity: Int = 8,
) {
    protected val _predictions = MutableSharedFlow<WindowPrediction>(extraBufferCapacity = 64)
    open val predictions: SharedFlow<WindowPrediction> = _predictions.asSharedFlow()

    private val _backlog = MutableStateFlow(0)

    /**
     * Approximate queue depth — incremented on each accepted window, decremented
     * when the worker pulls one. NOT a precise count: when DROP_OLDEST fires this
     * value temporarily over-reports because the dropped element is no longer in
     * the channel but was already counted. Treat as an upper-bound estimate
     * suitable for "analysis catching up…" UI hints; don't use for precise math.
     */
    open val backlog: StateFlow<Int> = _backlog.asStateFlow()

    private val _droppedOnFull = MutableStateFlow(0)

    /**
     * Number of windows the engine produced but failed to enqueue because
     * [trySendWindow] reported failure. With the default
     * `BUFFERED + DROP_OLDEST` channel this counter should remain `0` for an
     * open channel — see class KDoc. It is incremented as a safety net so UI
     * accounting (`emitted == consumed + dropped`) holds even if the failure
     * branch ever fires (e.g. a closed channel race, or a future change to
     * the buffer-overflow policy).
     */
    open val droppedOnFull: StateFlow<Int> = _droppedOnFull.asStateFlow()

    private val queue = Channel<Window>(capacity = queueCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var workerJob: Job? = null

    // Ring large enough to hold one full window plus one full hop without overrun.
    private val ring = FloatArray(windowSamples + hopSamples)
    private var ringFilled = 0 // valid samples in [0, ringFilled)
    private var consumed = 0L // total samples that have left the head of the ring
    private var nextEmitAt = 0L // sample index of next window's start

    @Volatile private var stopped = false
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    open fun start(scope: CoroutineScope) {
        check(!started.getAndSet(true)) {
            "LiveInferenceEngine is single-use: cannot start after stop or after a prior start"
        }
        workerJob = scope.launch(Dispatchers.Default) { worker() }
    }

    /** Append a chunk of audio (float, normalised). Non-blocking. */
    open fun feed(block: FloatArray) {
        if (stopped) return
        var idx = 0
        while (idx < block.size) {
            if (stopped) return
            if (ringFilled == ring.size) {
                error(
                    "LiveInferenceEngine ring buffer overrun: ringFilled=$ringFilled " +
                        "(invariant: tryEmitWindows must drain after each insert)",
                )
            }
            val take = (block.size - idx).coerceAtMost(ring.size - ringFilled)
            System.arraycopy(block, idx, ring, ringFilled, take)
            ringFilled += take
            idx += take
            tryEmitWindows()
        }
    }

    private fun tryEmitWindows() {
        while (consumed + ringFilled >= nextEmitAt + windowSamples) {
            val startInRing = (nextEmitAt - consumed).toInt()
            require(startInRing >= 0 && startInRing + windowSamples <= ringFilled) {
                "window slice out of range: start=$startInRing fill=$ringFilled"
            }
            val window = FloatArray(windowSamples)
            System.arraycopy(ring, startInRing, window, 0, windowSamples)
            val startMs = nextEmitAt * 1_000L / sampleRateHz
            val endMs = (nextEmitAt + windowSamples) * 1_000L / sampleRateHz
            val sent = trySendWindow(Window(window, startMs, endMs))
            if (sent) {
                _backlog.update { (it + 1).coerceAtMost(queueCapacity) }
            } else {
                // Should be unreachable while channel is open: BUFFERED+DROP_OLDEST
                // makes trySend infallible. If it fires we either raced with stop()
                // (channel closed) or the channel policy was changed — track the
                // drop so UI accounting (emitted = consumed + droppedOnFull) holds.
                _droppedOnFull.update { it + 1 }
                onSendRejected()
            }
            nextEmitAt += hopSamples

            // Slide the ring so future audio can fit. Keep only samples >= nextEmitAt.
            val keepFromAbsolute = nextEmitAt
            val keepFromRing = (keepFromAbsolute - consumed).toInt().coerceAtLeast(0)
            val remaining = (ringFilled - keepFromRing).coerceAtLeast(0)
            if (keepFromRing > 0 && remaining > 0) {
                System.arraycopy(ring, keepFromRing, ring, 0, remaining)
            }
            consumed += keepFromRing
            ringFilled = remaining
        }
    }

    /**
     * Test seam: enqueue [window] for the worker. Returns `true` if the channel
     * accepted the window, `false` otherwise (closed channel or — under a
     * non-default overflow policy — buffer rejection). Override in tests to
     * exercise the failure path; production code must not override this.
     *
     * `internal` (not `protected`) on purpose: the only legitimate consumer is
     * a test-source subclass in the same module, and we don't want [Window] to
     * leak into a public inheritance contract for external subclasses.
     */
    internal open fun trySendWindow(window: Window): Boolean =
        queue.trySend(window).isSuccess

    /**
     * Invoked when [trySendWindow] reports failure on what should be a working
     * channel. Default implementation fires a Kotlin `assert` (no-op when JVM
     * assertions are disabled, e.g. release builds; throws under `-ea` in tests
     * and developer builds) when the engine is not [stopped] — surfacing
     * architectural drift if the `BUFFERED + DROP_OLDEST` channel config is
     * ever changed away from a configuration where `trySend` is infallible on
     * an open queue. A closed channel (post-[stop]) is an expected race and
     * does not trip the assertion. Tests override to silence the assertion
     * while exercising the failure-accounting path.
     */
    protected open fun onSendRejected() {
        assert(stopped) {
            "BUFFERED+DROP_OLDEST channel rejected trySend on an open queue — " +
                "channel configuration drift?"
        }
    }

    private suspend fun worker() {
        for (window in queue) {
            _backlog.update { (it - 1).coerceAtLeast(0) }
            if (_backlog.value > BACKLOG_WARN) {
                Log.w(TAG, "Inference behind real time: backlog=${_backlog.value}")
            }
            runWindow(window)
        }
    }

    private suspend fun runWindow(w: Window) {
        var samples = w.samples
        if (usePreprocessing) {
            samples = highPassFilter(samples, sampleRateHz)
            spectralSubtractor?.let { samples = it.process(samples) }
        }
        val gateResult = yamNetGate?.classify(samples, sampleRateHz) // null = fail-open → PASS
        val preds = model.predict(
            pcmFloat32 = samples,
            sampleRateHz = sampleRateHz,
            latitude = null,
            longitude = null,
            observedAtMillis = 0L,
            windowStartMs = w.startMs,
            windowEndMs = w.endMs,
        )
        // Gate is soft: DOWNRANK only suppresses when no species has high confidence.
        // null (fail-open) or PASS always emits; DOWNRANK is overridden when at least
        // one prediction has confidence >= HIGH_CONFIDENCE_OVERRIDE.
        if (gateResult?.recommendation == GateRecommendation.DOWNRANK) {
            if (preds.none { it.confidence >= HIGH_CONFIDENCE_OVERRIDE }) return
        }
        for (p in preds) _predictions.tryEmit(p)
    }

    /** Stops accepting new windows, drains queue (≤ [DRAIN_TIMEOUT_MS]), cancels worker. */
    open suspend fun stop() {
        if (stopped) return
        stopped = true
        queue.close()
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) { workerJob?.join() }
        workerJob?.cancel()
        workerJob = null
        runCatching { model.close() }
            .onFailure { Log.w(TAG, "model.close() threw in stop()", it) }
    }

    internal data class Window(val samples: FloatArray, val startMs: Long, val endMs: Long)

    companion object {
        private const val TAG = "LiveInferenceEngine"
        private const val BACKLOG_WARN = 3
        private const val DRAIN_TIMEOUT_MS = 5_000L

        /** If any prediction has confidence >= this, override a DOWNRANK gate decision. */
        private const val HIGH_CONFIDENCE_OVERRIDE = 0.7f
    }
}

/**
 * Factory for creating [LiveInferenceEngine] instances bound to a recording's
 * sample rate. Hilt provides this so the VM doesn't need direct access to all
 * dependencies (BioacousticModels, YamNetGate). Test bindings can return null
 * to fall back to the offline inference pipeline.
 *
 * `create` is suspend and may return null when the BirdNET model is not
 * installed (or fails to load). Callers must fall back to the offline path
 * in that case rather than treating null as a hard error — the user simply
 * hasn't installed the model yet.
 */
fun interface LiveInferenceEngineFactory {
    suspend fun create(sampleRateHz: Int): LiveInferenceEngine?
}
