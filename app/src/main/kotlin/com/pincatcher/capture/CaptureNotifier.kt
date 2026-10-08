package com.pincatcher.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pincatcher.MainActivity
import com.pincatcher.R

/**
 * The capture notification.
 *
 * Foreground service and user-visible status are the same notification, so there
 * is exactly one thing on screen saying capture is running. Collapsing it into a
 * silent one would let the tunnel be invisible, which is not a state a tool that
 * reads other apps' traffic should be able to reach.
 */
class CaptureNotifier(private val context: Context) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Capture",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while PinCatcher is capturing traffic."
                setShowBadge(false)
            },
        )
    }

    fun build(state: State): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture_notification)
            .setContentTitle("Capturing")
            .setContentText(state.summary())
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun update(state: State) {
        manager.notify(NOTIFICATION_ID, build(state))
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    /** What the tunnel is doing, in words that mean something on a lock screen. */
    data class State(
        val packetsRead: Long,
        val rejected: Long,
        val targetPackages: List<String>,
    ) {
        fun summary(): String {
            val scope = when {
                targetPackages.isEmpty() -> "all apps"
                targetPackages.size == 1 -> targetPackages.single()
                else -> "${targetPackages.size} apps"
            }
            val rejectedNote = if (rejected > 0) ", $rejected QUIC blocked" else ""
            return "$scope · $packetsRead packets$rejectedNote"
        }
    }

    companion object {
        const val CHANNEL_ID = "capture"
        const val NOTIFICATION_ID = 1001
    }
}