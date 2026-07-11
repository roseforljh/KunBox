package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.repository.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LogRepository.getInstance()

    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    val categories: List<String> = listOf("CONN", "DNS", "ERR", "WARN", "INFO")

    val filteredLogs: StateFlow<List<String>> = combine(
        repository.logs,
        _searchKeyword,
        _selectedCategory
    ) { logs, keyword, category ->
        var result = logs
        if (!category.isNullOrBlank()) {
            result = result.filter { it.contains(category, ignoreCase = true) }
        }
        if (keyword.isNotBlank()) {
            val lower = keyword.lowercase()
            result = result.filter { it.lowercase().contains(lower) }
        }
        result
    }.flowOn(Dispatchers.Default)
        .onStart {
            withContext(Dispatchers.IO) {
                repository.setLogUiActive(true)
            }
        }
        .onCompletion {
            repository.setLogUiActive(false)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setSearchKeyword(keyword: String) {
        _searchKeyword.value = keyword
    }

    fun setCategory(category: String?) {
        _selectedCategory.value = if (_selectedCategory.value == category) null else category
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearLogs()
        }
    }

    fun getLogsForExport(): String {
        return repository.getLogsAsText()
    }
}
