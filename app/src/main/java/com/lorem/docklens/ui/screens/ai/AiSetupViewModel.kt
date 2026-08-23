package com.lorem.docklens.ui.screens.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorem.docklens.ai.AiModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class AiSetupState {
    object Idle : AiSetupState()
    data class Downloading(val progress: Int) : AiSetupState()
    object Initializing : AiSetupState()
    object Ready : AiSetupState()
    data class Error(val message: String) : AiSetupState()
}

class AiSetupViewModel(private val aiModelManager: AiModelManager) : ViewModel() {

    private val _uiState = MutableStateFlow<AiSetupState>(
        if (aiModelManager.isModelDownloaded()) AiSetupState.Ready else AiSetupState.Idle
    )
    val uiState: StateFlow<AiSetupState> = _uiState

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    fun startDownload(url: String) {
        viewModelScope.launch {
            _uiState.value = AiSetupState.Downloading(0)
            val result = aiModelManager.downloadModel(url) { progress ->
                _uiState.value = AiSetupState.Downloading(progress)
            }
            
            if (result.isSuccess) {
                initializeModel()
            } else {
                _uiState.value = AiSetupState.Error("Failed to download model: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun initializeModel() {
        viewModelScope.launch {
            _uiState.value = AiSetupState.Initializing
            val result = aiModelManager.initInference()
            if (result.isSuccess) {
                _uiState.value = AiSetupState.Ready
            } else {
                _uiState.value = AiSetupState.Error("Failed to initialize AI: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun runTestInference() {
        viewModelScope.launch {
            _testResult.value = "Thinking..."
            val result = aiModelManager.generateResponse("Hello, who are you? Keep it very short.")
            _testResult.value = result.getOrElse { "Error: ${it.message}" }
        }
    }

    override fun onCleared() {
        super.onCleared()
        aiModelManager.close()
    }
}

class AiSetupViewModelFactory(private val aiModelManager: AiModelManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiSetupViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AiSetupViewModel(aiModelManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
