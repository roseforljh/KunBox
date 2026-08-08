package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class KernelSelectionObservation(
    val revision: Long,
    val groupTag: String,
    val selectedTag: String
)

internal class KernelSelectionTracker {
    private val revision = AtomicLong(0L)
    private val observations = ConcurrentHashMap<String, MutableStateFlow<KernelSelectionObservation?>>()

    fun currentRevision(): Long = revision.get()

    fun record(groupTag: String, selectedTag: String) {
        observationFor(groupTag).value = KernelSelectionObservation(
            revision = revision.incrementAndGet(),
            groupTag = groupTag,
            selectedTag = selectedTag
        )
    }

    suspend fun awaitSelection(
        groupTag: String,
        expectedTag: String,
        afterRevision: Long,
        timeoutMs: Long
    ): String? {
        var latestSelection: String? = null
        val confirmed = withTimeoutOrNull(timeoutMs) {
            observationFor(groupTag)
                .filter { current ->
                    current != null && current.revision > afterRevision
                }
                .onEach { current -> latestSelection = current?.selectedTag }
                .first { current -> current?.selectedTag?.trim() == expectedTag.trim() }
        }
        return if (confirmed != null) expectedTag else latestSelection
    }

    fun clear() {
        observations.clear()
    }

    private fun observationFor(groupTag: String): MutableStateFlow<KernelSelectionObservation?> {
        return observations.computeIfAbsent(groupTag) { MutableStateFlow(null) }
    }
}

object SelectorManager {
    private const val TAG = "SelectorManager"
    private const val PROXY_SELECTOR_TAG = "PROXY"

    @Volatile
    private var currentSelectorSignature: String? = null

    @Volatile
    private var currentOutboundTags: List<String> = emptyList()

    @Volatile
    private var commandClient: CommandClient? = null

    @Volatile
    private var pendingSelectionTarget: String? = null

    private val _selectedOutbound = MutableStateFlow<String?>(null)
    val selectedOutbound: StateFlow<String?> = _selectedOutbound.asStateFlow()

    private val _canHotSwitch = MutableStateFlow(false)
    val canHotSwitchFlow: StateFlow<Boolean> = _canHotSwitch.asStateFlow()
    private val kernelSelectionTracker = KernelSelectionTracker()
    private val selectionMutex = Mutex()

    sealed class SwitchResult {
        data class Success(val method: String) : SwitchResult()
        data class NeedRestart(val reason: String) : SwitchResult()
    }

    fun updateCommandClient(client: CommandClient?) {
        commandClient = client
    }

    fun recordSelectorSignature(outboundTags: List<String>) {
        currentOutboundTags = outboundTags.toList()
        currentSelectorSignature = computeSignature(outboundTags)
        _canHotSwitch.value = outboundTags.isNotEmpty()
        _selectedOutbound.value = null
        Log.d(TAG, "Recorded selector: ${outboundTags.size} outbounds, sig=$currentSelectorSignature")
    }

    fun recordKernelSelection(groupTag: String, selectedTag: String) {
        if (groupTag.isBlank() || selectedTag.isBlank()) return
        kernelSelectionTracker.record(groupTag, selectedTag)
        if (groupTag == PROXY_SELECTOR_TAG) {
            _selectedOutbound.value = selectedTag
        }
    }

    fun canHotSwitch(newOutboundTags: List<String>): Boolean {
        val currentSig = currentSelectorSignature ?: return false
        val newSig = computeSignature(newOutboundTags)
        val canSwitch = currentSig == newSig
        Log.d(TAG, "canHotSwitch: current=$currentSig, new=$newSig, result=$canSwitch")
        return canSwitch
    }

    suspend fun switchNode(
        nodeTag: String,
        confirmationTimeoutMs: Long = SELECTION_CONFIRMATION_TIMEOUT_MS
    ): SwitchResult = selectionMutex.withLock {
        if (!hasSelector() || !currentOutboundTags.contains(nodeTag)) {
            return@withLock SwitchResult.NeedRestart("Node not in current selector")
        }

        val client = commandClient
            ?: return@withLock SwitchResult.NeedRestart("CommandClient hot switch unavailable")
        val beforeRevision = kernelSelectionTracker.currentRevision()
        pendingSelectionTarget = nodeTag
        try {
            client.selectOutbound(PROXY_SELECTOR_TAG, nodeTag)
            val actual = kernelSelectionTracker.awaitSelection(
                groupTag = PROXY_SELECTOR_TAG,
                expectedTag = nodeTag,
                afterRevision = beforeRevision,
                timeoutMs = confirmationTimeoutMs
            )
            when (actual) {
                nodeTag -> {
                    Log.i(TAG, "Hot switch confirmed by kernel: $PROXY_SELECTOR_TAG -> $nodeTag")
                    SwitchResult.Success("CommandClient+KernelAck")
                }
                null -> {
                    Log.e(TAG, "Hot switch confirmation timed out: expected=$nodeTag")
                    SwitchResult.NeedRestart("Kernel selection confirmation timed out: expected=$nodeTag")
                }
                else -> {
                    Log.e(TAG, "Hot switch confirmation mismatched: expected=$nodeTag actual=$actual")
                    SwitchResult.NeedRestart(
                        "Kernel selection confirmation mismatched: expected=$nodeTag actual=$actual"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hot switch via CommandClient failed: ${e.message}")
            SwitchResult.NeedRestart("CommandClient hot switch unavailable")
        } finally {
            pendingSelectionTarget = null
        }
    }

    fun getSelectedOutbound(): String? = _selectedOutbound.value

    fun isSelectionPending(): Boolean = pendingSelectionTarget != null

    fun getCurrentOutboundTags(): List<String> = currentOutboundTags

    fun hasSelector(): Boolean = currentSelectorSignature != null && currentOutboundTags.isNotEmpty()

    fun clear() {
        currentSelectorSignature = null
        currentOutboundTags = emptyList()
        _selectedOutbound.value = null
        _canHotSwitch.value = false
        commandClient = null
        pendingSelectionTarget = null
        kernelSelectionTracker.clear()
        Log.d(TAG, "Selector state cleared")
    }

    private fun computeSignature(tags: List<String>): String {
        return tags.sorted().hashCode().toString()
    }

    internal const val SELECTION_CONFIRMATION_TIMEOUT_MS = 2_500L
}
