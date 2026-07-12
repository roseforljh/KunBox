package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SelectorManager {
    private const val TAG = "SelectorManager"
    private const val PROXY_SELECTOR_TAG = "PROXY"

    @Volatile
    private var currentSelectorSignature: String? = null

    @Volatile
    private var currentOutboundTags: List<String> = emptyList()

    @Volatile
    private var commandClient: CommandClient? = null

    private val _selectedOutbound = MutableStateFlow<String?>(null)
    val selectedOutbound: StateFlow<String?> = _selectedOutbound.asStateFlow()

    private val _canHotSwitch = MutableStateFlow(false)
    val canHotSwitchFlow: StateFlow<Boolean> = _canHotSwitch.asStateFlow()

    sealed class SwitchResult {
        data class Success(val method: String) : SwitchResult()
        data class NeedRestart(val reason: String) : SwitchResult()
    }

    fun updateCommandClient(client: CommandClient?) {
        commandClient = client
    }

    fun recordSelectorSignature(outboundTags: List<String>, selectedTag: String? = null) {
        currentOutboundTags = outboundTags.toList()
        currentSelectorSignature = computeSignature(outboundTags)
        _canHotSwitch.value = outboundTags.isNotEmpty()
        if (selectedTag != null) {
            _selectedOutbound.value = selectedTag
        }
        Log.d(TAG, "Recorded selector: ${outboundTags.size} outbounds, sig=$currentSelectorSignature, selected=$selectedTag")
    }

    fun canHotSwitch(newOutboundTags: List<String>): Boolean {
        val currentSig = currentSelectorSignature ?: return false
        val newSig = computeSignature(newOutboundTags)
        val canSwitch = currentSig == newSig
        Log.d(TAG, "canHotSwitch: current=$currentSig, new=$newSig, result=$canSwitch")
        return canSwitch
    }

    fun switchNode(nodeTag: String): SwitchResult {
        if (!hasSelector() || !currentOutboundTags.contains(nodeTag)) {
            return SwitchResult.NeedRestart("Node not in current selector")
        }

        val client = commandClient ?: return SwitchResult.NeedRestart("CommandClient hot switch unavailable")
        return try {
            client.selectOutbound(PROXY_SELECTOR_TAG, nodeTag)
            _selectedOutbound.value = nodeTag
            Log.i(TAG, "Hot switch via CommandClient: $PROXY_SELECTOR_TAG -> $nodeTag")
            SwitchResult.Success("CommandClient")
        } catch (e: Exception) {
            Log.e(TAG, "Hot switch via CommandClient failed: ${e.message}")
            SwitchResult.NeedRestart("CommandClient hot switch unavailable")
        }
    }

    fun getSelectedOutbound(): String? = _selectedOutbound.value

    fun getCurrentOutboundTags(): List<String> = currentOutboundTags

    fun hasSelector(): Boolean = currentSelectorSignature != null && currentOutboundTags.isNotEmpty()

    fun clear() {
        currentSelectorSignature = null
        currentOutboundTags = emptyList()
        _selectedOutbound.value = null
        _canHotSwitch.value = false
        commandClient = null
        Log.d(TAG, "Selector state cleared")
    }

    private fun computeSignature(tags: List<String>): String {
        return tags.sorted().hashCode().toString()
    }
}
