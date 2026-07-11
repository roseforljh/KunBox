package com.kunk.singbox.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.InstalledAppUi
import com.kunk.singbox.repository.InstalledAppsRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class InstalledAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = InstalledAppsRepository.getInstance(application)

    /** 加载状态 */
    val loadingState: StateFlow<InstalledAppsRepository.LoadingState> = repository.loadingState

    val appItems: StateFlow<List<InstalledAppUi>> = repository.appItems

    fun loadAppsIfNeeded() {
        if (repository.needsLoading()) {
            viewModelScope.launch {
                repository.loadApps()
            }
        }
    }

    fun reloadApps() {
        viewModelScope.launch {
            repository.reloadApps()
        }
    }

    suspend fun loadIcon(packageName: String): Bitmap? = repository.loadIcon(packageName)
}
