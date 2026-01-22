package com.kunk.singbox.service.manager

import android.util.Log
import com.kunk.singbox.core.SelectorManager as CoreSelectorManager
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.flow.StateFlow

/**
 * 节点选择管理器 (协调者)
 * 封装 core.SelectorManager，提供统一的节点切换接口
 * 使用 Result<T> 返回值模式
 *
 * 热切换策略 (渐进式降级):
 * 1. 原生 CommandClient API (最可靠)
 * 2. BoxWrapperManager (备用)
 * 3. 完整重启 (fallback)
 */
class SelectorManager {
    companion object {
        private const val TAG = "SelectorManager"
        private const val PROXY_SELECTOR_TAG = "PROXY"
    }

    private var commandClient: CommandClient? = null

    /**
     * 切换结果
     */
    sealed class SwitchResult {
        data class Success(val nodeTag: String, val method: String) : SwitchResult()
        data class NeedRestart(val nodeTag: String, val reason: String) : SwitchResult()
        data class Failed(val error: String) : SwitchResult()
    }

    /**
     * 初始化管理器
     */
    fun init(commandClient: CommandClient?): Result<Unit> {
        return runCatching {
            this.commandClient = commandClient
            Log.i(TAG, "SelectorManager initialized, commandClient=${commandClient != null}")
        }
    }

    /**
     * 记录当前 selector 签名
     */
    fun recordSelector(outboundTags: List<String>, selectedTag: String?): Result<Unit> {
        return runCatching {
            CoreSelectorManager.recordSelectorSignature(outboundTags, selectedTag)
            Log.i(TAG, "Recorded ${outboundTags.size} outbounds, selected=$selectedTag")
        }
    }

    /**
     * 检查是否支持热切换
     */
    fun canHotSwitch(nodeTag: String): Boolean {
        return CoreSelectorManager.hasSelector() &&
            CoreSelectorManager.isNodeInCurrentSelector(nodeTag)
    }

    /**
     * 执行节点切换 (渐进式降级)
     */
    fun switchNode(nodeTag: String): SwitchResult {
        // 检查是否可以热切换
        if (!canHotSwitch(nodeTag)) {
            return SwitchResult.NeedRestart(nodeTag, "Node not in current selector")
        }

        // 策略 1: 使用 CommandClient
        commandClient?.let { client ->
            try {
                val success = CoreSelectorManager.selectOutbound(client, PROXY_SELECTOR_TAG, nodeTag)
                if (success) {
                    Log.i(TAG, "Hot switch via CommandClient: -> $nodeTag")
                    return SwitchResult.Success(nodeTag, "CommandClient")
                }
            } catch (e: Exception) {
                Log.w(TAG, "CommandClient switch failed: ${e.message}")
            }
            Unit
        }

        // 策略 2: 使用 BoxWrapperManager
        try {
            val success = CoreSelectorManager.selectOutboundViaWrapper(nodeTag)
            if (success) {
                Log.i(TAG, "Hot switch via BoxWrapper: -> $nodeTag")
                return SwitchResult.Success(nodeTag, "BoxWrapper")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BoxWrapper switch failed: ${e.message}")
        }

        // 策略 3: 需要完整重启
        return SwitchResult.NeedRestart(nodeTag, "All hot switch methods failed")
    }

    /**
     * 获取当前选中的节点
     */
    fun getSelectedOutbound(): String? = CoreSelectorManager.getSelectedOutbound()

    /**
     * 获取选中节点的 Flow
     */
    fun getSelectedOutboundFlow(): StateFlow<String?> = CoreSelectorManager.selectedOutbound

    /**
     * 获取当前 selector 的所有节点
     */
    fun getCurrentOutbounds(): List<String> = CoreSelectorManager.getCurrentOutboundTags()

    /**
     * 检查是否有有效的 selector
     */
    fun hasSelector(): Boolean = CoreSelectorManager.hasSelector()

    /**
     * 获取热切换能力 Flow
     */
    fun getCanHotSwitchFlow(): StateFlow<Boolean> = CoreSelectorManager.canHotSwitchFlow

    /**
     * 清理状态
     */
    fun clear(): Result<Unit> {
        return runCatching {
            CoreSelectorManager.clear()
            commandClient = null
            Log.i(TAG, "SelectorManager cleared")
        }
    }

    /**
     * 更新 CommandClient
     */
    fun updateCommandClient(client: CommandClient?) {
        this.commandClient = client
    }
}
