package com.sound2inat.app.recording

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sound2inat.app.MainActivity
import com.sound2inat.app.R
import com.sound2inat.app.Sound2iNatApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingNotificationBuilder @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun buildInitial(): Notification = buildNotification(contentText = "")

    fun build(state: RecordingSessionState.Recording): Notification =
        buildNotification(contentText = buildContentText(state))

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = PendingIntent.getService(
            ctx,
            0,
            Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, Sound2iNatApp.RECORDING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_recording)
            .setContentTitle(ctx.getString(R.string.notification_recording_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop_white, ctx.getString(R.string.notification_stop_action), stopIntent)
            .build()
    }

    companion object {
        fun buildContentText(state: RecordingSessionState.Recording): String {
            val elapsed = formatElapsed(state.elapsedMs)
            val species = state.lastDetection?.let { it.commonName ?: it.scientificName }
            return if (species != null) "$elapsed · $species" else elapsed
        }

        fun formatElapsed(ms: Long): String {
            val totalSeconds = ms / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
