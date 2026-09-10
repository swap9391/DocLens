package com.lorem.docklens.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for model discovery, download state and local installs.
 *
 * Three inputs are merged:
 *  1. [InstalledModelDao] - what is actually on disk (survives restarts and offline use).
 *  2. Hugging Face listings - what can be downloaded.
 *  3. Transient WorkManager progress - what is downloading right now.
 */
class ModelRepository(
    private val context: Context,
    private val installedModelDao: InstalledModelDao,
    scope: CoroutineScope
) {
    private val hfRepository = HuggingFaceModelsRepository()

    private val _remoteGroups = MutableStateFlow<List<HFRemoteModelGroup>>(emptyList())
    val remoteGroups: StateFlow<List<HFRemoteModelGroup>> = _remoteGroups.asStateFlow()

    /** Per-repo detail (full file list + byte sizes), loaded lazily when a repo is opened. */
    private val _groupDetails = MutableStateFlow<Map<String, HFRemoteModelGroup>>(emptyMap())
    val groupDetails: StateFlow<Map<String, HFRemoteModelGroup>> = _groupDetails.asStateFlow()

    private val _recommendedModels = MutableStateFlow<List<LlmModel>>(emptyList())

    /** Live download progress keyed by model id. Not persisted on purpose. */
    private val _transientStatus = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /**
     * False until the on-disk scan has finished. Routing waits for this so a user
     * who already has models is never bounced to the setup screen on cold start.
     */
    private val _initialScanComplete = MutableStateFlow(false)
    val initialScanComplete: StateFlow<Boolean> = _initialScanComplete.asStateFlow()

    /** Models present on disk. Always available, even with no network. */
    val installedModels: StateFlow<List<LlmModel>> = installedModelDao.observeAll()
        .map { entities -> entities.map { it.toLlmModel() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val availableModels: StateFlow<List<LlmModel>> = combine(
        _recommendedModels,
        _remoteGroups,
        _groupDetails,
        installedModels,
        _transientStatus
    ) { recommended, groups, details, installed, transient ->
        buildModelList(recommended, groups, details, installed, transient)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            deleteUnsupportedLegacyModels()
            reclaimStalePartialDownloads()
            adoptOrphanModelFiles()
            syncInstalledWithDisk()
            _initialScanComplete.value = true
        }
    }

    // ---------------------------------------------------------------- discovery

    /** Refreshes the Hugging Face catalog and resolves the curated recommendations. */
    suspend fun fetchRemoteModels() {
        _isRefreshing.value = true
        try {
            val result = hfRepository.fetchModelGroups()
            val groups = result.getOrElse { error ->
                _catalogError.value = error.message
                emptyList()
            }

            if (groups.isNotEmpty()) _catalogError.value = null

            val byId = groups.associateBy { it.id.lowercase() }

            // Curated repos that the listing endpoint did not return (mirrors, gated
            // repos, renamed orgs) are fetched individually so recommendations never
            // silently disappear.
            val missing = ModelCatalog.repoIds.filter { byId[it.lowercase()] == null }
            val extra = fetchDetailsFor(missing)

            val allGroups = (groups + extra).distinctBy { it.id }
            _remoteGroups.value = allGroups
                .sortedWith(compareByDescending<HFRemoteModelGroup> { it.downloads }.thenBy { it.displayName })

            _recommendedModels.value = allGroups.mapNotNull { group ->
                ModelCatalog.findFor(group.id)?.let { ModelCatalog.toRecommendedModel(it, group) }
            }

            syncInstalledWithDisk()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh remote models", e)
            _catalogError.value = e.toFriendlyMessage()
        } finally {
            _isRefreshing.value = false
        }
    }

    private suspend fun fetchDetailsFor(repoIds: List<String>): List<HFRemoteModelGroup> {
        if (repoIds.isEmpty()) return emptyList()
        return coroutineScope {
            repoIds.map { repoId -> async { hfRepository.fetchRepoDetail(repoId).getOrNull() } }
                .awaitAll()
                .filterNotNull()
        }
    }

    /**
     * Loads the complete file list (with byte sizes) for one repository.
     * Listings only carry partial sibling data, so this runs when a repo is opened.
     */
    suspend fun loadGroupDetails(repoId: String) {
        if (_groupDetails.value.containsKey(repoId)) return
        hfRepository.fetchRepoDetail(repoId)
            .onSuccess { detail -> _groupDetails.update { it + (repoId to detail) } }
            .onFailure { Log.w(TAG, "Could not load detail for $repoId", it) }
    }


    fun getLocalModelFile(model: LlmModel): File = File(context.filesDir, model.storageFileName)

    fun findModelById(modelId: String): LlmModel? = availableModels.value.find { it.id == modelId }

    fun updateDownloadStatus(modelId: String, status: DownloadStatus) {
        _transientStatus.update { it + (modelId to status) }
    }

    fun clearDownloadStatus(modelId: String) {
        _transientStatus.update { it - modelId }
    }

    /** True when a valid, complete file for [model] is already on disk. */
    fun isAlreadyInstalled(model: LlmModel): Boolean {
        val file = getLocalModelFile(model)
        return file.exists() && ModelFileValidator.validateForInference(file).isSuccess
    }

    /** Records a finished download so it is never downloaded twice. */
    suspend fun registerInstalledModel(model: LlmModel) {
        val file = getLocalModelFile(model)
        val validation = ModelFileValidator.validateForInference(file)
        if (validation.isFailure) {
            Log.w(TAG, "Refusing to register ${model.id}: ${validation.exceptionOrNull()?.message}")
            updateDownloadStatus(
                model.id,
                DownloadStatus.Error(validation.exceptionOrNull()?.message ?: "Model file is invalid.")
            )
            return
        }
        installedModelDao.upsert(InstalledModelEntity.from(model, file.length()))
        clearDownloadStatus(model.id)
    }

    suspend fun deleteModel(model: LlmModel) {
        withContext(Dispatchers.IO) {
            val file = getLocalModelFile(model)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete ${file.absolutePath}")
            }
        }
        installedModelDao.deleteById(model.id)
        clearDownloadStatus(model.id)
    }

    /**
     * Removes the partially downloaded `.part` file. Called when the user stops a
     * download (as opposed to pausing it, where the partial file must survive so
     * the transfer can resume from where it left off).
     */
    fun deletePartialDownload(model: LlmModel) {
        val part = ModelDownloadWorker.partFile(context, model.storageFileName)
        if (part.exists() && !part.delete()) {
            Log.w(TAG, "Could not delete partial download ${part.absolutePath}")
        }
    }

    /** Progress of a paused download, 0-100. */
    fun partialDownloadProgress(model: LlmModel): Int {
        val part = ModelDownloadWorker.partFile(context, model.storageFileName)
        if (!part.exists() || model.sizeBytes <= 0L) return 0
        return ((part.length() * 100) / model.sizeBytes).toInt().coerceIn(0, 99)
    }

    /** Every incomplete download currently occupying internal storage. */
    fun partialDownloadFiles(): List<File> =
        context.filesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
            .orEmpty()

    /** Total bytes tied up in incomplete downloads. */
    fun partialDownloadBytes(): Long = partialDownloadFiles().sumOf { it.length() }

    /**
     * Deletes every incomplete download. This is the "give me my storage back"
     * escape hatch for a device that filled up mid-transfer.
     */
    suspend fun purgePartialDownloads(): Long = withContext(Dispatchers.IO) {
        var reclaimed = 0L
        partialDownloadFiles().forEach { part ->
            val size = part.length()
            if (part.delete()) {
                reclaimed += size
                Log.i(TAG, "Deleted partial download ${part.name} (${LlmModel.readableSize(size)})")
            }
        }
        reclaimed
    }

    /**
     * Reclaims `.part` files that can no longer be resumed on startup.
     *
     * A partial file is kept when the user paused deliberately, but two cases only
     * waste space: the model finished downloading anyway, and leftovers from a run
     * that died long ago (a crash, or an out-of-space failure in an older build).
     * Multi-gigabyte orphans are exactly what pushes internal storage to 100%.
     */
    private suspend fun reclaimStalePartialDownloads() {
        withContext(Dispatchers.IO) {
            val staleBefore = System.currentTimeMillis() - STALE_PART_AGE_MS
            var reclaimed = 0L

            partialDownloadFiles().forEach { part ->
                val completed = File(context.filesDir, part.name.removeSuffix(PART_SUFFIX))
                val supersededByCompletedFile = completed.exists()
                val abandoned = part.lastModified() in 1 until staleBefore

                if (supersededByCompletedFile || abandoned) {
                    val size = part.length()
                    if (part.delete()) {
                        reclaimed += size
                        val why = if (supersededByCompletedFile) "already downloaded" else "abandoned"
                        Log.i(TAG, "Reclaimed $why partial ${part.name} (${LlmModel.readableSize(size)})")
                    }
                }
            }

            if (reclaimed > 0L) {
                Log.i(TAG, "Freed ${LlmModel.readableSize(reclaimed)} of incomplete downloads")
            }
        }
    }

    /**
     * Copies a user-provided `.task` bundle into app storage. This is the escape
     * hatch for gated repos: download on a desktop, then import here.
     */
    suspend fun importModelFile(uri: Uri): Result<LlmModel> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "imported-model.task"
            if (!displayName.endsWith(".task", ignoreCase = true)) {
                throw IllegalArgumentException("Only '.task' bundles can be imported.")
            }

            val storageName = LlmModel.storageFileName("imported", displayName)
            val target = File(context.filesDir, storageName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not read the selected file.")

            ModelFileValidator.validateForInference(target).getOrElse { error ->
                target.delete()
                throw error
            }

            val model = LlmModel(
                id = LlmModel.modelId("imported", displayName),
                name = displayName.removeSuffix(".task"),
                provider = "Imported",
                size = LlmModel.readableSize(target.length()),
                description = "Imported from device storage.",
                downloadUrl = "",
                format = ModelFormat.MEDIAPIPE_TASK,
                fileName = displayName,
                repoId = "imported",
                sizeBytes = target.length(),
                downloadStatus = DownloadStatus.Downloaded
            )
            installedModelDao.upsert(InstalledModelEntity.from(model, target.length()))
            model
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }

    /** Drops registry rows whose file was deleted or corrupted outside the app. */
    suspend fun syncInstalledWithDisk() {
        withContext(Dispatchers.IO) {
            installedModelDao.getAll().forEach { entity ->
                val file = File(context.filesDir, entity.storageFileName)
                if (ModelFileValidator.validateForInference(file).isFailure) {
                    Log.i(TAG, "Removing stale registry entry ${entity.id}")
                    if (file.exists()) file.delete()
                    installedModelDao.deleteById(entity.id)
                }
            }
        }
    }

    /**
     * Registers `.task` files that exist on disk but are not in the registry.
     * Covers models downloaded by earlier builds, which used unscoped file names.
     */
    private suspend fun adoptOrphanModelFiles() {
        withContext(Dispatchers.IO) {
            val known = installedModelDao.getAll().map { it.storageFileName }.toSet()
            context.filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".task", ignoreCase = true) }
                ?.filter { it.name !in known }
                ?.filter { ModelFileValidator.validateForInference(it).isSuccess }
                ?.forEach { file ->
                    val label = file.name.substringAfterLast("__").removeSuffix(".task")
                    Log.i(TAG, "Adopting existing model file ${file.name}")
                    installedModelDao.upsert(
                        InstalledModelEntity(
                            id = LlmModel.modelId("local", file.name),
                            name = label,
                            provider = "On device",
                            description = "Found in app storage.",
                            repoId = null,
                            fileName = file.name,
                            storageFileName = file.name,
                            downloadUrl = "",
                            format = ModelFormat.MEDIAPIPE_TASK.name,
                            sizeBytes = file.length(),
                            isReasoningModel = file.name.contains("deepseek", ignoreCase = true) ||
                                file.name.contains("r1", ignoreCase = true)
                        )
                    )
                }
        }
    }

    /** Legacy `.bin` models crash the native runtime; remove them and reclaim storage. */
    private fun deleteUnsupportedLegacyModels() {
        try {
            context.filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".bin", ignoreCase = true) }
                ?.forEach { stale ->
                    if (stale.delete()) Log.i(TAG, "Removed unsupported legacy model: ${stale.name}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to clean up legacy model files", e)
        }
    }

    // ------------------------------------------------------------------ merging

    private fun buildModelList(
        recommended: List<LlmModel>,
        groups: List<HFRemoteModelGroup>,
        details: Map<String, HFRemoteModelGroup>,
        installed: List<LlmModel>,
        transient: Map<String, DownloadStatus>
    ): List<LlmModel> {
        val merged = LinkedHashMap<String, LlmModel>()

        recommended.forEach { merged[it.id] = it }

        groups.forEach { group ->
            (details[group.id] ?: group).toLlmModels().forEach { model ->
                val existing = merged[model.id]
                if (existing == null) {
                    merged[model.id] = model
                } else if (existing.sizeBytes == 0L && model.sizeBytes > 0L) {
                    // Keep curated copy but adopt the size we just learned about.
                    merged[model.id] = existing.copy(size = model.size, sizeBytes = model.sizeBytes)
                }
            }
        }

        installed.forEach { installedModel ->
            val existing = merged[installedModel.id]
            merged[installedModel.id] = existing?.copy(
                downloadStatus = DownloadStatus.Downloaded,
                sizeBytes = installedModel.sizeBytes,
                size = installedModel.size
            ) ?: installedModel
        }

        transient.forEach { (id, status) ->
            merged[id]?.let { merged[id] = it.copy(downloadStatus = status) }
        }

        return merged.values.toList()
    }

    private companion object {
        const val TAG = "ModelRepository"

        const val PART_SUFFIX = ".part"

        /** A `.part` untouched for this long is treated as abandoned, not paused. */
        const val STALE_PART_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
