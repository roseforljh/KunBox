package com.kunk.singbox.service.manager

import android.util.Log
import com.kunk.singbox.core.SelectorManager as CoreSelectorManager
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.flow.StateFlow

class SelectorManager {
    companion object {
        private const val TAG = "SelectorManager"
        private const val PROXY_SELECTOR_TAG = "PROXY"
    }

    private var commandClient: CommandClient? = null

    sealed class SwitchResult {
        data class Success(val nodeTag: String, val method: String) : SwitchResult()
        data class NeedRestart(val nodeTag: String, val reason: String) : SwitchResult()
        data class Failed(val error: String) : SwitchResult()
    }

    fun init(commandClient: CommandClient?): Result<Unit> {
        return runCatching {
            this.commandClient = commandClient
            Log.i(TAG, "SelectorManager initialized, commandClient=${commandClient != null}")
        }
    }

    fun recordSelector(outboundTags: List<String>, selectedTag: String?): Result<Unit> {
        return runCatching {
            CoreSelectorManager.recordSelectorSignature(outboundTags, selectedTag)
            Log.i(TAG, "Recorded ${outboundTags.size} outbounds, selected=$selectedTag")
        }
    }

    fun canHotSwitch(nodeTag: String): Boolean {
        return CoreSelectorManager.hasSelector() &&
            CoreSelectorManager.isNodeInCurrentSelector(nodeTag)
    }

    fun switchNode(nodeTag: String): SwitchResult {
        if (!canHotSwitch(nodeTag)) {
            return SwitchResult.NeedRestart(nodeTag, "Node not in current selector")
        }

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

        return SwitchResult.NeedRestart(nodeTag, "CommandClient hot switch unavailable")
    }

    fun getSelectedOutbound(): String? = CoreSelectorManager.getSelectedOutbound()

    fun getSelectedOutboundFlow(): StateFlow<String?> = CoreSelectorManager.selectedOutbound

    fun getCurrentOutbounds(): List<String> = CoreSelectorManager.getCurrentOutboundTags()

    fun hasSelector(): Boolean = CoreSelectorManager.hasSelector()

    fun getCanHotSwitchFlow(): StateFlow<Boolean> = CoreSelectorManager.canHotSwitchFlow

    fun clear(): Result<Unit> {
        return runCatching {
            CoreSelectorManager.clear()
            commandClient = null
            Log.i(TAG, "SelectorManager cleared")
        }
    }

    fun updateCommandClient(client: CommandClient?) {
        this.commandClient = client
    }
}
