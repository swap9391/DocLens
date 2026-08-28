package com.lorem.docklens.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale

@JsonClass(generateAdapter = true)
data class HFSibling(
    @Json(name = "rfilename") val fileName: String,
    @Json(name = "size") val size: Long? = null
) {
    fun downloadUrl(modelId: String): String {
        return "https://huggingface.co/$modelId/resolve/main/$fileName?download=true"
    }
}

@JsonClass(generateAdapter = true)
data class HFModel(
    @Json(name = "id") val id: String,
    @Json(name = "downloads") val downloads: Int? = null,
    @Json(name = "likes") val likes: Int? = null,
    @Json(name = "siblings") val siblings: List<HFSibling>? = null
) {
    fun getSupportedFiles(): List<HFSibling> {
        return siblings?.filter { 
            it.fileName.endsWith(".litertlm", ignoreCase = true) || 
            it.fileName.endsWith(".bin", ignoreCase = true) || 
            it.fileName.endsWith(".task", ignoreCase = true) ||
            it.fileName.endsWith(".tflite", ignoreCase = true)
        } ?: emptyList()
    }
}

data class HFRemoteModelGroup(
    val id: String,
    val downloads: Int,
    val likes: Int,
    val versionFiles: List<HFSibling>
) {
    val displayName: String
        get() = id.substringAfter("/")

    fun toLlmModels(): List<LlmModel> {
        return versionFiles.map { file ->
            val format = if (file.fileName.endsWith(".litertlm", ignoreCase = true)) {
                ModelFormat.LITERT_LM
            } else {
                ModelFormat.MEDIAPIPE_TASK
            }
            
            LlmModel(
                id = remoteVersionId(file.fileName),
                name = "${displayName} - ${file.fileName.removeSuffix(".litertlm").removeSuffix(".bin").removeSuffix(".task").removeSuffix(".tflite")}",
                provider = id.substringBefore("/"),
                size = file.size.toReadableSize(),
                description = "Repository: $id\nFile: ${file.fileName}\nDownloads: $downloads • Likes: $likes",
                downloadUrl = file.downloadUrl(id),
                format = format,
                isRecommended = false
            )
        }
    }

    private fun remoteVersionId(fileName: String): String {
        return "hf_${id.replace('/', '_')}_${fileName.replace('.', '_')}"
    }
}

fun HFModel.toRemoteGroup(): HFRemoteModelGroup? {
    val files = getSupportedFiles()
    if (files.isEmpty()) return null
    return HFRemoteModelGroup(
        id = id,
        downloads = downloads ?: 0,
        likes = likes ?: 0,
        versionFiles = files
    )
}

private fun Long?.toReadableSize(): String {
    val bytes = this ?: return "Unknown size"
    if (bytes <= 0L) return "Unknown size"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}

interface HFApi {
    @GET("api/models")
    suspend fun fetchLiteRTModels(
        @Query("author") author: String,
        @Query("expand[]") expand: String = "siblings",
        @Query("limit") limit: Int = 100,
        @Query("sort") sort: String = "downloads",
        @Query("direction") direction: String = "-1"
    ): List<HFModel>
}

object RetrofitClient {
    private const val BASE_URL = "https://huggingface.co/"
    val api: HFApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(HFApi::class.java)
    }
}

class HuggingFaceModelsRepository(
    private val api: HFApi = RetrofitClient.api
) {
    suspend fun fetchRemoteLiteRtModels(): List<HFRemoteModelGroup> {
        return try {
            api.fetchLiteRTModels(author = "litert-community")
                .mapNotNull { it.toRemoteGroup() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
