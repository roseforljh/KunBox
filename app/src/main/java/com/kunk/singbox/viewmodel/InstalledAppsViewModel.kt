package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.InstalledApp
import com.kunk.singbox.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 *
 */
class InstalledAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = InstalledAppsRepository.getInstance(application)

    /** 宸插畨瑁呭簲鐢ㄥ垪琛?*/
    val installedApps: StateFlow<List<InstalledApp>> = repository.installedApps

    /** 鍔犺浇鐘舵€?*/
    val loadingState: StateFlow<InstalledAppsRepository.LoadingState> = repository.loadingState

    /**
     */
    fun loadAppsIfNeeded() {
        if (repository.needsLoading()) {
            viewModelScope.launch {
                repository.loadApps()
            }
        }
    }

    /**
     *
     */
    fun reloadApps() {
        viewModelScope.launch {
            repository.reloadApps()
        }
    }

    fun isLoaded(): Boolean = repository.isLoaded()
}
