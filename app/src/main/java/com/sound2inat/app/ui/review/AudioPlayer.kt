package com.sound2inat.app.ui.review

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Test-friendly audio player abstraction. The Android implementation wraps
 * [MediaPlayer]; tests substitute a fake without pulling in [MediaPlayer]
 * (which lives in `android.jar` and is mocked out in unit tests).
 */
interface AudioPlayer {
    /** Latest known playback position in ms; 0 when idle. */
    val position: StateFlow<Long>

    /** Whether the underlying source is currently producing audio. */
    val isPlaying: StateFlow<Boolean>

    /** Total duration of the loaded source, or 0 if not yet loaded. */
    val durationMs: StateFlow<Long>

    /** Last fatal playback error message, if any (cleared on next [start]). */
    val lastError: StateFlow<String?>

    /** Loads [path] (if not already loaded) and begins playback from the current position. */
    fun start(path: String)

    /** Pauses playback, retaining the current position. */
    fun pause()

    /** Seeks to [ms] (clamped by impl). */
    fun seekTo(ms: Long)

    /** Releases any resources. After release, the player must not be reused. */
    fun release()
}

/**
 * MediaPlayer-backed [AudioPlayer]. Owns its own progress-tick coroutine on
 * the supplied [scope]; the scope is cancelled in [release] so the tick stops.
 *
 * Construct one per Review screen instance.
 */
class MediaPlayerAudioPlayer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AudioPlayer {

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    private var player: MediaPlayer? = null
    private var loadedPath: String? = null
    private var tickJob: Job? = null

    @Suppress("TooGenericExceptionCaught")
    override fun start(path: String) {
        try {
            if (loadedPath != path) {
                player?.release()
                _position.value = 0L
                val mp = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener {
                        _isPlaying.value = false
                        _position.value = duration.toLong()
                    }
                    setOnErrorListener { _, what, extra ->
                        _lastError.value = "MediaPlayer error $what/$extra"
                        _isPlaying.value = false
                        true
                    }
                }
                player = mp
                loadedPath = path
                _durationMs.value = mp.duration.toLong()
                _lastError.value = null
            }
            player?.start()
            _isPlaying.value = true
            startTicking()
        } catch (t: Throwable) {
            _lastError.value = t.message ?: t::class.simpleName.orEmpty()
            _isPlaying.value = false
        }
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        _isPlaying.value = false
        stopTicking()
        player?.let { _position.value = it.currentPosition.toLong() }
    }

    override fun seekTo(ms: Long) {
        val mp = player ?: return
        val clamped = ms.coerceIn(0L, mp.duration.toLong())
        mp.seekTo(clamped.toInt())
        _position.value = clamped
    }

    override fun release() {
        stopTicking()
        player?.release()
        player = null
        loadedPath = null
        _isPlaying.value = false
        scope.cancel()
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (player?.isPlaying == true) {
                _position.value = player?.currentPosition?.toLong() ?: 0L
                delay(TICK_INTERVAL_MS)
            }
            _isPlaying.value = player?.isPlaying == true
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    private companion object {
        const val TICK_INTERVAL_MS = 50L
    }
}
