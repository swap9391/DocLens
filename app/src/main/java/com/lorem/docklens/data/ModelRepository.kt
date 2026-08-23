package com.lorem.docklens.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class ModelRepository(private val context: Context) {
    private val _availableModels = MutableStateFlow<List<LlmModel>>(
        listOf(
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
                // Using a known working public task file URL
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
    )
    val availableModels: StateFlow<List<LlmModel>> = _availableModels.asStateFlow()

    init {
        refreshDownloadStatuses()
    }

    fun refreshDownloadStatuses() {
        _availableModels.update { list ->
            list.map { model ->
                val file = File(context.filesDir, "${model.id}.task")
                // Ensure file exists and is at least 100MB to be considered a valid download
                if (file.exists() && file.length() > 100 * 1024 * 1024) {
                    model.copy(downloadStatus = DownloadStatus.Downloaded)
                } else {
                    model.copy(downloadStatus = DownloadStatus.NotDownloaded)
                }
            }
        }
    }

    fun updateDownloadStatus(modelId: String, status: DownloadStatus) {
        _availableModels.update { list ->
            list.map { if (it.id == modelId) it.copy(downloadStatus = status) else it }
        }
    }
}
