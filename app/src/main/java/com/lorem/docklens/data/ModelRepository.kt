package com.lorem.docklens.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ModelRepository {
    private val _availableModels = MutableStateFlow<List<LlmModel>>(
        listOf(
            LlmModel(
                id = "deepseek-r1-distill-qwen-1.5b",
                name = "DeepSeek R1 Qwen 1.5B",
                provider = "DeepSeek",
                size = "1.1 GB",
                description = "Specialized reasoning model with <think> tag support. Distilled from DeepSeek-R1. High intelligence for document logic.",
                downloadUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/model.task",
                format = ModelFormat.MEDIAPIPE_TASK,
                isRecommended = true,
                isReasoningModel = true
            ),
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
                id = "qwen-2.5-1.5b-chat",
                name = "Qwen 2.5 1.5B Chat",
                provider = "Alibaba",
                size = "1.1 GB",
                description = "Alibaba's latest Qwen series. Small yet powerful for multilingual document chat and code understanding.",
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Chat-GGUF/resolve/main/qwen2.5-1.5b-chat-q4_k_m.task",
                format = ModelFormat.MEDIAPIPE_TASK
            ),
            LlmModel(
                id = "phi-3.5-mini-instruct",
                name = "Phi-3.5 Mini",
                provider = "Microsoft",
                size = "2.2 GB",
                description = "Microsoft's tiny but mighty 3.8B parameter model with huge context window. Excellent for long contracts.",
                downloadUrl = "https://huggingface.co/microsoft/Phi-3.5-mini-instruct/resolve/main/phi-3.5-mini-instruct-q4.task",
                format = ModelFormat.MEDIAPIPE_TASK,
                isRecommended = true
            ),
            LlmModel(
                id = "glm-4-9b-chat",
                name = "GLM-4 9B Chat",
                provider = "Zhipu AI",
                size = "5.2 GB",
                description = "High-parameter bilingual model. Requires premium hardware (8GB+ RAM).",
                downloadUrl = "https://example.com/glm-4-9b.task",
                format = ModelFormat.MEDIAPIPE_TASK
            )
        )
    )
    val availableModels: StateFlow<List<LlmModel>> = _availableModels.asStateFlow()

    fun updateDownloadStatus(modelId: String, status: DownloadStatus) {
        _availableModels.update { list ->
            list.map { if (it.id == modelId) it.copy(downloadStatus = status) else it }
        }
    }
}
