package com.kunk.singbox.viewmodel.shared

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kunk.singbox.model.NodeFilter
import com.kunk.singbox.model.NodeSortType
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class NodeDisplaySettings private constructor(
    settingsRepository: SettingsRepository,
    scope: CoroutineScope
) {
    companion object {
        @Volatile
        private var instance: NodeDisplaySettings? = null

        fun getInstance(context: Context): NodeDisplaySettings {
            return instance ?: synchronized(this) {
                instance ?: NodeDisplaySettings(
                    SettingsRepository.getInstance(context),
                    ProcessLifecycleOwner.get().lifecycleScope
                ).also { instance = it }
            }
        }

        fun clearInstance() {
            instance = null
        }
    }

    val nodeFilter: StateFlow<NodeFilter> = settingsRepository.getNodeFilterFlow()
        .stateIn(scope, SharingStarted.Eagerly, NodeFilter())

    val sortType: StateFlow<NodeSortType> = settingsRepository.getNodeSortType()
        .stateIn(scope, SharingStarted.Eagerly, NodeSortType.DEFAULT)

    val customOrder: StateFlow<List<String>> = settingsRepository.getCustomNodeOrder()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
