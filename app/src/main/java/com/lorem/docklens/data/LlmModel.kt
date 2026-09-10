package com.lorem.docklens.data

import java.util.Locale

/**
 * A single, concrete model *file* the user can download and run.
 *
 * One Hugging Face repository ([repoId]) usually publishes several files
 * (different quantisations / context lengths). Each of those becomes one
 * [LlmModel], which is why [id] is derived from repo + file name.
 */
data class LlmModel(
    val id: String,
    val name: String,
    val provider: String,
    val size: String,
    val description: String,
    val downloadUrl: String,
    val format: ModelFormat,
    /** Original file name as published on Hugging Face, e.g. `gemma3-1b-it-int4.task`. */
    val fileName: String,
    /** Owner/repo on Hugging Face, e.g. `litert-community/Gemma3-1B-IT`. */
    val repoId: String? = null,
    val sizeBytes: Long = 0L,
    /**
     * True when the Hugging Face repo requires accepting a licence and sending an
     * access token. Downloading these without a token returns HTTP 401.
     */
    val isGated: Boolean = false,
    val isRecommended: Boolean = false,
    val downloadStatus: DownloadStatus = DownloadStatus.NotDownloaded,
    val supportVision: Boolean = false,
    val isReasoningModel: Boolean = false
) {
    /**
     * Name used on disk. Repo-scoped so that two repositories publishing the same
     * file name (very common on Hugging Face) cannot overwrite each other.
     */
    val storageFileName: String get() = storageFileName(repoId, fileName)

    val isDownloaded: Boolean get() = downloadStatus is DownloadStatus.Downloaded

    /** Only `.task` bundles can be executed by the runtime bundled with this app. */
    val isRunnable: Boolean get() = format == ModelFormat.MEDIAPIPE_TASK

    companion object {
        fun storageFileName(repoId: String?, fileName: String): String {
            val safeFile = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (repoId.isNullOrBlank()) return safeFile
            val safeRepo = repoId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return "${safeRepo}__$safeFile"
        }

        /** Stable id for a repo + file pair. */
        fun modelId(repoId: String, fileName: String): String =
            "hf_${repoId.replace('/', '_')}_${fileName.replace('.', '_').replace('/', '_')}"

        fun readableSize(bytes: Long?): String {
            val value = bytes ?: return "Unknown size"
            if (value <= 0L) return "Unknown size"
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            return when {
                value >= gb -> String.format(Locale.US, "%.2f GB", value / gb)
                value >= mb -> String.format(Locale.US, "%.0f MB", value / mb)
                value >= kb -> String.format(Locale.US, "%.0f KB", value / kb)
                else -> "$value B"
            }
        }
    }
}

enum class ModelFormat {
    /** `.task` bundle loaded by `com.google.mediapipe.tasks.genai`. The only runtime this app ships. */
    MEDIAPIPE_TASK,

    /** `.litertlm` bundle. Not loadable by the bundled MediaPipe runtime. */
    LITERT_LM;

    companion object {
        fun fromFileName(fileName: String): ModelFormat = when {
            fileName.endsWith(".litertlm", ignoreCase = true) -> LITERT_LM
            else -> MEDIAPIPE_TASK
        }
    }
}

sealed class DownloadStatus {
    data object NotDownloaded : DownloadStatus()
    data object Queued : DownloadStatus()
    data class Downloading(
        val progress: Int,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L
    ) : DownloadStatus()
    data class Paused(val progress: Int) : DownloadStatus()
    data object Downloaded : DownloadStatus()
    data class Error(val message: String) : DownloadStatus()
}
