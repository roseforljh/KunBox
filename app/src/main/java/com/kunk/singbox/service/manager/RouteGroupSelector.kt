package com.kunk.singbox.service.manager

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.SingBoxConfig
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * 路由组自动选择管理器
 * 负责定期为路由规则使用的 Selector 选择最低延迟节点
 */
class RouteGroupSelector(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "RouteGroupSelector"
        private const val AUTO_SELECT_INTERVAL_MS = 30L * 60L * 1000L // 30 分钟
        private const val INITIAL_DELAY_MS = 1200L
        private const val LATENCY_TEST_TIMEOUT_MS = 4500L
        private const val MAX_CONCURRENT_TESTS = 4
    }

    private val gson = Gson()
    private var autoSelectJob: Job? = null

    interface Callbacks {
        val isRunning: Boolean
        val isStopping: Boolean
        fun getCommandClient(): CommandClient?
        fun getSelectedOutbound(groupTag: String): String?
    }

    private var callbacks: Callbacks? = null

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     * 启动路由组自动选择
     */
    fun start(configContent: String) {
        stop()

        autoSelectJob = serviceScope.launch {
            delay(INITIAL_DELAY_MS)
            while (callbacks?.isRunning == true && callbacks?.isStopping != true) {
                runCatching {
                    selectBestForRouteGroups(configContent)
                }
                delay(AUTO_SELECT_INTERVAL_MS)
            }
        }
    }

    /**
     * 停止路由组自动选择
     */
    fun stop() {
        autoSelectJob?.cancel()
        autoSelectJob = null
    }

    /**
     * 为路由规则引用的 Selector 选择最低延迟节点
     */
    private suspend fun selectBestForRouteGroups(configContent: String) {
        val cfg = runCatching { gson.fromJson(configContent, SingBoxConfig::class.java) }.getOrNull() ?: return
        val routeRules = cfg.route?.rules.orEmpty()
        val referencedOutbounds = routeRules.mapNotNull { it.outbound }.toSet()

        if (referencedOutbounds.isEmpty()) return

        val outbounds = cfg.outbounds.orEmpty()
        val byTag = outbounds.associateBy { it.tag }

        val targetSelectors = outbounds.filter {
            it.type == "selector" &&
                referencedOutbounds.contains(it.tag) &&
                !it.tag.equals("PROXY", ignoreCase = true)
        }

        if (targetSelectors.isEmpty()) return

        val client = waitForCommandClient(LATENCY_TEST_TIMEOUT_MS) ?: return
        val core = SingBoxCore.getInstance(context)
        val semaphore = Semaphore(permits = MAX_CONCURRENT_TESTS)

        for (selector in targetSelectors) {
            if (callbacks?.isRunning != true || callbacks?.isStopping == true) return

            val groupTag = selector.tag
            val candidates = selector.outbounds
                .orEmpty()
                .filter { it.isNotBlank() }
                .filterNot { it.equals("direct", true) || it.equals("block", true) || it.equals("dns-out", true) }

            if (candidates.isEmpty()) continue

            val results = ConcurrentHashMap<String, Long>()

            coroutineScope {
                candidates.map { tag ->
                    async(Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            val outbound = byTag[tag] ?: return@async
                            val rtt = try {
                                core.testOutboundLatency(outbound, outbounds)
                            } catch (_: Exception) {
                                -1L
                            }
                            if (rtt >= 0) {
                                results[tag] = rtt
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            val best = results.entries.minByOrNull { it.value }?.key ?: continue
            val currentSelected = callbacks?.getSelectedOutbound(groupTag)
            if (currentSelected != null && currentSelected == best) continue

            runCatching {
                try {
                    client.selectOutbound(groupTag, best)
                } catch (_: Exception) {
                    client.selectOutbound(groupTag.lowercase(), best)
                }
            }
        }
    }

    private suspend fun waitForCommandClient(timeoutMs: Long): CommandClient? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val c = callbacks?.getCommandClient()
            if (c != null) return c
            delay(120)
        }
        return callbacks?.getCommandClient()
    }

    fun cleanup() {
        stop()
        callbacks = null
    }
}
