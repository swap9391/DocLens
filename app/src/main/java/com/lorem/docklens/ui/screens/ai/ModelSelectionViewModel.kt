package com.lorem.docklens.ui.screens.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.lorem.docklens.data.DownloadStatus
import com.lorem.docklens.data.HFRemoteModelGroup
import com.lorem.docklens.data.LlmModel
import com.lorem.docklens.data.ModelDownloadWorker
import com.lorem.docklens.data.ModelRepository
import com.lorem.docklens.data.UserPreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ModelSelectionUiState(
    val searchQuery: String = "",
    val filteredModels: List<LlmModel> = emptyList(),
    val remoteGroups: List<HFRemoteModelGroup> = emptyList(),
    val recommendedModels: List<LlmModel> = emptyList(),
    val useGpu: Boolean = false,
    val selectedModelDetails: LlmModel? = null,
    val isModelSelected: Boolean = false,
    val lastSelectedModel: LlmModel? = null,
    val isRefreshing: Boolean = false,
    val selectedGroup: HFRemoteModelGroup? = null
)

class ModelSelectionViewModel(
    private val repository: ModelRepository,
    private val preferencesRepository: UserPreferencesRepository,
    context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _useGpu = MutableStateFlow(false)
    private val _selectedModelDetails = MutableStateFlow<LlmModel?>(null)
    private val _isModelSelected = MutableStateFlow(false)
    private val _lastSelectedModel = MutableStateFlow<LlmModel?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _selectedGroup = MutableStateFlow<HFRemoteModelGroup?>(null)
    
    private val workManager = WorkManager.getInstance(context)

    val uiState: StateFlow<ModelSelectionUiState> = combine(
        repository.availableModels,
        repository.remoteGroups,
        _searchQuery,
        _useGpu,
        _selectedModelDetails,
        _isModelSelected,
        _lastSelectedModel,
        _isRefreshing,
        _selectedGroup
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val models = args[0] as List<LlmModel>
        @Suppress("UNCHECKED_CAST")
        val groups = args[1] as List<HFRemoteModelGroup>
        val query = args[2] as String
        val useGpu = args[3] as Boolean
        val details = args[4] as LlmModel?
        val isSelected = args[5] as Boolean
        val lastModel = args[6] as LlmModel?
        val refreshing = args[7] as Boolean
        val selectedGroup = args[8] as HFRemoteModelGroup?

        ModelSelectionUiState(
            searchQuery = query,
            filteredModels = models.filter { 
                it.name.contains(query, ignoreCase = true) || it.provider.contains(query, ignoreCase = true) 
            },
            remoteGroups = groups.filter {
                it.displayName.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
            },
            recommendedModels = models.filter { it.isRecommended },
            useGpu = useGpu,
            selectedModelDetails = details,
            isModelSelected = isSelected,
            lastSelectedModel = lastModel,
            isRefreshing = refreshing,
            selectedGroup = selectedGroup
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelSelectionUiState())

    init {
        refreshModels()
        
        viewModelScope.launch {
            preferencesRepository.useGpu.collect { enabled ->
                _useGpu.value = enabled
            }
        }
        
        viewModelScope.launch {
            preferencesRepository.selectedModelId.collect { id ->
                _lastSelectedModel.value = repository.availableModels.value.find { it.id == id }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.fetchRemoteModels()
            _isRefreshing.value = false
        }
    }

    fun selectModel(model: LlmModel) {
        viewModelScope.launch {
            preferencesRepository.setSelectedModelId(model.id)
            _lastSelectedModel.value = model
            _isModelSelected.value = true
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onToggleGpu(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUseGpu(enabled)
            _useGpu.value = enabled
        }
    }
    
    fun showModelDetails(model: LlmModel?) {
        _selectedModelDetails.value = model
    }

    fun selectGroup(group: HFRemoteModelGroup?) {
        _selectedGroup.value = group
    }

    fun getModelsForGroup(groupId: String): List<LlmModel> {
        return repository.getModelsForGroup(groupId)
    }

    fun downloadModel(model: LlmModel) {
        // Reset state before starting
        repository.updateDownloadStatus(model.id, DownloadStatus.Downloading(0))

        val downloadWorkRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                "modelId" to model.id,
                "modelName" to model.name,
                "downloadUrl" to model.downloadUrl,
                "format" to model.format.name
            ))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("download_${model.id}")
            .build()

        // Use REPLACE to ensure new configuration (like updated URL) is applied
        workManager.enqueueUniqueWork(
            "download_${model.id}",
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest
        )
        
        observeWork(model.id, downloadWorkRequest.id)
    }

    private fun observeWork(modelId: String, workId: java.util.UUID) {
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo != null) {
                    val progress = workInfo.progress.getInt("progress", 0)
                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            repository.updateDownloadStatus(modelId, DownloadStatus.Downloading(progress))
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            repository.updateDownloadStatus(modelId, DownloadStatus.Downloaded)
                            repository.refreshDownloadStatuses() // Verify file on disk
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMsg = workInfo.outputData.getString("error") ?: "Download failed"
                            repository.updateDownloadStatus(modelId, DownloadStatus.Error(errorMsg))
                        }
                        WorkInfo.State.CANCELLED -> {
                            repository.updateDownloadStatus(modelId, DownloadStatus.NotDownloaded)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun pauseDownload(model: LlmModel) {
        workManager.cancelUniqueWork("download_${model.id}")
        val currentProgress = (model.downloadStatus as? DownloadStatus.Downloading)?.progress ?: 0
        repository.updateDownloadStatus(model.id, DownloadStatus.Paused(currentProgress))
    }

    fun stopDownload(model: LlmModel) {
        workManager.cancelUniqueWork("download_${model.id}")
        repository.updateDownloadStatus(model.id, DownloadStatus.NotDownloaded)
    }
}

class ModelSelectionViewModelFactory(
    private val repository: ModelRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ModelSelectionViewModel(repository, preferencesRepository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
