package net.nuclearlighters.ventriloquist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * The long-running part of Ventriloquist. In this skeleton it only holds a foreground
 * notification; capture, sending and the latency module arrive in later milestones.
 */
class VentriloquistService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "start requested (idle skeleton)")
                startIdleForeground()
                running = true
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun startIdleForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_idle))
            .setOngoing(true)
            .build()
        // The media-projection type comes with the capture milestone; without a projection the
        // plain form is the correct one.
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val TAG = "Ventriloquist"
        const val ACTION_START = "net.nuclearlighters.ventriloquist.action.START"
        const val ACTION_STOP = "net.nuclearlighters.ventriloquist.action.STOP"
        const val CHANNEL_ID = "ventriloquist"
        const val NOTIFICATION_ID = 1

        @Volatile
        var running: Boolean = false
            private set
    }
}
