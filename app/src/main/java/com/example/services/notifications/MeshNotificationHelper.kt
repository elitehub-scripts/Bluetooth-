package com.example.services.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.bluetooth.BluetoothCapabilities
import com.example.services.bluetooth.BluetoothMeshForegroundService

object MeshNotificationHelper {
    const val CHANNEL_SERVICE = "bgs_relay_service"
    const val CHANNEL_MESSAGES = "bgs_offline_messages"
    const val SERVICE_NOTIFICATION_ID = 1001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.service_notification_channel_desc)
                setShowBadge(false)
            }
            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.message_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.message_notification_channel_desc)
            }
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(msgChannel)
        }
    }

    fun buildServiceNotification(
        context: Context,
        connectedPeers: Int,
        queuedRelays: Int,
        isScanning: Boolean
    ): Notification {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, BluetoothMeshForegroundService::class.java).apply {
                action = BluetoothMeshForegroundService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val scanLabel = if (isScanning) "Scanning" else "Listening"
        val contentText = "$scanLabel • $connectedPeers peer(s) • $queuedRelays relay packet(s)"

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bluetooth Global Share Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop Mesh", stopIntent)
            .build()
    }

    fun showIncomingMessageNotification(context: Context, senderName: String, preview: String) {
        if (!BluetoothCapabilities.canPostNotifications(context)) return
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            senderName.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Offline message from $senderName")
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(senderName.hashCode() and 0x7FFF, notification)
    }

    fun showFileReceivedNotification(context: Context, fileName: String) {
        if (!BluetoothCapabilities.canPostNotifications(context)) return
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            fileName.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Verified offline file received")
            .setContentText("$fileName (SHA-256 verified)")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((fileName.hashCode() and 0x7FFF) + 5000, notification)
    }
}
