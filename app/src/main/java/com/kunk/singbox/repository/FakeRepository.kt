package com.kunk.singbox.repository

import com.kunk.singbox.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

object FakeRepository {
    // State Flows for UI to observe
    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(ConnectionStats(0, 0, 0, 0, 0))
    val stats: StateFlow<ConnectionStats> = _stats.asStateFlow()

    private val _profiles = MutableStateFlow<List<ProfileUi>>(emptyList())
    val profiles: StateFlow<List<ProfileUi>> = _profiles.asStateFlow()

    private val _nodes = MutableStateFlow<List<NodeUi>>(emptyList())
    val nodes: StateFlow<List<NodeUi>> = _nodes.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _activeNodeId = MutableStateFlow<String?>(null)
    val activeNodeId: StateFlow<String?> = _activeNodeId.asStateFlow()

    // 用于管理统计模拟协程，避免泄漏
    private var statsJob: Job? = null
    private val repositoryScope = CoroutineScope(Dispatchers.Default)

    init {
        // Initialize with some mock data
        val mockProfiles = listOf(
            ProfileUi("p1", "香港优质线路", ProfileType.Subscription, "https://sub.example.com/1", System.currentTimeMillis(), true),
            ProfileUi("p2", "美国流媒体解锁", ProfileType.Subscription, "https://sub.example.com/2", System.currentTimeMillis() - 86400000, true),
            ProfileUi("p3", "本地配置", ProfileType.LocalFile, null, System.currentTimeMillis() - 10000000, false)
        )
        _profiles.value = mockProfiles
        _activeProfileId.value = "p1"

        val mockNodes = listOf(
            NodeUi("n1", "香港-01 [VLESS]", "vless", "HK", "🇭🇰", 45, true, "p1"),
            NodeUi("n2", "香港-02 [Trojan]", "trojan", "HK", "🇭🇰", 52, false, "p1"),
            NodeUi("n3", "美国-洛杉矶 [VMess]", "vmess", "US", "🇺🇸", 180, false, "p2"),
            NodeUi("n4", "日本-东京 [AnyTLS]", "anytls", "JP", "🇯🇵", 80, true, "p1"),
            NodeUi("n5", "新加坡-直连 [Hysteria2]", "hysteria2", "SG", "🇸🇬", 60, false, "p1")
        )
        _nodes.value = mockNodes
        _activeNodeId.value = "n1"
    }

    suspend fun toggleConnection() {
        when (_connectionState.value) {
            ConnectionState.Idle, ConnectionState.Error -> {
                _connectionState.value = ConnectionState.Connecting
                delay(1500) // Simulate connection delay
                // Check if still in Connecting state (user might have cancelled)
                if (_connectionState.value == ConnectionState.Connecting) {
                    if (Random.nextFloat() > 0.1) { // 90% success rate
                        _connectionState.value = ConnectionState.Connected
                        // 取消之前的模拟任务并启动新的
                        statsJob?.cancel()
                        statsJob = repositoryScope.launch {
                            startSimulatingStats()
                        }
                    } else {
                        _connectionState.value = ConnectionState.Error
                    }
                }
            }
            ConnectionState.Connecting -> {
                // User clicked while connecting - cancel and go to Idle immediately
                statsJob?.cancel()
                _connectionState.value = ConnectionState.Idle
                _stats.value = ConnectionStats(0, 0, 0, 0, 0)
            }
            ConnectionState.Connected, ConnectionState.Disconnecting -> {
                // 取消统计模拟协程
                statsJob?.cancel()
                _connectionState.value = ConnectionState.Disconnecting
                delay(500)
                _connectionState.value = ConnectionState.Idle
                _stats.value = ConnectionStats(0, 0, 0, 0, 0)
            }
        }
    }

    private suspend fun startSimulatingStats() {
        while (_connectionState.value == ConnectionState.Connected) {
            delay(1000)
            _stats.update { current ->
                current.copy(
                    uploadSpeed = Random.nextLong(1024, 1024 * 1024), // 1KB - 1MB
                    downloadSpeed = Random.nextLong(1024 * 10, 1024 * 1024 * 10), // 10KB - 10MB
                    uploadTotal = current.uploadTotal + Random.nextLong(1024, 1024 * 1024),
                    downloadTotal = current.downloadTotal + Random.nextLong(1024 * 10, 1024 * 1024 * 10),
                    duration = current.duration + 1000
                )
            }
        }
    }

    suspend fun testLatency(nodeId: String) {
        // Simulate latency test
        delay(Random.nextLong(200, 800))
        _nodes.update { list ->
            list.map {
                if (it.id == nodeId) it.copy(latencyMs = Random.nextInt(20, 300).toLong()) else it
            }
        }
    }

    fun setActiveNode(nodeId: String) {
        _activeNodeId.value = nodeId
    }

    fun setActiveProfile(profileId: String) {
        _activeProfileId.value = profileId
        // Update nodes based on profile (Mock)
        if (profileId == "p2") {
            val newNodes = listOf(
                NodeUi("n3", "美国-洛杉矶 [VMess]", "vmess", "自动选择", "🇺🇸", 180, false, "p2"),
                NodeUi("n6", "美国-纽约 [AnyTLS]", "anytls", "自动选择", "🇺🇸", 200, false, "p2"),
                NodeUi("n7", "手动-美国", "vmess", "手动选择", "🇺🇸", 190, false, "p2")
            )
            _nodes.value = newNodes
            _activeNodeId.value = "n3"
        } else {
            val newNodes = listOf(
                NodeUi("n1", "香港-01 [VLESS]", "vless", "HK", "🇭🇰", 45, true, "p1"),
                NodeUi("n2", "香港-02 [Trojan]", "trojan", "HK", "🇭🇰", 52, false, "p1"),
                NodeUi("n4", "日本-东京 [AnyTLS]", "anytls", "JP", "🇯🇵", 80, true, "p1"),
                NodeUi("n5", "新加坡-直连 [Hysteria2]", "hysteria2", "SG", "🇸🇬", 60, false, "p1")
            )
            _nodes.value = newNodes
            _activeNodeId.value = "n1"
        }
    }

    fun deleteProfile(profileId: String) {
        _profiles.update { list ->
            list.filter { it.id != profileId }
        }
        if (_activeProfileId.value == profileId) {
            _activeProfileId.value = _profiles.value.firstOrNull()?.id
        }
    }

    fun toggleProfileEnabled(profileId: String) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(enabled = !it.enabled) else it
            }
        }
    }

    suspend fun updateProfile(profileId: String) {
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Updating) else it
            }
        }
        delay(2000)
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(
                    updateStatus = UpdateStatus.Success,
                    lastUpdated = System.currentTimeMillis()
                ) else it
            }
        }
        delay(1000)
        _profiles.update { list ->
            list.map {
                if (it.id == profileId) it.copy(updateStatus = UpdateStatus.Idle) else it
            }
        }
    }

    fun addProfile(profile: ProfileUi) {
        _profiles.update { list ->
            list + profile
        }
    }
}
