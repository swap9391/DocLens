package com.lorem.docklens.ui.screens.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorem.docklens.data.DownloadStatus
import com.lorem.docklens.data.LlmModel
import com.lorem.docklens.data.ModelRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ModelSelectionUiState(
    val searchQuery: String = "",
    val filteredModels: List<LlmModel> = emptyList(),
    val recommendedModels: List<LlmModel> = emptyList(),
    val useGpu: Boolean = false
)

class ModelSelectionViewModel(private val repository: ModelRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _useGpu = MutableStateFlow(false)

    val uiState: StateFlow<ModelSelectionUiState> = combine(
        repository.availableModels,
        _searchQuery,
        _useGpu
    ) { models, query, useGpu ->
        ModelSelectionUiState(
            searchQuery = query,
            filteredModels = models.filter { 
                it.name.contains(query, ignoreCase = true) || it.provider.contains(query, ignoreCase = true) 
            },
            recommendedModels = models.filter { it.isRecommended },
            useGpu = useGpu
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelSelectionUiState())

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onToggleGpu(enabled: Boolean) {
        _useGpu.value = enabled
    }

    fun downloadModel(model: LlmModel) {
        // Implementation for download starting
        viewModelScope.launch {
            repository.updateDownloadStatus(model.id, DownloadStatus.Downloading(0))
            // Actual download logic would be triggered here
        }
    }
}

class ModelSelectionViewModelFactory(private val repository: ModelRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ModelSelectionViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
