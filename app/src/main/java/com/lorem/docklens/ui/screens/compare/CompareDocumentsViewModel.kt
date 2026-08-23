package com.lorem.docklens.ui.screens.compare

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

sealed class CompareState {
    object Idle : CompareState()
    object Processing : CompareState()
    data class Success(val result: String) : CompareState()
    data class Error(val message: String) : CompareState()
}

class CompareDocumentsViewModel(
    private val inferenceManager: InferenceManager,
    private val repository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val docId1: Long,
    private val docId2: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow<CompareState>(CompareState.Idle)
    val uiState: StateFlow<CompareState> = _uiState

    private val _docs = MutableStateFlow<Pair<DocumentEntity?, DocumentEntity?>>(null to null)
    val docs: StateFlow<Pair<DocumentEntity?, DocumentEntity?>> = _docs

    init {
        startComparison()
    }

    private fun startComparison() {
        viewModelScope.launch {
            _uiState.value = CompareState.Processing
            val doc1 = repository.getDocumentById(docId1)
            val doc2 = repository.getDocumentById(docId2)
            _docs.value = doc1 to doc2

            if (doc1 == null || doc2 == null) {
                _uiState.value = CompareState.Error("One or both documents not found")
                return@launch
            }

            val text1 = ocrHelper.extractText(File(doc1.uri))
            val text2 = ocrHelper.extractText(File(doc2.uri))

            val prompt = AiPrompts.getComparisonPrompt(doc1.name, text1, doc2.name, text2)
            
            var finalResult = ""
            inferenceManager.generateResponse(prompt).collect { step ->
                when (step) {
                    is InferenceStep.PartialResponse -> finalResult += step.text
                    is InferenceStep.FullResponse -> _uiState.value = CompareState.Success(step.response)
                    is InferenceStep.Error -> _uiState.value = CompareState.Error(step.message)
                    else -> {}
                }
            }
        }
    }
}

class CompareDocumentsViewModelFactory(
    private val inferenceManager: InferenceManager,
    private val repository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val docId1: Long,
    private val docId2: Long
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CompareDocumentsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CompareDocumentsViewModel(inferenceManager, repository, ocrHelper, docId1, docId2) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
