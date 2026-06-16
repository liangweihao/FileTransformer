package com.nothing.filetransformer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nothing.filetransformer.MainActivity
import com.nothing.filetransformer.R

/**
 * Creates and manages the notification channel and builds foreground-service notifications.
 */
object NotificationHelper {

    const val CHANNEL_ID = "filetransfer_channel"
    const val NOTIFICATION_ID = 1001

    /**
     * Creates the notification channel. Safe to call on every app start.
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the file transfer server is running"
                setShowBadge(false)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the foreground-service notification.
     */
    fun buildNotification(context: Context, ipAddress: String, port: Int): android.app.Notification {
        val openIntent = Intent(context, MainActivity::class.java).let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val stopIntent = Intent(context, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("File Transfer Active")
            .setContentText("http://$ipAddress:$port")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
