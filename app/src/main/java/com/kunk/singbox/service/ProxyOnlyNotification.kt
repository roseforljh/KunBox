package com.kunk.singbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kunk.singbox.MainActivity
import com.kunk.singbox.R

internal fun ProxyOnlyService.createProxyOnlyNotificationChannel(
    channelId: String,
    legacyChannelId: String,
    logTag: String
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val manager = getSystemService(NotificationManager::class.java)
    try {
        manager.deleteNotificationChannel(legacyChannelId)
    } catch (e: Exception) {
        Log.w(logTag, "Failed to delete legacy notification channel", e)
    }

    val channel = NotificationChannel(
        channelId,
        "SingBox Proxy",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        setShowBadge(false)
        enableVibration(false)
        enableLights(false)
        setSound(null, null)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
}

internal fun ProxyOnlyService.createProxyOnlyNotification(channelId: String): Notification {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, channelId)
            .setContentTitle("KunBox")
            .setContentText("Proxy-only running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(this)
            .setContentTitle("KunBox")
            .setContentText("Proxy-only running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
