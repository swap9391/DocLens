package com.lorem.docklens.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Persistent record of a model that finished downloading.
 *
 * This is the source of truth for "what is installed on this device". Without it
 * a downloaded model disappears from the UI whenever the Hugging Face listing
 * fails (offline, rate limited, gated), which made models look un-downloaded and
 * forced the user to download them again.
 */
@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val description: String,
    val repoId: String?,
    /** Original Hugging Face file name. */
    val fileName: String,
    /** Repo-scoped name actually used inside `filesDir`. */
    val storageFileName: String,
    val downloadUrl: String,
    val format: String,
    val sizeBytes: Long,
    val isReasoningModel: Boolean,
    val installedAt: Long = System.currentTimeMillis()
) {
    fun toLlmModel(): LlmModel = LlmModel(
        id = id,
        name = name,
        provider = provider,
        size = LlmModel.readableSize(sizeBytes),
        description = description,
        downloadUrl = downloadUrl,
        format = runCatching { ModelFormat.valueOf(format) }.getOrDefault(ModelFormat.MEDIAPIPE_TASK),
        fileName = fileName,
        repoId = repoId,
        sizeBytes = sizeBytes,
        isGated = false,
        downloadStatus = DownloadStatus.Downloaded,
        isReasoningModel = isReasoningModel
    )

    companion object {
        fun from(model: LlmModel, actualSizeBytes: Long): InstalledModelEntity = InstalledModelEntity(
            id = model.id,
            name = model.name,
            provider = model.provider,
            description = model.description,
            repoId = model.repoId,
            fileName = model.fileName,
            storageFileName = model.storageFileName,
            downloadUrl = model.downloadUrl,
            format = model.format.name,
            sizeBytes = if (actualSizeBytes > 0L) actualSizeBytes else model.sizeBytes,
            isReasoningModel = model.isReasoningModel
        )
    }
}

@Dao
interface InstalledModelDao {

    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    suspend fun getAll(): List<InstalledModelEntity>

    @Query("SELECT * FROM installed_models WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InstalledModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE id = :id")
    suspend fun deleteById(id: String)
}

