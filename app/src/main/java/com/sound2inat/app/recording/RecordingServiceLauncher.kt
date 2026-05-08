package com.sound2inat.app.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import javax.inject.Inject

interface RecordingServiceLauncher {
    fun start(context: Context)
    fun stop(context: Context)
    fun cancel(context: Context)
}

class DefaultRecordingServiceLauncher @Inject constructor() : RecordingServiceLauncher {
    override fun start(ctx: Context) {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_START),
        )
    }

    // startService delivers the action to onStartCommand; the service calls stopSelf() internally
    override fun stop(ctx: Context) {
        ctx.startService(
            Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
    }

    override fun cancel(ctx: Context) {
        ctx.startService(
            Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_CANCEL),
        )
    }
}
