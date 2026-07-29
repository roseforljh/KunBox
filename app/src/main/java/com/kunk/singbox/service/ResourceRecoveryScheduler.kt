package com.kunk.singbox.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock

internal fun recycleBackgroundProcess(context: Context, restartIntent: Intent) {
    val pendingIntent = PendingIntent.getService(
        context,
        RESOURCE_RECOVERY_REQUEST_CODE,
        restartIntent.setPackage(context.packageName),
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    alarmManager.setAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + RESOURCE_RECOVERY_RESTART_DELAY_MS,
        pendingIntent
    )
    Process.killProcess(Process.myPid())
}

private const val RESOURCE_RECOVERY_REQUEST_CODE = 0x4B46
private const val RESOURCE_RECOVERY_RESTART_DELAY_MS = 1_000L
