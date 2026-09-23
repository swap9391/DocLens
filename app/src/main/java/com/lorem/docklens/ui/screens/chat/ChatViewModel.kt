package com.lorem.docklens.ui.screens.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorem.docklens.ai.InferenceManager
import com.lorem.docklens.ai.InferenceStep
import com.lorem.docklens.ai.ModelLoadCoordinator
import com.lorem.docklens.ai.ModelLoadState
import com.lorem.docklens.ai.OcrHelper
import com.lorem.docklens.data.ChatMessageEntity
import com.lorem.docklens.data.ChatRepository
import com.lorem.docklens.data.DocumentEntity
import com.lorem.docklens.data.DocumentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration.Companion.milliseconds

class ChatViewModel(
    private val inferenceManager: InferenceManager,
    private val chatRepository: ChatRepository,
    private val documentRepository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val modelLoadCoordinator: ModelLoadCoordinator,
    private val initialDocumentId: Long?,
) : ViewModel() {

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping

    private val _currentReasoning = MutableStateFlow<String?>(null)
    val currentReasoning: StateFlow<String?> = _currentReasoning

    private val _attachedDocument = MutableStateFlow<DocumentEntity?>(null)
    val attachedDocument: StateFlow<DocumentEntity?> = _attachedDocument

    /**
     * Which model is answering. Chat is intentionally blocked until this is
     * [ModelLoadState.Ready], so a message can never be silently dropped because
     * the engine was still initialising or had failed to start.
     */
    val modelState: StateFlow<ModelLoadState> = modelLoadCoordinator.state

    val canSend: StateFlow<Boolean> = combine(modelState, _isTyping) { state, typing ->
        state is ModelLoadState.Ready && !typing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
            val id = chatRepository.createSession("New Chat", null)
            _sessionId.value = id

            initialDocumentId?.let { docId ->
                if (docId != -1L) {
                    _attachedDocument.value = documentRepository.getDocumentById(docId)
                }
            }
        }
    }

    fun retryModelLoad() = modelLoadCoordinator.retry()

    fun sendMessage(text: String) {
        val sid = _sessionId.value ?: return
        if (text.isBlank()) return

        val currentModelState = modelState.value
        if (currentModelState !is ModelLoadState.Ready) {
            viewModelScope.launch {
                chatRepository.saveMessage(sid, text, isUser = true)
                chatRepository.saveMessage(
                    sid,
                    when (currentModelState) {
                        ModelLoadState.NoModel ->
                            "No AI model is installed yet. Open AI settings to download one."
                        is ModelLoadState.Loading ->
                            "${currentModelState.model.name} is still loading. Try again in a moment."
                        is ModelLoadState.Failed ->
                            "The selected model could not be loaded: ${currentModelState.message}"
                        else -> "The AI engine is not ready yet."
                    },
                    isUser = false
                )
            }
            return
        }

        viewModelScope.launch {
            chatRepository.saveMessage(sid, text, isUser = true)

            _isTyping.value = true
            _streamingResponse.value = ""
            _currentReasoning.value = null

            val contextText = _attachedDocument.value?.let { doc ->
                runCatching { ocrHelper.extractText(File(doc.uri)) }.getOrNull()
            }

            val prompt = if (!contextText.isNullOrBlank()) {
                "Context from document: $contextText\n\nUser Question: $text"
            } else {
                text
            }

            var accumulatedText = ""

            inferenceManager.generateResponse(prompt).collect { step ->
                when (step) {
                    is InferenceStep.PartialReasoning -> {
                        _currentReasoning.value = (_currentReasoning.value ?: "") + step.text
                    }
                    is InferenceStep.PartialResponse -> {
                        _isTyping.value = true
                        accumulatedText += step.text
                        _streamingResponse.value = accumulatedText
                    }
                    is InferenceStep.FullResponse -> {
                        _isTyping.value = false
                        _streamingResponse.value = null
                        chatRepository.saveMessage(
                            sid,
                            step.response,
                            isUser = false,
                            reasoning = _currentReasoning.value
                        )
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

    val allDocuments: StateFlow<List<DocumentEntity>> = documentRepository.allDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

     fun getDocumentById()  {
        //documentRepository.getDocumentById(allDocuments.value.last().id)
        viewModelScope.launch {
            delay(1000.milliseconds)

            val lastDocument: DocumentEntity? =
                documentRepository.getLatestDocument()

            attachDocument(lastDocument!!)
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

    fun importDocument(context: Context, uri: Uri, type: String) {
        viewModelScope.launch {
            val displayName = getFileName(context, uri)
            val fileName = "imported_${System.currentTimeMillis()}.${if (type == "PDF") "pdf" else "jpg"}"
            val file = File(context.filesDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            val document = DocumentEntity(
                name = displayName,
                type = type,
                uri = file.absolutePath
            )
            documentRepository.insert(document)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Unknown Document"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

}

class ChatViewModelFactory(
    private val inferenceManager: InferenceManager,
    private val chatRepository: ChatRepository,
    private val documentRepository: DocumentRepository,
    private val ocrHelper: OcrHelper,
    private val modelLoadCoordinator: ModelLoadCoordinator,
    private val initialDocumentId: Long?
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                inferenceManager,
                chatRepository,
                documentRepository,
                ocrHelper,
                modelLoadCoordinator,
                initialDocumentId
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
