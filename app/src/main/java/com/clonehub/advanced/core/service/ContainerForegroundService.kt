package com.clonehub.advanced.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clonehub.advanced.R
import com.clonehub.advanced.ui.home.HomeActivity

/**
 * 100% Android 14 (API 34) & Android 15 (Target SDK 35) Compliant Foreground Service.
 * Fixes the fatal MissingForegroundServiceTypeException and SecurityException.
 */
class ContainerForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "clonehub_channel_service"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_APP_NAME = "extra_app_name"
        private const val EXTRA_SPACE_ID = "extra_space_id"

        fun start(context: Context, appName: String, spaceId: Int) {
            val intent = Intent(context, ContainerForegroundService::class.java).apply {
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_SPACE_ID, spaceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContainerForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appName = intent?.getStringExtra(EXTRA_APP_NAME) ?: "Container"
        val spaceId = intent?.getIntExtra(EXTRA_SPACE_ID, 1) ?: 1

        val notification = buildNotification(appName, spaceId)

        // Strict Android 14+ / 15 compliance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ (Android 14 & 15)
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clone Hub Active Space",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps virtual container active and responsive"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(appName: String, spaceId: Int): Notification {
        val openIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clone Hub (Space $spaceId)")
            .setContentText("$appName is running securely in isolated space")
            .setSmallIcon(R.drawable.ic_action_install)
            .setColor(0xFF00C3CC.toInt())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
