package com.kunk.singbox.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.ClashConnection
import com.kunk.singbox.model.ClashConnectionsResponse
import com.kunk.singbox.model.SingBoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ConnectionInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "ConnectionInfoViewModel"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private val _connectionsResponse = MutableStateFlow<ClashConnectionsResponse?>(null)
    val connectionsResponse = _connectionsResponse.asStateFlow()

    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    private var pollJob: Job? = null
    // 缓存 Clash API 地址，避免每次轮询都读配置文件
    private var cachedClashApiUrl: String? = null

    val connections: StateFlow<List<ClashConnection>> =
        _connectionsResponse.map { it?.connections ?: emptyList() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    init {
        startPolling()
    }

    fun setRefreshing(refresh: Boolean) {
        _isRefreshing.value = refresh
        if (refresh) {
            startPolling()
        } else {
            stopPolling()
        }
    }

    private fun startPolling() {
        if (pollJob != null && pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                val isActive = VpnStateStore.getActive()
                _vpnActive.value = isActive
                if (isActive && _isRefreshing.value) {
                    fetchConnections()
                } else {
                    _connectionsResponse.value = null
                    // VPN 不活跃时清除缓存，下次连接后重新读取
                    cachedClashApiUrl = null
                }
                delay(1500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun getClashApiUrl(): String? {
        cachedClashApiUrl?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val file = File(getApplication<Application>().filesDir, "running_config.json")
                if (file.exists()) {
                    val json = file.readText()
                    val config = gson.fromJson(json, SingBoxConfig::class.java)
                    val controller = config.experimental?.clashApi?.externalController
                    if (!controller.isNullOrBlank()) {
                        val url = "http://$controller"
                        cachedClashApiUrl = url
                        return@withContext url
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to read running config", e)
            }
            null
        }
    }

    private suspend fun fetchConnections() = withContext(Dispatchers.IO) {
        val baseUrl = getClashApiUrl() ?: return@withContext
        val request = Request.Builder()
            .url("$baseUrl/connections")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val res = gson.fromJson(body, ClashConnectionsResponse::class.java)
                        _connectionsResponse.value = res
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch connections", e)
        }
    }

    fun closeConnection(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = getClashApiUrl() ?: return@launch
            val request = Request.Builder()
                .url("$baseUrl/connections/$id")
                .delete()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        fetchConnections()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to close connection $id", e)
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseUrl = getClashApiUrl() ?: return@launch
            val request = Request.Builder()
                .url("$baseUrl/connections")
                .delete()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        fetchConnections()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to close all connections", e)
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
