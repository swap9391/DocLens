package com.lorem.docklens.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Legacy single-model downloader from the first prototype.
 *
 * It is unreachable from the navigation graph and hard-codes a `.bin` Gemma model
 * that the current MediaPipe runtime cannot load. Model download, storage and
 * loading now live in [com.lorem.docklens.data.ModelRepository],
 * [com.lorem.docklens.data.ModelDownloadWorker] and
 * [com.lorem.docklens.ai.ModelLoadCoordinator].
 *
 * Kept only so the old `AiSetupScreen` still compiles; safe to delete along with
 * `AiSetupScreen.kt` and `AiSetupViewModel.kt`.
 */
@Deprecated(
    message = "Replaced by ModelRepository + ModelDownloadWorker + ModelLoadCoordinator.",
    level = DeprecationLevel.WARNING
)
class AiModelManager(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val modelFileName = "gemma-1.1-2b-it-cpu-int4.bin"
    private val modelFile: File by lazy {
        File(context.filesDir, modelFileName)
    }

    private val okHttpClient = OkHttpClient.Builder().build()

    fun isModelDownloaded(): Boolean = modelFile.exists()

    suspend fun downloadModel(
        url: String,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            
            body.byteStream().use { input ->
                FileOutputStream(modelFile).use { output ->
                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        if (contentLength > 0) {
                            onProgress((total * 100 / contentLength).toInt())
                        }
                        output.write(data, 0, count)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AiModelManager", "Download failed", e)
            if (modelFile.exists()) modelFile.delete() // Clean up partial download
            Result.failure(e)
        }
    }

    fun initInference(): Result<Unit> {
        if (!isModelDownloaded()) {
            return Result.failure(IllegalStateException("Model not downloaded"))
        }

        return try {
            close()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AiModelManager", "Inference init failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateResponse(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        val inference = llmInference ?: return@withContext Result.failure(IllegalStateException("AI Engine not initialized"))
        
        try {
            val result = inference.generateResponse(prompt)
            Result.success(result)
        } catch (e: Exception) {
            Log.e("AiModelManager", "Generation failed", e)
            Result.failure(e)
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}
