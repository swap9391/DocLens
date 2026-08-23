package com.lorem.docklens.ai

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

interface InferenceManager {
    val isModelLoaded: Boolean
    val currentModelId: String?
    
    fun loadModel(modelPath: String, modelId: String, useGpu: Boolean): Result<Unit>
    fun generateResponse(prompt: String, bitmap: Bitmap? = null): Flow<InferenceStep>
    fun stopGeneration()
    fun unloadModel()
    fun clearSession()
}

sealed class InferenceStep {
    object Started : InferenceStep()
    data class PartialReasoning(val text: String) : InferenceStep()
    data class PartialResponse(val text: String) : InferenceStep()
    data class FullResponse(val response: String, val reasoning: String?) : InferenceStep()
    data class Error(val message: String) : InferenceStep()
}
