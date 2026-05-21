package com.kunk.singbox.utils

import android.util.Log
import com.kunk.singbox.repository.LogRepository

/**
 *
 *
 * ```
 * L.connection("HotSwitch", "Starting hot switch to node: $nodeTag")
 * L.vpn("Lifecycle", "VPN service started")
 * L.error("Health", "Health check failed", exception)
 * ```
 */
object L {

    /**
     */
    enum class Category(val prefix: String, val emoji: String) {
        CONNECTION("CONN", "\uD83D\uDD17"),
        VPN("VPN", "\uD83D\uDEE1\uFE0F"),
        NETWORK("NET", "\uD83C\uDF10"),
        CONFIG("CFG", "\u2699\uFE0F"),
        ERROR("ERR", "\u274C"),
        DEBUG("DBG", "\uD83D\uDC1B"),
        INFO("INFO", "\u2139\uFE0F")
    }

    /**
     */
    @Volatile
    var minCategoryLevel: Int = Log.INFO

    /**
     */
    @Volatile
    var showEmoji: Boolean = true

    /**
     */
    @Volatile
    var logcatEnabled: Boolean = true

    /**
     */
    @Volatile
    var uiLogEnabled: Boolean = true

    /**
     */
    fun connection(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.CONNECTION, tag, message, level)
    }

    /**
     */
    fun vpn(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.VPN, tag, message, level)
    }

    /**
     */
    fun config(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.CONFIG, tag, message, level)
    }

    /**
     */
    fun network(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.NETWORK, tag, message, level)
    }

    /**
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(Category.ERROR, tag, message, Log.ERROR, throwable)
    }

    /**
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        log(Category.ERROR, tag, message, Log.WARN, throwable)
    }

    /**
     */
    fun debug(tag: String, message: String) {
        log(Category.DEBUG, tag, message, Log.DEBUG)
    }

    /**
     */
    fun info(tag: String, message: String) {
        log(Category.INFO, tag, message, Log.INFO)
    }

    private fun log(
        category: Category,
        tag: String,
        message: String,
        level: Int,
        throwable: Throwable? = null
    ) {
        val fullTag = "${category.prefix}/$tag"

        if (logcatEnabled) {
            when (level) {
                Log.VERBOSE -> Log.v(fullTag, message, throwable)
                Log.DEBUG -> Log.d(fullTag, message, throwable)
                Log.INFO -> Log.i(fullTag, message, throwable)
                Log.WARN -> Log.w(fullTag, message, throwable)
                Log.ERROR -> Log.e(fullTag, message, throwable)
            }
        }

        if (uiLogEnabled && level >= minCategoryLevel) {
            val emoji = if (showEmoji) "${category.emoji} " else ""
            val levelStr = when (level) {
                Log.ERROR -> "E"
                Log.WARN -> "W"
                Log.INFO -> "I"
                Log.DEBUG -> "D"
                else -> "V"
            }

            val formattedMessage = buildString {
                append(emoji)
                append("[${category.prefix}]")
                append("[$levelStr]")
                append(" ")
                append(tag)
                append(": ")
                append(message)
                if (throwable != null) {
                    append(" | ")
                    append(throwable.javaClass.simpleName)
                    append(": ")
                    append(throwable.message ?: "no message")
                }
            }

            LogRepository.getInstance().addLog(formattedMessage)
        }
    }

    /**
     * 步骤日志示例：[CONN][I] HotSwitch: [Step 1/3] Calling wake()
     */
    fun step(tag: String, current: Int, total: Int, message: String, category: Category = Category.CONNECTION) {
        log(category, tag, "[Step $current/$total] $message", Log.INFO)
    }

    /**
     */
    fun result(tag: String, success: Boolean, message: String, category: Category = Category.CONNECTION) {
        val level = if (success) Log.INFO else Log.WARN
        val prefix = if (success) "✅" else "❌"
        log(category, tag, "$prefix $message", level)
    }

    /**
     * State change logging
     * Example: L.stateChange("VPN", "STOPPED", "STARTING")
     */
    fun stateChange(tag: String, from: String, to: String, category: Category = Category.VPN) {
        log(category, tag, "$from → $to", Log.INFO)
    }

    /**
     */
    fun metric(tag: String, name: String, value: Number, unit: String = "", category: Category = Category.NETWORK) {
        log(category, tag, "$name: $value $unit".trim(), Log.DEBUG)
    }
}
