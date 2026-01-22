package com.kunk.singbox.utils

import android.util.Log
import com.kunk.singbox.repository.LogRepository

/**
 * 结构化日志系统
 *
 * 日志分类:
 * - CONNECTION: 连接相关（节点切换、连接重置、热切换）
 * - VPN: VPN 服务生命周期（启动、停止、重启）
 * - CONFIG: 配置相关（订阅更新、配置解析、节点操作）
 * - NETWORK: 网络状态（流量、延迟测试、网络变化）
 * - ERROR: 错误和异常
 * - DEBUG: 调试信息（仅 Debug 构建）
 *
 * 使用方式:
 * ```
 * L.connection("HotSwitch", "Starting hot switch to node: $nodeTag")
 * L.vpn("Lifecycle", "VPN service started")
 * L.error("Health", "Health check failed", exception)
 * ```
 */
object L {

    /**
     * 日志类别
     */
    enum class Category(val prefix: String, val emoji: String) {
        CONNECTION("CONN", "\uD83D\uDD17"), // 🔗
        VPN("VPN", "\uD83D\uDEE1\uFE0F"), // 🛡️
        CONFIG("CFG", "\u2699\uFE0F"), // ⚙️
        NETWORK("NET", "\uD83C\uDF10"), // 🌐
        ERROR("ERR", "\u274C"), // ❌
        DEBUG("DBG", "\uD83D\uDC1B"), // 🐛
        INFO("INFO", "\u2139\uFE0F") // ℹ️
    }

    /**
     * 日志级别阈值
     * 只有 >= 此级别的日志才会输出到 LogRepository
     */
    @Volatile
    var minCategoryLevel: Int = Log.INFO

    /**
     * 是否在日志中显示 emoji（用于 UI 日志）
     */
    @Volatile
    var showEmoji: Boolean = true

    /**
     * 是否启用 Android Logcat 输出
     */
    @Volatile
    var logcatEnabled: Boolean = true

    /**
     * 是否启用 UI 日志（LogRepository）
     */
    @Volatile
    var uiLogEnabled: Boolean = true

    // ==================== 分类日志方法 ====================

    /**
     * 连接相关日志
     * 用于: 节点切换、热切换、连接重置、选择出站
     */
    fun connection(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.CONNECTION, tag, message, level)
    }

    /**
     * VPN 服务日志
     * 用于: 服务启动/停止/重启、TUN 设备、权限
     */
    fun vpn(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.VPN, tag, message, level)
    }

    /**
     * 配置相关日志
     * 用于: 订阅更新、配置解析、节点增删改、规则集
     */
    fun config(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.CONFIG, tag, message, level)
    }

    /**
     * 网络相关日志
     * 用于: 流量统计、延迟测试、网络状态变化
     */
    fun network(tag: String, message: String, level: Int = Log.INFO) {
        log(Category.NETWORK, tag, message, level)
    }

    /**
     * 错误日志
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(Category.ERROR, tag, message, Log.ERROR, throwable)
    }

    /**
     * 警告日志
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        log(Category.ERROR, tag, message, Log.WARN, throwable)
    }

    /**
     * 调试日志（仅 Debug 构建输出到 UI）
     */
    fun debug(tag: String, message: String) {
        log(Category.DEBUG, tag, message, Log.DEBUG)
    }

    /**
     * 通用信息日志
     */
    fun info(tag: String, message: String) {
        log(Category.INFO, tag, message, Log.INFO)
    }

    // ==================== 核心日志方法 ====================

    private fun log(
        category: Category,
        tag: String,
        message: String,
        level: Int,
        throwable: Throwable? = null
    ) {
        val fullTag = "${category.prefix}/$tag"

        // Logcat 输出
        if (logcatEnabled) {
            when (level) {
                Log.VERBOSE -> Log.v(fullTag, message, throwable)
                Log.DEBUG -> Log.d(fullTag, message, throwable)
                Log.INFO -> Log.i(fullTag, message, throwable)
                Log.WARN -> Log.w(fullTag, message, throwable)
                Log.ERROR -> Log.e(fullTag, message, throwable)
            }
        }

        // UI 日志输出（带级别过滤）
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

    // ==================== 便捷方法：带上下文的日志 ====================

    /**
     * 步骤日志 - 用于多步骤操作
     * 示例: L.step("HotSwitch", 1, 3, "Calling wake()")
     * 输出: 🔗 [CONN][I] HotSwitch: [Step 1/3] Calling wake()
     */
    fun step(tag: String, current: Int, total: Int, message: String, category: Category = Category.CONNECTION) {
        log(category, tag, "[Step $current/$total] $message", Log.INFO)
    }

    /**
     * 结果日志 - 用于操作结果
     * 示例: L.result("HotSwitch", true, "Node switched successfully")
     */
    fun result(tag: String, success: Boolean, message: String, category: Category = Category.CONNECTION) {
        val level = if (success) Log.INFO else Log.WARN
        val prefix = if (success) "✓" else "✗"
        log(category, tag, "$prefix $message", level)
    }

    /**
     * 状态变化日志
     * 示例: L.stateChange("VPN", "STOPPED", "STARTING")
     */
    fun stateChange(tag: String, from: String, to: String, category: Category = Category.VPN) {
        log(category, tag, "$from → $to", Log.INFO)
    }

    /**
     * 指标日志 - 用于数值监控
     * 示例: L.metric("Traffic", "Download", 1024, "KB/s")
     */
    fun metric(tag: String, name: String, value: Number, unit: String = "", category: Category = Category.NETWORK) {
        log(category, tag, "$name: $value $unit".trim(), Log.DEBUG)
    }
}
