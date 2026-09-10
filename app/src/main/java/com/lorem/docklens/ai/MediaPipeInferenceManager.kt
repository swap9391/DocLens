package com.lorem.docklens.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.lorem.docklens.data.DeviceStorage
import com.lorem.docklens.data.LlmModel
import com.lorem.docklens.data.ModelFileValidator
import com.lorem.docklens.data.WeightCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class MediaPipeInferenceManager(private val context: Context) : InferenceManager {

    private companion object {
        const val TAG = "InferenceManager"
        const val MAX_TOKENS = 2048
    }

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null
    private var _currentModelId: String? = null
    private var loadedOnGpu: Boolean = false
    private val isGenerating = AtomicBoolean(false)
    private var activeGeneration: Future<*>? = null

    override val isModelLoaded: Boolean
        get() = llmInference != null

    override val currentModelId: String?
        get() = _currentModelId

    override suspend fun loadModel(
        modelPath: String,
        modelId: String,
        useGpu: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentModelPath == modelPath && loadedOnGpu == useGpu && llmInference != null) {
            return@withContext Result.success(Unit)
        }

        // MediaPipe loads the model in native code. An invalid or unsupported file
        // aborts the entire process, so validate before crossing the JNI boundary.
        val validation = ModelFileValidator.validateForInference(File(modelPath))
        if (validation.isFailure) {
            val error = validation.exceptionOrNull()
                ?: IllegalStateException("Model file failed validation.")
            Log.e(TAG, "Refusing to load model $modelId: ${error.message}")
            return@withContext Result.failure(error)
        }

        unloadModel()

        val modelFile = File(modelPath)

        // MediaPipe repacks every weight into <cacheDir>/<model>.xnnpack_cache while
        // loading. If that write runs out of room XNNPack raises SIGABRT instead of
        // returning an error, killing the process from native code where no `catch`
        // can reach it. Everything below therefore has to happen *before* the JNI call.
        prepareWeightCache(modelFile)?.let { blocker ->
            Log.e(TAG, "Refusing to load $modelId: $blocker")
            return@withContext Result.failure(IllegalStateException(blocker))
        }

        // Try the requested backend first. GPU delegates are unavailable on plenty
        // of devices, so fall back to CPU rather than leaving no working engine.
        val attempts: List<LlmInference.Backend> = if (useGpu) {
            listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
        } else {
            listOf(LlmInference.Backend.CPU)
        }

        var lastError: Throwable? = null
        for (backend in attempts) {
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_TOKENS)
                    .setPreferredBackend(backend)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                currentModelPath = modelPath
                _currentModelId = modelId
                loadedOnGpu = backend == LlmInference.Backend.GPU
                // Clears the abort marker and protects this cache from reclaim passes.
                WeightCacheStore.markLoaded(context, modelFile)
                Log.d(TAG, "Model loaded successfully: $modelId on $backend")
                return@withContext Result.success(Unit)
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Failed to load $modelId on $backend", e)
                llmInference = null
            }
        }

        currentModelPath = null
        _currentModelId = null
        // Every backend failed but the process is still alive, so this was an ordinary
        // error, not a native abort. Don't let it count towards the crash-loop limit.
        WeightCacheStore.clearAbortHistory(context)
        Result.failure(
            lastError ?: IllegalStateException("Could not initialise the on-device AI engine.")
        )
    }

    /**
     * Makes the weight cache safe to build, or explains why it is not.
     *
     * Returns `null` when loading may proceed, otherwise a message to show the user.
     * Three hazards are handled, in order:
     *  - a cache truncated by an earlier abort, which would abort again if reused,
     *  - a model that has already killed the process twice, which must not be
     *    retried automatically or the app never finishes starting,
     *  - too little room in `cacheDir` for the repacked weights.
     */
    private fun prepareWeightCache(modelFile: File): String? {
        val freed = WeightCacheStore.purgeUnusableCaches(context, modelFile)
        if (freed > 0L) {
            Log.i(TAG, "Reclaimed ${LlmModel.readableSize(freed)} of stale weight caches")
        }

        val aborts = WeightCacheStore.beginLoad(context, modelFile)
        if (WeightCacheStore.hasExhaustedRetries(aborts)) {
            val label = modelFile.name.substringAfterLast("__").removeSuffix(".task")
            return "$label crashed the AI engine while preparing its weights $aborts times " +
                "in a row, so it is not being loaded again automatically. Free up storage and " +
                "use Retry, or choose a smaller quantised build (int4/int8)."
        }

        // A cache that survived the purge was proven good by a completed load, so it
        // is reused as-is and nothing new has to be written.
        if (WeightCacheStore.hasReusableCache(context, modelFile)) {
            Log.d(TAG, "Reusing verified weight cache for ${modelFile.name}")
            return null
        }

        val required = WeightCacheStore.budgetFor(modelFile.length())
        if (DeviceStorage.availableBytes(context) >= required) return null

        // Last chance: hand back the app's other disposable storage and re-measure.
        DeviceStorage.reclaimDisposableStorage(context)
        if (DeviceStorage.availableBytes(context) >= required) return null

        WeightCacheStore.clearAbortHistory(context)
        return "Not enough storage to start this model. Preparing it for inference needs " +
            "about ${LlmModel.readableSize(required)} of temporary working space but only " +
            "${LlmModel.readableSize(DeviceStorage.availableBytes(context))} is free. " +
            "Delete a model or some files, or use a smaller quantised build (int4/int8)."
    }

    override fun generateResponse(prompt: String, bitmap: Bitmap?): Flow<InferenceStep> = callbackFlow {
        val inference = llmInference ?: run {
            trySend(
                InferenceStep.Error(
                    "No AI model is loaded. Download and select a model in AI settings first."
                )
            )
            close()
            return@callbackFlow
        }

        if (isGenerating.getAndSet(true)) {
            trySend(InferenceStep.Error("Another response is still being generated."))
            close()
            return@callbackFlow
        }

        trySend(InferenceStep.Started)

        // Reasoning models stream `<think>...</think>` blocks. They are split out
        // here so the UI can present them separately from the final answer.
        var fullResponse = ""
        var reasoningText = ""
        var isReasoning = false
        var buffer = ""

        // A per-call listener; the previous shared field leaked state between
        // generations when a flow was cancelled mid-stream.
        val listener = ProgressListener<String> { partialResult, done ->
            buffer += partialResult.orEmpty()

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
                } else if (buffer.length > 5 && !buffer.endsWith("<") &&
                    !buffer.endsWith("</") && !buffer.endsWith("</t")
                ) {
                    reasoningText += buffer
                    trySend(InferenceStep.PartialReasoning(buffer))
                    buffer = ""
                }
            } else if (!buffer.endsWith("<") && !buffer.endsWith("<t")) {
                fullResponse += buffer
                trySend(InferenceStep.PartialResponse(buffer))
                buffer = ""
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
            activeGeneration = inference.generateResponseAsync(prompt, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            trySend(InferenceStep.Error(e.message ?: "Inference error"))
            isGenerating.set(false)
            close()
        }

        awaitClose {
            activeGeneration = null
            isGenerating.set(false)
        }
    }

    override fun stopGeneration() {
        runCatching { activeGeneration?.cancel(true) }
        activeGeneration = null
        isGenerating.set(false)
    }

    override fun unloadModel() {
        stopGeneration()
        runCatching { llmInference?.close() }
            .onFailure { Log.w(TAG, "Error while closing previous engine", it) }
        llmInference = null
        currentModelPath = null
        _currentModelId = null
        // The weight cache is no longer mapped, so reclaim passes may delete it.
        WeightCacheStore.markUnloaded()
    }

    override fun clearSession() {}

    override fun clearLoadFailureHistory() = WeightCacheStore.clearAbortHistory(context)
}
