package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.InstalledApp
import com.kunk.singbox.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 已安装应用的 ViewModel
 * 负责管理应用列表的加载状态
 */
class InstalledAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = InstalledAppsRepository.getInstance(application)

    /** 已安装应用列表 */
    val installedApps: StateFlow<List<InstalledApp>> = repository.installedApps

    /** 加载状态 */
    val loadingState: StateFlow<InstalledAppsRepository.LoadingState> = repository.loadingState

    /**
     * 加载应用列表（如果需要）
     */
    fun loadAppsIfNeeded() {
        if (repository.needsLoading()) {
            viewModelScope.launch {
                repository.loadApps()
            }
        }
    }

    /**
     * 强制重新加载应用列表
     */
    fun reloadApps() {
        viewModelScope.launch {
            repository.reloadApps()
        }
    }

    /**
     * 检查是否已加载完成
     */
    fun isLoaded(): Boolean = repository.isLoaded()
}
