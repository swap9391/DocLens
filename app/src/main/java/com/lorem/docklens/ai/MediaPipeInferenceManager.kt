package com.lorem.docklens.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MediaPipeInferenceManager(private val context: Context) : InferenceManager {
    private var llmInference: LlmInference? = null
    private var _currentModelPath: String? = null
    private var _currentModelId: String? = null
    private var _useGpu: Boolean = false
    private val isGenerating = AtomicBoolean(false)
    
    private var activeResultListener: ((String, Boolean) -> Unit)? = null

    override val isModelLoaded: Boolean
        get() = llmInference != null

    override val currentModelId: String?
        get() = _currentModelId

    override suspend fun loadModel(modelPath: String, modelId: String, useGpu: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (_currentModelPath == modelPath && _useGpu == useGpu && llmInference != null) {
            return@withContext Result.success(Unit)
        }

        return@withContext try {
            unloadModel()
            
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(2048)
                .setResultListener { partialResult, done ->
                    activeResultListener?.invoke(partialResult, done)
                }
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _currentModelPath = modelPath
            _currentModelId = modelId
            _useGpu = useGpu
            Log.d("InferenceManager", "Model loaded successfully: $modelId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("InferenceManager", "Failed to load model: $modelPath", e)
            Result.failure(e)
        }
    }

    override fun generateResponse(prompt: String, bitmap: Bitmap?): Flow<InferenceStep> = callbackFlow {
        val inference = llmInference ?: run {
            trySend(InferenceStep.Error("AI Engine not initialized. Please ensure the model is fully downloaded and try again."))
            close()
            return@callbackFlow
        }

        if (isGenerating.getAndSet(true)) {
            trySend(InferenceStep.Error("Another generation is in progress"))
            close()
            return@callbackFlow
        }

        trySend(InferenceStep.Started)

        var fullResponse = ""
        var reasoningText = ""
        var isReasoning = false
        var buffer = ""

        activeResultListener = { partialResult: String, done: Boolean ->
            buffer += partialResult

            if (!isReasoning && buffer.contains("<think>")) {
                isReasoning = true
                val parts = buffer.split("<think>", limit = 2)
                if (parts[0].isNotEmpty()) {
                    fullResponse += parts[0]
                    trySend(InferenceStep.PartialResponse(parts[0]))
                }
                buffer = parts[1]
            }

            if (isReasoning) {
                if (buffer.contains("</think>")) {
                    val parts = buffer.split("</think>", limit = 2)
                    reasoningText += parts[0]
                    trySend(InferenceStep.PartialReasoning(parts[0]))
                    isReasoning = false
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        fullResponse += parts[1]
                        trySend(InferenceStep.PartialResponse(parts[1]))
                    }
                    buffer = ""
                } else {
                    if (buffer.length > 5 && !buffer.endsWith("<") && !buffer.endsWith("</") && !buffer.endsWith("</t")) {
                        reasoningText += buffer
                        trySend(InferenceStep.PartialReasoning(buffer))
                        buffer = ""
                    }
                }
            } else {
                if (!buffer.endsWith("<") && !buffer.endsWith("<t")) {
                    fullResponse += buffer
                    trySend(InferenceStep.PartialResponse(buffer))
                    buffer = ""
                }
            }

            if (done) {
                if (buffer.isNotEmpty()) {
                    if (isReasoning) reasoningText += buffer else fullResponse += buffer
                }
                trySend(InferenceStep.FullResponse(fullResponse, reasoningText.ifBlank { null }))
                isGenerating.set(false)
                close()
            }
        }

        try {
            inference.generateResponseAsync(prompt)
        } catch (e: Exception) {
            Log.e("InferenceManager", "Generation error", e)
            trySend(InferenceStep.Error(e.message ?: "Inference error"))
            isGenerating.set(false)
            close()
        }

        awaitClose {
            activeResultListener = null
            isGenerating.set(false)
        }
    }

    override fun stopGeneration() {
        isGenerating.set(false)
    }

    override fun unloadModel() {
        llmInference?.close()
        llmInference = null
        _currentModelPath = null
        _currentModelId = null
        isGenerating.set(false)
    }

    override fun clearSession() {}
}
