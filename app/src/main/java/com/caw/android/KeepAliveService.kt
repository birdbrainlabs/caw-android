package com.caw.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps Caw's process (and the speech model loaded in it) alive while other apps are in front.
 * Without it Samsung's memory manager kills the hidden process and the next hold waits for the
 * model to load again. It does nothing but stay in the foreground with a quiet "Caw is ready"
 * notification. Started by [CawInputMethodService] on its first show
 * and by [DictationActivity] when it starts.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "keep-alive: could not go to the foreground, will retry on the next show", t)
            started = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "CawSpeech"
        private const val CHANNEL_ID = "caw_ready"
        private const val NOTIFICATION_ID = 1

        @Volatile private var started = false

        /**
         * Starts the service once per process. Any failure (e.g. ForegroundServiceStartNotAllowedException)
         * is logged and the next call tries again.
         */
        fun startOnce(context: Context) {
            if (started) return
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
                started = true
                Log.i(TAG, "keep-alive: service started")
            } catch (t: Throwable) {
                Log.w(TAG, "keep-alive: could not start the service, will retry on the next show", t)
            }
        }

        private fun buildNotification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Caw is ready", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Caw is ready")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
