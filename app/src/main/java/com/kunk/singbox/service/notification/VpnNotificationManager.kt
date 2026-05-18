package com.kunk.singbox.service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.MainActivity
import com.kunk.singbox.R
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.SingBoxService.Companion.ACTION_STOP
import com.kunk.singbox.service.SingBoxService.Companion.ACTION_SWITCH_NODE
import com.kunk.singbox.service.SingBoxService.Companion.ACTION_RESET_CONNECTIONS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 */
class VpnNotificationManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "VpnNotificationManager"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "singbox_vpn_service_silent"
        private const val LEGACY_CHANNEL_ID = "singbox_vpn_service"
        private const val UPDATE_DEBOUNCE_MS = 3000L
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    private val lastUpdateAtMs = AtomicLong(0L)
    private val hasForegroundStarted = AtomicBoolean(false)

    @Volatile
    private var updateJob: Job? = null

    @Volatile
    private var suppressUpdates = false

    @Volatile
    private var lastTextLogged: String? = null

    /**
     */
    data class NotificationState(
        val isRunning: Boolean = false,
        val isStopping: Boolean = false,
        val activeNodeName: String? = null,
        val showSpeed: Boolean = true,
        val uploadSpeed: Long = 0L,
        val downloadSpeed: Long = 0L
    )

    /**
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            runCatching { notificationManager.deleteNotificationChannel("singbox_vpn") }
            runCatching { notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }

            val channel = NotificationChannel(
                CHANNEL_ID,
                "KunBox VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN Service Notification"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     */
    fun updateNotification(state: NotificationState, service: SingBoxService) {
        val notification = createNotification(state)

        val text = runCatching {
            notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        }.getOrNull()

        if (!text.isNullOrBlank() && text != lastTextLogged) {
            lastTextLogged = text
            Log.i(TAG, "Notification content: $text")
        }

        if (!hasForegroundStarted.get()) {
            runCatching {
                service.startForeground(NOTIFICATION_ID, notification)
                hasForegroundStarted.set(true)
            }.onFailure { e ->
                Log.w(TAG, "Failed to call startForeground, fallback to notify()", e)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            runCatching {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }.onFailure { e ->
                Log.w(TAG, "Failed to update notification via notify()", e)
            }
        }
    }

    /**
     */
    fun requestNotificationUpdate(
        state: NotificationState,
        service: SingBoxService,
        force: Boolean = false
    ) {
        if (suppressUpdates) return
        if (state.isStopping) return

        val now = SystemClock.elapsedRealtime()
        val last = lastUpdateAtMs.get()

        if (force) {
            lastUpdateAtMs.set(now)
            updateJob?.cancel()
            updateJob = null
            updateNotification(state, service)
            return
        }

        val delayMs = (UPDATE_DEBOUNCE_MS - (now - last)).coerceAtLeast(0L)
        if (delayMs <= 0L) {
            lastUpdateAtMs.set(now)
            updateJob?.cancel()
            updateJob = null
            updateNotification(state, service)
            return
        }

        if (updateJob?.isActive == true) return
        updateJob = serviceScope.launch {
            delay(delayMs)
            lastUpdateAtMs.set(SystemClock.elapsedRealtime())
            updateNotification(state, service)
        }
    }

    /**
     */
    fun createNotification(state: NotificationState): Notification {

        if (state.isStopping) {
            return buildNotificationBuilder()
                .setContentTitle("KunBox VPN")
                .setContentText(context.getString(R.string.connection_disconnecting))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build()
        }

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val switchIntent = Intent(context, SingBoxService::class.java).apply {
            action = ACTION_SWITCH_NODE
        }
        val switchPendingIntent = PendingIntent.getService(
            context, 1, switchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, SingBoxService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val resetIntent = Intent(context, SingBoxService::class.java).apply {
            action = ACTION_RESET_CONNECTIONS
        }
        val resetPendingIntent = PendingIntent.getService(
            context, 3, resetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nodeName = state.activeNodeName ?: context.getString(R.string.connection_connected)

        val contentText = if (state.showSpeed) {
            val uploadStr = formatSpeed(state.uploadSpeed)
            val downloadStr = formatSpeed(state.downloadSpeed)
            context.getString(R.string.notification_speed_format, uploadStr, downloadStr)
        } else {
            context.getString(R.string.connection_connected)
        }

        return buildNotificationBuilder()
            .setContentTitle("KunBox VPN - $nodeName")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    context.getString(R.string.notification_switch_node),
                    switchPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_rotate,
                    context.getString(R.string.notification_reset_connections),
                    resetPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.notification_disconnect),
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    /**
     */
    fun createStartingNotification(message: String): Notification {
        return buildNotificationBuilder()
            .setContentTitle("KunBox VPN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    /**
     */
    fun showTemporaryNotification(id: Int, notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID + id, notification)
    }

    /**
     */
    fun cancelNotification(id: Int = NOTIFICATION_ID) {
        notificationManager.cancel(id)
    }

    /**
     */
    fun setSuppressUpdates(suppress: Boolean) {
        suppressUpdates = suppress
    }

    /**
     */
    fun resetState() {
        updateJob?.cancel()
        updateJob = null
        hasForegroundStarted.set(false)
        suppressUpdates = false
        lastTextLogged = null
    }

    fun hasForegroundStarted(): Boolean = hasForegroundStarted.get()

    /**
     */
    fun markForegroundStarted() {
        hasForegroundStarted.set(true)
    }

    private fun buildNotificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return android.text.format.Formatter.formatFileSize(context, bytesPerSecond) + "/s"
    }
}
