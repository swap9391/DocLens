package com.lorem.docklens.ui.screens.ai

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lorem.docklens.ai.ModelLoadCoordinator
import com.lorem.docklens.ai.ModelLoadState
import com.lorem.docklens.data.DeviceStorage
import com.lorem.docklens.data.DownloadStatus
import com.lorem.docklens.data.HFRemoteModelGroup
import com.lorem.docklens.data.HuggingFaceAuth
import com.lorem.docklens.data.LlmModel
import com.lorem.docklens.data.ModelDownloadWorker
import com.lorem.docklens.data.ModelRepository
import com.lorem.docklens.data.UserPreferencesRepository
import com.lorem.docklens.data.WeightCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


enum class ModelTab(val title: String) {
    EXPLORE("Explore"),
    MY_MODELS("My Models")
}

/** Internal-storage snapshot, refreshed whenever a download starts or ends. */
data class StorageInfo(
    val usableBytes: Long = 0L,
    val incompleteBytes: Long = 0L
) {
    val hasIncompleteDownloads: Boolean get() = incompleteBytes > 0L
    val readableUsable: String get() = LlmModel.readableSize(usableBytes)
    val readableIncomplete: String get() = LlmModel.readableSize(incompleteBytes)

    /** Warn before the user discovers the problem 2 GB into a transfer. */
    val isLow: Boolean get() = usableBytes in 1 until LOW_STORAGE_THRESHOLD

    private companion object {
        const val LOW_STORAGE_THRESHOLD = 3L * 1024 * 1024 * 1024
    }
}

data class ModelSelectionUiState(
    val tab: ModelTab = ModelTab.EXPLORE,
    val searchQuery: String = "",
    val recommendedModels: List<LlmModel> = emptyList(),
    val remoteGroups: List<HFRemoteModelGroup> = emptyList(),
    val searchResults: List<LlmModel> = emptyList(),
    val installedModels: List<LlmModel> = emptyList(),
    val selectedGroup: HFRemoteModelGroup? = null,
    val groupModels: List<LlmModel> = emptyList(),
    val isLoadingGroup: Boolean = false,
    val useGpu: Boolean = false,
    val hasToken: Boolean = false,
    val selectedModelDetails: LlmModel? = null,
    val activeModelId: String? = null,
    val modelLoadState: ModelLoadState = ModelLoadState.NoModel,
    val isRefreshing: Boolean = false,
    val catalogError: String? = null,
    val storage: StorageInfo = StorageInfo(),
    val userMessage: String? = null
) {
    val hasAnyModelInstalled: Boolean get() = installedModels.isNotEmpty()
}

