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
            downloadUrl = "https://huggingface.co/google/gemma-2b-it-lite-rt/resolve/main/gemma-2b-it-cpu-int4.bin?download=true",
            format = ModelFormat.MEDIAPIPE_TASK,
            isRecommended = true
        ),
        LlmModel(
            id = "phi-3-mini-4k-instruct",
            name = "Phi-3 Mini 4K",
            provider = "Microsoft",
            size = "2.3 GB",
            description = "Microsoft's efficient 3.8B parameter model. Strong reasoning capabilities.",
            downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-lite-rt/resolve/main/Phi-3-mini-4k-instruct-cpu-int4.bin?download=true",
            format = ModelFormat.MEDIAPIPE_TASK,
            isRecommended = true
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek R1 Qwen 1.5B",
            provider = "DeepSeek",
            size = "1.1 GB",
            description = "Specialized reasoning model with <think> tag support. Distilled from DeepSeek-R1.",
            downloadUrl = "https://huggingface.co/google/gemma-2b-it-lite-rt/resolve/main/gemma-2b-it-cpu-int4.bin?download=true", // Fallback to Gemma as specialized task files are rarer
            format = ModelFormat.MEDIAPIPE_TASK,
            isRecommended = true,
            isReasoningModel = true
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
                val file = getModelFile(model.id, model.format)
                if (file.exists() && file.length() > 1024 * 1024) {
                    model.copy(downloadStatus = DownloadStatus.Downloaded)
                } else {
                    if (model.downloadStatus is DownloadStatus.Downloading || model.downloadStatus is DownloadStatus.Paused) {
                        model
                    } else {
                        model.copy(downloadStatus = DownloadStatus.NotDownloaded)
                    }
                }
            }.sortedWith(
                compareByDescending<LlmModel> { it.downloadStatus == DownloadStatus.Downloaded }
                    .thenByDescending { it.isRecommended }
            )
        }
    }

    fun updateDownloadStatus(modelId: String, status: DownloadStatus) {
        _availableModels.update { list ->
            val updatedList = list.map { if (it.id == modelId) it.copy(downloadStatus = status) else it }
            // If something just finished downloading, re-sort
            if (status == DownloadStatus.Downloaded) {
                updatedList.sortedWith(
                    compareByDescending<LlmModel> { it.downloadStatus == DownloadStatus.Downloaded }
                        .thenByDescending { it.isRecommended }
                )
            } else {
                updatedList
            }
        }
    }
    
    fun getModelFile(modelId: String, format: ModelFormat): File {
        val extension = if (format == ModelFormat.LITERT_LM) ".litertlm" else ".task"
        val modelsDir = File(context.getExternalFilesDir(null), "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return File(modelsDir, "$modelId$extension")
    }

    fun getModelsForGroup(groupId: String): List<LlmModel> {
        val group = _remoteGroups.value.find { it.id == groupId } ?: return emptyList()
        val allModels = _availableModels.value
        return group.toLlmModels().map { remoteModel ->
            allModels.find { it.id == remoteModel.id } ?: remoteModel
        }.sortedByDescending { it.downloadStatus == DownloadStatus.Downloaded }
    }
}
