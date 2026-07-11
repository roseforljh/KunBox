package com.kunk.singbox.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.ClashConnectionsResponse
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.utils.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ConnectionInfoUiState(
    val response: ClashConnectionsResponse? = null,
    val isRefreshing: Boolean = true,
    val vpnActive: Boolean = false
)

internal fun Request.Builder.withClashApiAuth(secret: String?): Request.Builder = apply {
    if (!secret.isNullOrBlank()) header("Authorization", "Bearer $secret")
}

private data class ClashApiEndpoint(val baseUrl: String, val secret: String?)

class ConnectionInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "ConnectionInfoViewModel"
    private val gson = Gson()
    private val client = NetworkClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private val _isRefreshing = MutableStateFlow(true)
    private val refreshRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    // 缓存 Clash API 地址和密钥，避免每次轮询都读配置文件。
    private var cachedClashApiEndpoint: ClashApiEndpoint? = null

    val uiState: StateFlow<ConnectionInfoUiState> = flow {
        var lastResponse: ClashConnectionsResponse? = null
        merge(pollingTicks(), refreshRequests).collect { forceRefresh ->
            val isRefreshing = _isRefreshing.value
            val vpnActive = VpnStateStore.getActive()
            if (!vpnActive) {
                lastResponse = null
                cachedClashApiEndpoint = null
            } else if (isRefreshing || forceRefresh) {
                fetchConnections()?.let { lastResponse = it }
            }
            emit(
                ConnectionInfoUiState(
                    response = lastResponse,
                    isRefreshing = isRefreshing,
                    vpnActive = vpnActive
                )
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionInfoUiState()
    )

    fun setRefreshing(refresh: Boolean) {
        _isRefreshing.value = refresh
        refreshRequests.tryEmit(false)
    }

    private fun pollingTicks() = flow {
        while (true) {
            emit(false)
            delay(1500)
        }
    }

    private suspend fun getClashApiEndpoint(): ClashApiEndpoint? {
        cachedClashApiEndpoint?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val file = File(getApplication<Application>().filesDir, "running_config.json")
                if (file.exists()) {
                    val json = file.readText()
                    val config = gson.fromJson(json, SingBoxConfig::class.java)
                    val controller = config.experimental?.clashApi?.externalController
                    if (!controller.isNullOrBlank()) {
                        val endpoint = ClashApiEndpoint(
                            baseUrl = "http://$controller",
                            secret = config.experimental?.clashApi?.secret
                        )
                        cachedClashApiEndpoint = endpoint
                        return@withContext endpoint
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to read running config", e)
            }
            null
        }
    }

    private suspend fun fetchConnections(): ClashConnectionsResponse? = withContext(Dispatchers.IO) {
        val endpoint = getClashApiEndpoint() ?: return@withContext null
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/connections")
            .withClashApiAuth(endpoint.secret)
            .get()
            .build()

        try {
            NetworkClient.executeCancellable(client, request) { response ->
                if (!response.isSuccessful) return@executeCancellable null
                gson.fromJson(response.body.string(), ClashConnectionsResponse::class.java)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch connections", e)
            null
        }
    }

    fun closeConnection(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val endpoint = getClashApiEndpoint() ?: return@launch
            val request = Request.Builder()
                .url("${endpoint.baseUrl}/connections/$id")
                .withClashApiAuth(endpoint.secret)
                .delete()
                .build()

            try {
                NetworkClient.executeCancellable(client, request) { response ->
                    if (response.isSuccessful) {
                        refreshRequests.tryEmit(true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to close connection $id", e)
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            val endpoint = getClashApiEndpoint() ?: return@launch
            val request = Request.Builder()
                .url("${endpoint.baseUrl}/connections")
                .withClashApiAuth(endpoint.secret)
                .delete()
                .build()

            try {
                NetworkClient.executeCancellable(client, request) { response ->
                    if (response.isSuccessful) {
                        refreshRequests.tryEmit(true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to close all connections", e)
            }
        }
    }
}
