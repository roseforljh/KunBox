package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.CommandServer

object BoxWrapperManager {
    private const val TAG = "BoxWrapperManager"

    @Volatile
    private var commandServer: CommandServer? = null

    fun init(server: CommandServer): Boolean {
        return try {
            commandServer = server
            Log.i(TAG, "BoxWrapperManager initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init BoxWrapperManager", e)
            commandServer = null
            false
        }
    }

    fun release() {
        commandServer = null
        Log.i(TAG, "BoxWrapperManager released")
    }

    fun isAvailable(): Boolean {
        return commandServer != null
    }

    fun getSelectedOutbound(): String? {
        if (!isAvailable()) return null
        return SelectorManager.getSelectedOutbound()
    }

    fun pause(): Boolean {
        val server = commandServer ?: return false
        return try {
            server.pause()
            Log.i(TAG, "pause() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause() failed: ${e.message}")
            false
        }
    }

    fun wake(): Boolean {
        val server = commandServer ?: return false
        return try {
            server.wake()
            Log.i(TAG, "wake() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "wake() failed: ${e.message}")
            false
        }
    }

    fun resetNetwork(): Boolean {
        val server = commandServer ?: return false
        return try {
            server.resetNetwork()
            Log.i(TAG, "resetNetwork() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetNetwork() failed: ${e.message}")
            false
        }
    }
}