class ModelSelectionViewModel(
    private val repository: ModelRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val modelLoadCoordinator: ModelLoadCoordinator,
    context: Context
) : ViewModel() {

    private companion object {
        const val TAG_PREFIX = "download_"
    }

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    private val _tab = MutableStateFlow(ModelTab.EXPLORE)
    private val _searchQuery = MutableStateFlow("")
    private val _selectedGroupId = MutableStateFlow<String?>(null)
    private val _selectedModelDetails = MutableStateFlow<LlmModel?>(null)
    private val _loadingGroupId = MutableStateFlow<String?>(null)
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _storage = MutableStateFlow(StorageInfo())

    /** Ids the user explicitly paused, so a WorkManager cancel is not read as "stopped". */
    private val pausedIds = MutableStateFlow<Set<String>>(emptySet())

    private data class CatalogSlice(
        val models: List<LlmModel>,
        val groups: List<HFRemoteModelGroup>,
        val details: Map<String, HFRemoteModelGroup>,
        val installed: List<LlmModel>
    )

    private data class NavigationSlice(
        val tab: ModelTab,
        val query: String,
        val selectedGroupId: String?,
        val details: LlmModel?,
        val loadingGroupId: String?
    )

    private data class SettingsSlice(
        val useGpu: Boolean,
        val hasToken: Boolean,
        val isRefreshing: Boolean,
        val catalogError: String?,
        val loadState: ModelLoadState
    )

    private val catalogSlice = combine(
        repository.availableModels,
        repository.remoteGroups,
        repository.groupDetails,
        repository.installedModels
    ) { models, groups, details, installed ->
        CatalogSlice(models, groups, details, installed)
    }

    private val navigationSlice = combine(
        _tab,
        _searchQuery,
        _selectedGroupId,
        _selectedModelDetails,
        _loadingGroupId
    ) { tab, query, groupId, details, loadingGroupId ->
        NavigationSlice(tab, query, groupId, details, loadingGroupId)
    }

    private val settingsSlice = combine(
        preferencesRepository.useGpu,
        preferencesRepository.huggingFaceToken,
        repository.isRefreshing,
        repository.catalogError,
        modelLoadCoordinator.state
    ) { useGpu, token, refreshing, error, loadState ->
        SettingsSlice(useGpu, !token.isNullOrBlank(), refreshing, error, loadState)
    }

    val uiState: StateFlow<ModelSelectionUiState> = combine(
        catalogSlice,
        navigationSlice,
        settingsSlice,
        _storage,
        _userMessage
    ) { catalog, navigation, settings, storage, message ->
        val byId = catalog.models.associateBy { it.id }
        val selectedGroup = navigation.selectedGroupId?.let { id ->
            catalog.details[id] ?: catalog.groups.firstOrNull { it.id == id }
        }

        ModelSelectionUiState(
            tab = navigation.tab,
            searchQuery = navigation.query,
            recommendedModels = catalog.models.filter { it.isRecommended },
            remoteGroups = catalog.groups.filterByQuery(navigation.query),
            searchResults = if (navigation.query.isBlank()) {
                emptyList()
            } else {
                catalog.models.filter { it.matches(navigation.query) }.take(50)
            },
            installedModels = catalog.installed.map { byId[it.id] ?: it },
            selectedGroup = selectedGroup,
            groupModels = selectedGroup?.toLlmModels()?.map { byId[it.id] ?: it }.orEmpty(),
            isLoadingGroup = navigation.loadingGroupId != null &&
                navigation.loadingGroupId == navigation.selectedGroupId,
            useGpu = settings.useGpu,
            hasToken = settings.hasToken,
            selectedModelDetails = navigation.details?.let { byId[it.id] ?: it },
            activeModelId = settings.loadState.activeModel?.id,
            modelLoadState = settings.loadState,
            isRefreshing = settings.isRefreshing,
            catalogError = settings.catalogError,
            storage = storage,
            userMessage = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelSelectionUiState())

    init {
        refreshModels()
        refreshStorage()
        observeDownloadWork()
    }

    /** Recomputes free space and the size of any incomplete downloads. */
    private fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) {
                StorageInfo(
                    usableBytes = DeviceStorage.availableBytes(appContext),
                    incompleteBytes = repository.partialDownloadBytes()
                )
            }
        }
    }

    // ------------------------------------------------------------------ catalog

    fun refreshModels() {
        viewModelScope.launch { repository.fetchRemoteModels() }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onTabSelected(tab: ModelTab) {
        _tab.value = tab
    }

    fun selectGroup(group: HFRemoteModelGroup?) {
        _selectedGroupId.value = group?.id
        val repoId = group?.id ?: return
        viewModelScope.launch {
            _loadingGroupId.value = repoId
            repository.loadGroupDetails(repoId)
            _loadingGroupId.value = null
        }
    }

    fun showModelDetails(model: LlmModel?) {
        _selectedModelDetails.value = model
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }

    // ----------------------------------------------------------------- settings

    fun onToggleGpu(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setUseGpu(enabled) }
    }

    fun saveHuggingFaceToken(token: String?) {
        viewModelScope.launch {
            preferencesRepository.setHuggingFaceToken(token)
            HuggingFaceAuth.update(token)
            _userMessage.value = if (token.isNullOrBlank()) {
                "Hugging Face token removed."
            } else {
                "Hugging Face token saved. Gated models can now be downloaded."
            }
            repository.fetchRemoteModels()
        }
    }

    fun selectModel(model: LlmModel) {
        viewModelScope.launch {
            if (!repository.isAlreadyInstalled(model)) {
                _userMessage.value = "Download ${model.name} before selecting it."
                return@launch
            }
            preferencesRepository.setSelectedModelId(model.id)
            _userMessage.value = "${model.name} is now the active model."
        }
    }

    fun retryModelLoad() = modelLoadCoordinator.retry()

    // ---------------------------------------------------------------- downloads

    fun downloadModel(model: LlmModel) {
        viewModelScope.launch {
            if (!model.isRunnable) {
                _userMessage.value = "Only '.task' bundles can run on this device."
                return@launch
            }

            // Already on disk: register it instead of downloading gigabytes again.
            if (repository.isAlreadyInstalled(model)) {
                repository.registerInstalledModel(model)
                _userMessage.value = "${model.name} is already downloaded."
                return@launch
            }

            if (model.isGated && !HuggingFaceAuth.hasToken()) {
                repository.updateDownloadStatus(
                    model.id,
                    DownloadStatus.Error(
                        "${model.name} is gated on Hugging Face. Add an access token and accept " +
                            "its licence, or import the .task file manually."
                    )
                )
                return@launch
            }

            pausedIds.update { it - model.id }

            // These files are multiple gigabytes: check up front so the user is not
            // told "no space left" only after several GB of mobile data are spent.
            // The check reclaims caches and unresumable partials before giving up, and
            // it touches the filesystem, so it must not run on the main thread.
            val partFile = ModelDownloadWorker.partFile(appContext, model.storageFileName)
            val spaceProblem = withContext(Dispatchers.IO) {
                DeviceStorage.checkSpaceFor(appContext, model.sizeBytes, partFile)
            }
            if (spaceProblem != null) {
                repository.updateDownloadStatus(model.id, DownloadStatus.Error(spaceProblem))
                _userMessage.value = spaceProblem
                refreshStorage()
                return@launch
            }
            refreshStorage()

            // Fitting the file is only half the requirement: loading it repacks every
            // weight into a cache of roughly the same size. Say so now rather than
            // after gigabytes of mobile data have bought a model that cannot start.
            warnIfWeightCacheWontFit(model)

            repository.updateDownloadStatus(model.id, DownloadStatus.Queued)

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        ModelDownloadWorker.KEY_MODEL_ID to model.id,
                        ModelDownloadWorker.KEY_MODEL_NAME to model.name,
                        ModelDownloadWorker.KEY_DOWNLOAD_URL to model.downloadUrl,
                        ModelDownloadWorker.KEY_STORAGE_FILE_NAME to model.storageFileName,
                        ModelDownloadWorker.KEY_EXPECTED_BYTES to model.sizeBytes,
                        ModelDownloadWorker.KEY_AUTH_TOKEN to HuggingFaceAuth.token
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(ModelDownloadWorker.TAG_DOWNLOAD)
                .addTag("${ModelDownloadWorker.TAG_MODEL_PREFIX}${model.id}")
                .addTag("$TAG_PREFIX${model.id}")
                .build()

            workManager.enqueueUniqueWork(
                "$TAG_PREFIX${model.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    /**
     * Warns when the device can hold the download but not the weight cache built
     * from it. MediaPipe repacks every weight into `cacheDir` on load, so a model
     * that only just fits will download fine and then refuse to start.
     */
    private suspend fun warnIfWeightCacheWontFit(model: LlmModel) {
        if (model.sizeBytes <= 0L) return
        val available = withContext(Dispatchers.IO) { DeviceStorage.availableBytes(appContext) }
        val afterDownload = available - model.sizeBytes
        val cacheBudget = WeightCacheStore.budgetFor(model.sizeBytes)
        if (afterDownload >= cacheBudget) return

        _userMessage.value = "Heads up: ${model.name} will fit, but starting it needs a further " +
            "${LlmModel.readableSize(cacheBudget)} of working space and only " +
            "${LlmModel.readableSize(afterDownload.coerceAtLeast(0L))} will be left. " +
            "Free up space before running it, or pick a smaller quantised build (int4/int8)."
    }

    /** Keeps the partial file so the transfer resumes from where it stopped. */
    fun pauseDownload(model: LlmModel) {
        pausedIds.update { it + model.id }
        workManager.cancelUniqueWork("$TAG_PREFIX${model.id}")
        val progress = (model.downloadStatus as? DownloadStatus.Downloading)?.progress
            ?: repository.partialDownloadProgress(model)
        repository.updateDownloadStatus(model.id, DownloadStatus.Paused(progress))
    }

    /** Discards the partial file entirely. */
    fun stopDownload(model: LlmModel) {
        pausedIds.update { it - model.id }
        workManager.cancelUniqueWork("$TAG_PREFIX${model.id}")
        repository.deletePartialDownload(model)
        repository.clearDownloadStatus(model.id)
        refreshStorage()
    }

    /**
     * Cancels every in-flight download and deletes all `.part` files.
     * The recovery path when internal storage has filled up.
     */
    fun clearIncompleteDownloads() {
        viewModelScope.launch {
            workManager.cancelAllWorkByTag(ModelDownloadWorker.TAG_DOWNLOAD)
            val reclaimed = repository.purgePartialDownloads()
            pausedIds.value = emptySet()
            refreshStorage()
            _userMessage.value = if (reclaimed > 0L) {
                "Freed ${LlmModel.readableSize(reclaimed)} by removing incomplete downloads."
            } else {
                "No incomplete downloads to remove."
            }
        }
    }

    fun deleteModel(model: LlmModel) {
        viewModelScope.launch {
            repository.deleteModel(model)
            repository.deletePartialDownload(model)
            refreshStorage()
            _userMessage.value = "${model.name} deleted."
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            repository.importModelFile(uri)
                .onSuccess { imported ->
                    preferencesRepository.setSelectedModelId(imported.id)
                    _tab.value = ModelTab.MY_MODELS
                    _userMessage.value = "${imported.name} imported and selected."
                }
                .onFailure { error ->
                    _userMessage.value = error.message ?: "Could not import that file."
                }
        }
    }

    /**
     * Re-attaches to downloads started earlier (screen re-entry, process death).
     * Work is identified by its `model:<id>` tag rather than a per-call observer.
     */
    private fun observeDownloadWork() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG_DOWNLOAD).collect { infos ->
                infos.forEach(::applyWorkInfo)
            }
        }
    }

    private fun applyWorkInfo(info: WorkInfo) {
        val modelId = info.tags
            .firstOrNull { it.startsWith(ModelDownloadWorker.TAG_MODEL_PREFIX) }
            ?.removePrefix(ModelDownloadWorker.TAG_MODEL_PREFIX)
            ?: return

        // An installed model always wins over stale historical work records,
        // otherwise an old FAILED entry would mark a working model as broken.
        if (repository.installedModels.value.any { it.id == modelId }) {
            repository.clearDownloadStatus(modelId)
            return
        }

        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                repository.updateDownloadStatus(modelId, DownloadStatus.Queued)

            WorkInfo.State.RUNNING -> {
                val progress = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                repository.updateDownloadStatus(
                    modelId,
                    DownloadStatus.Downloading(
                        progress = progress,
                        downloadedBytes = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                        totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                    )
                )
            }

            WorkInfo.State.SUCCEEDED -> viewModelScope.launch {
                val model = repository.findModelById(modelId)
                if (model != null) {
                    repository.registerInstalledModel(model)
                    // First model on the device becomes the active one automatically.
                    if (repository.installedModels.value.size <= 1) {
                        preferencesRepository.setSelectedModelId(model.id)
                    }
                } else {
                    repository.syncInstalledWithDisk()
                }
                refreshStorage()
            }

            WorkInfo.State.FAILED -> {
                refreshStorage()
                val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                if (error == ModelDownloadWorker.ERROR_PAUSED) {
                    val model = repository.findModelById(modelId)
                    val progress = model?.let { repository.partialDownloadProgress(it) } ?: 0
                    repository.updateDownloadStatus(modelId, DownloadStatus.Paused(progress))
                } else {
                    repository.updateDownloadStatus(
                        modelId,
                        DownloadStatus.Error(error ?: "Download failed. Tap download to retry.")
                    )
                }
            }

            WorkInfo.State.CANCELLED -> {
                if (modelId in pausedIds.value) {
                    val model = repository.findModelById(modelId)
                    val progress = model?.let { repository.partialDownloadProgress(it) } ?: 0
                    repository.updateDownloadStatus(modelId, DownloadStatus.Paused(progress))
                } else {
                    repository.clearDownloadStatus(modelId)
                }
            }
        }
    }
}

private fun LlmModel.matches(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        provider.contains(query, ignoreCase = true) ||
        fileName.contains(query, ignoreCase = true) ||
        repoId?.contains(query, ignoreCase = true) == true

private fun List<HFRemoteModelGroup>.filterByQuery(query: String): List<HFRemoteModelGroup> =
    if (query.isBlank()) this else filter { group ->
        group.displayName.contains(query, ignoreCase = true) ||
            group.id.contains(query, ignoreCase = true) ||
            group.versionFiles.any { it.fileName.contains(query, ignoreCase = true) }
    }

class ModelSelectionViewModelFactory(
    private val repository: ModelRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val modelLoadCoordinator: ModelLoadCoordinator,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelSelectionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ModelSelectionViewModel(
                repository,
                preferencesRepository,
                modelLoadCoordinator,
                context
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
