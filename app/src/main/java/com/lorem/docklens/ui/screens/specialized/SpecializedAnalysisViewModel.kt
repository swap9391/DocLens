package com.lorem.docklens.ui.screens.specialized

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorem.docklens.ai.InferenceManager
import com.lorem.docklens.ai.InferenceStep
import com.lorem.docklens.ai.AiPrompts
import com.lorem.docklens.ai.OcrHelper
import com.lorem.docklens.data.DocumentEntity
import com.lorem.docklens.data.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AnalysisState {
    object Idle : AnalysisState()
    object Processing : AnalysisState()
    data class Success(val result: String) : AnalysisState()
    data class Error(val message: String) : AnalysisState()
}

class SpecializedAnalysisViewModel(
    private val inferenceManager: InferenceManager,
    private val repository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val type: String,
    private val documentId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val uiState: StateFlow<AnalysisState> = _uiState

    private val _document = MutableStateFlow<DocumentEntity?>(null)
    val document: StateFlow<DocumentEntity?> = _document

    init {
        startAnalysis()
    }

    private fun startAnalysis() {
        viewModelScope.launch {
            _uiState.value = AnalysisState.Processing
            val doc = repository.getDocumentById(documentId)
            _document.value = doc

            if (doc == null) {
                _uiState.value = AnalysisState.Error("Document not found")
                return@launch
            }

            val ocrText = ocrHelper.extractText(File(doc.uri))
            val prompt = if (type == "MEDICAL") {
                AiPrompts.getMedicalReportPrompt(ocrText)
            } else {
                AiPrompts.getXRayAnalysisPrompt(ocrText)
            }

            var finalResult = ""
            inferenceManager.generateResponse(prompt).collect { step ->
                when (step) {
                    is InferenceStep.PartialResponse -> finalResult += step.text
                    is InferenceStep.FullResponse -> _uiState.value = AnalysisState.Success(step.response)
                    is InferenceStep.Error -> _uiState.value = AnalysisState.Error(step.message)
                    else -> {}
                }
            }
        }
    }
}

class SpecializedAnalysisViewModelFactory(
    private val inferenceManager: InferenceManager,
    private val repository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val type: String,
    private val documentId: Long
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SpecializedAnalysisViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SpecializedAnalysisViewModel(inferenceManager, repository, ocrHelper, type, documentId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
