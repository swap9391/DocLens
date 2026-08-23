package com.lorem.docklens.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorem.docklens.ai.InferenceManager
import com.lorem.docklens.ai.InferenceStep
import com.lorem.docklens.ai.OcrHelper
import com.lorem.docklens.data.ChatMessageEntity
import com.lorem.docklens.data.ChatRepository
import com.lorem.docklens.data.DocumentEntity
import com.lorem.docklens.data.DocumentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(
    private val inferenceManager: InferenceManager,
    private val chatRepository: ChatRepository,
    private val documentRepository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val initialDocumentId: Long?
) : ViewModel() {

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping

    private val _currentReasoning = MutableStateFlow<String?>(null)
    val currentReasoning: StateFlow<String?> = _currentReasoning

    private val _attachedDocument = MutableStateFlow<DocumentEntity?>(null)
    val attachedDocument: StateFlow<DocumentEntity?> = _attachedDocument

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessageEntity>> = _sessionId.flatMapLatest { id ->
        if (id != null) {
            chatRepository.getSessionWithMessages(id).map { it.messages }
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // For transient/streaming response before saving to DB
    private val _streamingResponse = MutableStateFlow<String?>(null)
    val streamingResponse: StateFlow<String?> = _streamingResponse

    init {
        viewModelScope.launch {
            // Create a new session or load existing
            val id = chatRepository.createSession("New Chat", null)
            _sessionId.value = id

            initialDocumentId?.let { docId ->
                if (docId != -1L) {
                    val doc = documentRepository.getDocumentById(docId)
                    _attachedDocument.value = doc
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val sid = _sessionId.value ?: return
        if (text.isBlank()) return

        viewModelScope.launch {
            // Save user message
            chatRepository.saveMessage(sid, text, isUser = true)
            
            _isTyping.value = true
            _streamingResponse.value = ""
            _currentReasoning.value = null
            
            var contextText: String? = null
            _attachedDocument.value?.let { doc ->
                contextText = ocrHelper.extractText(File(doc.uri))
            }

            val prompt = if (contextText != null) {
                "Context from document: $contextText\n\nUser Question: $text"
            } else {
                text
            }

            var accumulatedText = ""
            var finalReasoning: String? = null

            inferenceManager.generateResponse(prompt).collect { step ->
                when (step) {
                    is InferenceStep.PartialReasoning -> {
                        _currentReasoning.value = (_currentReasoning.value ?: "") + step.text
                    }
                    is InferenceStep.PartialResponse -> {
                        accumulatedText += step.text
                        _streamingResponse.value = accumulatedText
                    }
                    is InferenceStep.FullResponse -> {
                        _isTyping.value = false
                        _streamingResponse.value = null
                        finalReasoning = _currentReasoning.value
                        chatRepository.saveMessage(sid, step.response, isUser = false, reasoning = finalReasoning)
                    }
                    is InferenceStep.Error -> {
                        _isTyping.value = false
                        _streamingResponse.value = null
                        chatRepository.saveMessage(sid, "Error: ${step.message}", isUser = false)
                    }
                    else -> {}
                }
            }
        }
    }

    fun attachDocument(document: DocumentEntity) {
        _attachedDocument.value = document
    }

    fun detachDocument() {
        _attachedDocument.value = null
    }

    override fun onCleared() {
        super.onCleared()
        inferenceManager.stopGeneration()
    }
}

class ChatViewModelFactory(
    private val inferenceManager: InferenceManager,
    private val chatRepository: ChatRepository,
    private val documentRepository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val initialDocumentId: Long?
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(inferenceManager, chatRepository, documentRepository, ocrHelper, initialDocumentId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
