package com.lorem.docklens.ai

import android.util.Log
import com.lorem.docklens.data.LlmModel
import com.lorem.docklens.data.ModelRepository
import com.lorem.docklens.data.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the AI engine is currently doing, from the point of view of any screen. */
sealed interface ModelLoadState {
    /** Nothing installed yet: the user must download a model first. */
    data object NoModel : ModelLoadState
    data class Loading(val model: LlmModel) : ModelLoadState
    data class Ready(val model: LlmModel, val useGpu: Boolean) : ModelLoadState
    data class Failed(val model: LlmModel?, val message: String) : ModelLoadState

    val activeModel: LlmModel?
        get() = when (this) {
            is Loading -> model
            is Ready -> model
            is Failed -> model
            NoModel -> null
        }
}

/**
 * Owns "which model is loaded into the inference engine".
 *
 * Previously this lived in a `LaunchedEffect` inside `MainActivity`, so screens had
 * no way to know whether loading had succeeded, was in flight, or which model was
 * actually answering. Chat now observes [state] directly, which is what makes the
 * selected model take effect immediately after it is chosen.
 */
class ModelLoadCoordinator(
    private val modelRepository: ModelRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val inferenceManager: InferenceManager,
    scope: CoroutineScope
) {
    private companion object {
        const val TAG = "ModelLoadCoordinator"
    }

    private val _state = MutableStateFlow<ModelLoadState>(ModelLoadState.NoModel)
    val state: StateFlow<ModelLoadState> = _state.asStateFlow()

    private val retryTrigger = MutableStateFlow(0)

    /** Guards against reloading the same model/backend combination repeatedly. */
    private var loadedKey: String? = null
    private var loadJob: Job? = null

    private data class LoadRequest(
        val installed: List<LlmModel>,
        val selectedModelId: String?,
        val useGpu: Boolean
    )

    init {
        scope.launch {
            combine(
                modelRepository.installedModels,
                preferencesRepository.selectedModelId,
                preferencesRepository.useGpu,
                retryTrigger
            ) { installed, selectedId, useGpu, _ ->
                LoadRequest(installed, selectedId, useGpu)
            }.collectLatest { request -> handle(request) }
        }
    }

    fun retry() {
        loadedKey = null
        // An explicit retry is the only thing allowed to lift the block on a model
        // that previously aborted the process while preparing its weights.
        inferenceManager.clearLoadFailureHistory()
        retryTrigger.update { it + 1 }
    }

    private suspend fun handle(request: LoadRequest) {
        if (request.installed.isEmpty()) {
            loadedKey = null
            inferenceManager.unloadModel()
            _state.value = ModelLoadState.NoModel
            return
        }

        // Fall back to any installed model when the stored selection is gone
        // (deleted, or downloaded by a previous install of the app).
        val target = request.installed.firstOrNull { it.id == request.selectedModelId }
            ?: request.installed.first()

        if (target.id != request.selectedModelId) {
            preferencesRepository.setSelectedModelId(target.id)
            return // The preference change re-triggers this flow with the right model.
        }

        val key = "${target.id}|${request.useGpu}"
        if (key == loadedKey && _state.value is ModelLoadState.Ready && inferenceManager.isModelLoaded) {
            return
        }

        loadJob?.cancel()
        _state.value = ModelLoadState.Loading(target)

        val file = modelRepository.getLocalModelFile(target)
        val result = inferenceManager.loadModel(file.absolutePath, target.id, request.useGpu)

        _state.value = result.fold(
            onSuccess = {
                loadedKey = key
                Log.i(TAG, "Active model is now ${target.name}")
                ModelLoadState.Ready(target, request.useGpu)
            },
            onFailure = { error ->
                loadedKey = null
                Log.e(TAG, "Failed to load ${target.id}", error)
                // The file is unusable; drop it so the UI offers a re-download
                // instead of failing forever on a corrupt bundle.
                modelRepository.syncInstalledWithDisk()
                ModelLoadState.Failed(
                    target,
                    error.message ?: "Could not start the on-device AI engine."
                )
            }
        )
    }
}

