package com.lorem.docklens.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class ModelRepository(private val context: Context) {
    private val hfRepository = HuggingFaceModelsRepository()
    
    private val recommendedModels = listOf(
        LlmModel(
            id = "gemma-2b-it-cpu-int4",
            name = "Gemma 2B IT",
            provider = "Google",
            size = "1.35 GB",
            description = "Google's lightweight open model. Highly optimized for Android and balanced performance.",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-cpu-int4.bin",
            format = ModelFormat.MEDIAPIPE_TASK,
            isRecommended = true
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek R1 Qwen 1.5B",
            provider = "DeepSeek",
            size = "1.1 GB",
            description = "Specialized reasoning model with <think> tag support. Distilled from DeepSeek-R1.",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-cpu-int4.bin", 
            format = ModelFormat.MEDIAPIPE_TASK,
            isRecommended = true,
            isReasoningModel = true
        ),
        LlmModel(
            id = "phi-3.5-mini-instruct",
            name = "Phi-3.5 Mini",
            provider = "Microsoft",
            size = "2.2 GB",
            description = "Microsoft's tiny but mighty 3.8B parameter model with huge context window.",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-cpu-int4.bin",
            format = ModelFormat.MEDIAPIPE_TASK,
            isRecommended = false
        )
    )

    private val _availableModels = MutableStateFlow<List<LlmModel>>(recommendedModels)
    val availableModels: StateFlow<List<LlmModel>> = _availableModels.asStateFlow()

    private val _remoteGroups = MutableStateFlow<List<HFRemoteModelGroup>>(emptyList())
    val remoteGroups: StateFlow<List<HFRemoteModelGroup>> = _remoteGroups.asStateFlow()

    init {
        refreshDownloadStatuses()
    }

    suspend fun fetchRemoteModels() {
        try {
            val groups = hfRepository.fetchRemoteLiteRtModels()
            _remoteGroups.value = groups
            
            val remoteModels = groups.flatMap { it.toLlmModels() }
            
            _availableModels.update { _ ->
                val recommendedIds = recommendedModels.map { it.id }.toSet()
                val uniqueRemoteModels = remoteModels.filter { it.id !in recommendedIds }
                (recommendedModels + uniqueRemoteModels)
            }
            refreshDownloadStatuses()
        } catch (e: Exception) {
            Log.e("ModelRepository", "Failed to fetch remote models", e)
        }
    }

    fun refreshDownloadStatuses() {
        _availableModels.update { list ->
            list.map { model ->
                val fileName = if (model.format == ModelFormat.LITERT_LM) "${model.id}.litertlm" else "${model.id}.task"
                val file = File(context.filesDir, fileName)
                if (file.exists() && file.length() > 1024 * 1024) {
                    model.copy(downloadStatus = DownloadStatus.Downloaded)
                } else {
                    if (model.downloadStatus is DownloadStatus.Downloading || model.downloadStatus is DownloadStatus.Paused) {
                        model
                    } else {
                        model.copy(downloadStatus = DownloadStatus.NotDownloaded)
                    }
                }
            }
        }
    }

    fun updateDownloadStatus(modelId: String, status: DownloadStatus) {
        _availableModels.update { list ->
            list.map { if (it.id == modelId) it.copy(downloadStatus = status) else it }
        }
    }

    fun getModelsForGroup(groupId: String): List<LlmModel> {
        val group = _remoteGroups.value.find { it.id == groupId } ?: return emptyList()
        val allModels = _availableModels.value
        return group.toLlmModels().map { remoteModel ->
            allModels.find { it.id == remoteModel.id } ?: remoteModel
        }
    }
}
