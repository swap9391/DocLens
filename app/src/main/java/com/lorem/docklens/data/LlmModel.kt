package com.lorem.docklens.data

data class LlmModel(
    val id: String,
    val name: String,
    val provider: String,
    val size: String,
    val description: String,
    val downloadUrl: String,
    val format: ModelFormat,
    val isRecommended: Boolean = false,
    val downloadStatus: DownloadStatus = DownloadStatus.NotDownloaded,
    val supportVision: Boolean = false,
    val isReasoningModel: Boolean = false
)

enum class ModelFormat {
    MEDIAPIPE_TASK,
    LITERT_LM
}

sealed class DownloadStatus {
    object NotDownloaded : DownloadStatus()
    data class Downloading(val progress: Int) : DownloadStatus()
    object Downloaded : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}
