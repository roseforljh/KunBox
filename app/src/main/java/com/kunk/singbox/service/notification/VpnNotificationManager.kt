package com.kunk.singbox.service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.kunk.singbox.service.manager.VpnStopInitiator
import com.kunk.singbox.ipc.DataPlaneStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class NotificationActionConfig(
    val serviceClass: Class<out Service>,
    val switchNodeAction: String,
    val resetConnectionsAction: String,
    val stopAction: String
)

class VpnNotificationManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val notificationId: Int = NOTIFICATION_ID,
    private val channelId: String = CHANNEL_ID,
    private val channelName: String = "KunBox VPN",
    private val actions: NotificationActionConfig = NotificationActionConfig(
        serviceClass = SingBoxService::class.java,
        switchNodeAction = ACTION_SWITCH_NODE,
        resetConnectionsAction = ACTION_RESET_CONNECTIONS,
        stopAction = ACTION_STOP
    )
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

    private val updateLock = Any()
    private var pendingUpdate: PendingUpdate? = null

    @Volatile
    private var suppressUpdates = false

    @Volatile
    private var lastTextLogged: String? = null

    data class NotificationState(
        val isRunning: Boolean = false,
        val isStopping: Boolean = false,
        val activeNodeName: String? = null,
        val showSpeed: Boolean = true,
        val uploadSpeed: Long = 0L,
        val downloadSpeed: Long = 0L,
        val dataPlaneStatus: DataPlaneStatus = DataPlaneStatus.STOPPED
    )

    private data class PendingUpdate(
        val state: NotificationState,
        val service: Service
    )

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            if (channelId == CHANNEL_ID) {
                runCatching { notificationManager.deleteNotificationChannel("singbox_vpn") }
                runCatching { notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            }

            val channel = NotificationChannel(
                channelId,
                channelName,
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

    fun updateNotification(state: NotificationState, service: Service) {
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
                service.startForeground(notificationId, notification)
                hasForegroundStarted.set(true)
            }.onFailure { e ->
                Log.w(TAG, "Failed to call startForeground, fallback to notify()", e)
                notificationManager.notify(notificationId, notification)
            }
        } else {
            runCatching {
                notificationManager.notify(notificationId, notification)
            }.onFailure { e ->
                Log.w(TAG, "Failed to update notification via notify()", e)
            }
        }
    }

    fun requestNotificationUpdate(
        state: NotificationState,
        service: Service,
        force: Boolean = false
    ) {
        if (suppressUpdates) return
        if (state.isStopping) return

        synchronized(updateLock) {
            val now = SystemClock.elapsedRealtime()
            val last = lastUpdateAtMs.get()

            if (force) {
                lastUpdateAtMs.set(now)
                pendingUpdate = null
                updateJob?.cancel()
                updateJob = null
                updateNotification(state, service)
                return
            }

            val delayMs = (UPDATE_DEBOUNCE_MS - (now - last)).coerceAtLeast(0L)
            if (delayMs <= 0L) {
                lastUpdateAtMs.set(now)
                pendingUpdate = null
                updateJob?.cancel()
                updateJob = null
                updateNotification(state, service)
                return
            }

            pendingUpdate = PendingUpdate(state, service)
            if (updateJob?.isActive == true) return
            updateJob = serviceScope.launch {
                delay(delayMs)
                synchronized(updateLock) {
                    val latest = pendingUpdate.also { pendingUpdate = null }
                    updateJob = null
                    if (latest != null && !suppressUpdates && !latest.state.isStopping) {
                        lastUpdateAtMs.set(SystemClock.elapsedRealtime())
                        updateNotification(latest.state, latest.service)
                    }
                }
            }
        }
    }

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

        val switchIntent = Intent(context, actions.serviceClass).apply {
            action = actions.switchNodeAction
        }
        val switchPendingIntent = PendingIntent.getService(
            context, 1, switchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, actions.serviceClass).apply {
            action = actions.stopAction
            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, VpnStopInitiator.NOTIFICATION.wireValue)
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val resetIntent = Intent(context, actions.serviceClass).apply {
            action = actions.resetConnectionsAction
        }
        val resetPendingIntent = PendingIntent.getService(
            context, 3, resetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nodeName = state.activeNodeName ?: context.getString(
            if (state.dataPlaneStatus == DataPlaneStatus.READY) R.string.connection_connected else R.string.app_name
        )

        val safetyText = when (state.dataPlaneStatus) {
            DataPlaneStatus.STOPPED,
            DataPlaneStatus.STARTING -> context.getString(R.string.connection_connecting)
            DataPlaneStatus.BLOCKING -> context.getString(R.string.vpn_status_blocking)
            DataPlaneStatus.RECOVERING -> context.getString(R.string.vpn_status_recovering)
            DataPlaneStatus.FAILED_BLOCKED -> context.getString(R.string.vpn_status_failed_blocked)
            DataPlaneStatus.FAILED_UNPROTECTED -> context.getString(R.string.vpn_status_failed_unprotected)
            else -> null
        }
        val contentText = safetyText ?: if (state.showSpeed) {
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
            .apply {
                if (state.dataPlaneStatus == DataPlaneStatus.READY) {
                    addAction(
                        Notification.Action.Builder(
                            android.R.drawable.ic_menu_revert,
                            context.getString(R.string.notification_switch_node),
                            switchPendingIntent
                        ).build()
                    )
                }
            }
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

    fun createStartingNotification(message: String): Notification {
        return buildNotificationBuilder()
            .setContentTitle("KunBox VPN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    fun showTemporaryNotification(id: Int, notification: Notification) {
        notificationManager.notify(notificationId + id, notification)
    }

    fun cancelNotification(id: Int = notificationId) {
        notificationManager.cancel(id)
    }

    fun setSuppressUpdates(suppress: Boolean) {
        suppressUpdates = suppress
        if (suppress) {
            synchronized(updateLock) {
                pendingUpdate = null
                updateJob?.cancel()
                updateJob = null
            }
        }
    }

    fun resetState() {
        synchronized(updateLock) {
            pendingUpdate = null
            updateJob?.cancel()
            updateJob = null
        }
        hasForegroundStarted.set(false)
        suppressUpdates = false
        lastTextLogged = null
    }

    fun hasForegroundStarted(): Boolean = hasForegroundStarted.get()

    fun markForegroundStarted() {
        hasForegroundStarted.set(true)
    }

    private fun buildNotificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return android.text.format.Formatter.formatFileSize(context, bytesPerSecond) + "/s"
    }
}
