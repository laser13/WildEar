package com.sound2inat.app.recording

import android.app.Service
import android.content.Intent
import android.os.IBinder

// Full implementation added in Task 4
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_START = "com.sound2inat.app.recording.START"
        const val ACTION_STOP = "com.sound2inat.app.recording.STOP"
        const val ACTION_CANCEL = "com.sound2inat.app.recording.CANCEL"
        const val NOTIF_ID = 1001
    }
}
