package com.sound2inat.app.recording

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var controller: RecordingController

    @Inject lateinit var notificationBuilder: RecordingNotificationBuilder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerJob: Job? = null
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Service was restarted by the system after process death.
            // Recording state is gone (RecordingController is a singleton in a fresh
            // process, starts in Idle). Don't create a zombie foreground notification.
            Log.w(TAG, "onStartCommand: null intent (process-death restart), stopping startId=$startId")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_CANCEL -> handleCancel()
            else -> {
                Log.w(TAG, "onStartCommand: unknown action=${intent.action}, stopping startId=$startId")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        startForeground(NOTIF_ID, notificationBuilder.buildInitial())
        serviceScope.launch { controller.start() }
        observeRecordingState()
    }

    private fun handleStop() {
        observerJob?.cancel()
        serviceScope.launch {
            controller.stop()
            stopForegroundAndSelf()
        }
    }

    private fun handleCancel() {
        observerJob?.cancel()
        controller.cancel()
        stopForegroundAndSelf()
    }

    @OptIn(FlowPreview::class)
    private fun observeRecordingState() {
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            controller.state
                .sample(NOTIFICATION_UPDATE_MS)
                .takeWhile { it is RecordingSessionState.Recording }
                .collect { state ->
                    val recording = state as RecordingSessionState.Recording
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(NOTIF_ID, notificationBuilder.build(recording))
                }
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        if (stopped.compareAndSet(false, true)) {
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "com.sound2inat.app.recording.START"
        const val ACTION_STOP = "com.sound2inat.app.recording.STOP"
        const val ACTION_CANCEL = "com.sound2inat.app.recording.CANCEL"
        const val NOTIF_ID = 1001
        private const val NOTIFICATION_UPDATE_MS = 1_000L
        private const val TAG = "RecordingService"
    }
}
