package com.sound2inat.app.recording

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_CANCEL -> handleCancel()
        }
        return START_STICKY
    }

    private fun handleStart() {
        startForeground(NOTIF_ID, notificationBuilder.buildInitial())
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.start() }
        observeRecordingState()
    }

    private fun handleStop() {
        observerJob?.cancel()
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
    }
}
