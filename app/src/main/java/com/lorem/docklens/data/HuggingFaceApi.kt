package com.lorem.docklens.data

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Holds the Hugging Face access token for the whole process.
 *
 * The token lives in DataStore (see [UserPreferencesRepository]) but both the
 * Retrofit client and [ModelDownloadWorker] need it synchronously, so it is
 * mirrored here and refreshed whenever the preference changes.
 */
object HuggingFaceAuth {
    @Volatile
    var token: String? = null
        private set

    fun update(newToken: String?) {
        token = newToken?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun hasToken(): Boolean = !token.isNullOrBlank()

    fun authorizationHeader(): String? = token?.let { "Bearer $it" }
}

internal class HuggingFaceAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val header = HuggingFaceAuth.authorizationHeader()
            ?: return chain.proceed(chain.request())
        val authorized = chain.request().newBuilder()
            .header("Authorization", header)
            .build()
        return chain.proceed(authorized)
    }
}

@JsonClass(generateAdapter = true)
data class HFSibling(
    @Json(name = "rfilename") val fileName: String,
    @Json(name = "size") val size: Long? = null
) {
    fun downloadUrl(modelId: String): String =
        "https://huggingface.co/$modelId/resolve/main/$fileName?download=true"
}

@JsonClass(generateAdapter = true)
data class HFModel(
    @Json(name = "id") val id: String,
    @Json(name = "downloads") val downloads: Int? = null,
    @Json(name = "likes") val likes: Int? = null,
    @Json(name = "pipeline_tag") val pipelineTag: String? = null,
    /**
     * Hugging Face returns either `false` or one of `"auto"` / `"manual"` here,
     * so the raw JSON value is kept as [Any] and normalised by [isGated].
     */
    @Json(name = "gated") val gated: Any? = null,
    @Json(name = "siblings") val siblings: List<HFSibling>? = null
) {
    val isGated: Boolean
        get() = when (gated) {
            is Boolean -> gated
            is String -> !gated.equals("false", ignoreCase = true)
            else -> false
        }

    /**
     * `.task` bundles are the only files the bundled MediaPipe runtime can execute.
     * `.litertlm` files are deliberately excluded: downloading a 2 GB file that can
     * never be loaded is worse than not listing it at all.
     */
    fun runnableFiles(): List<HFSibling> =
        siblings.orEmpty().filter { it.fileName.endsWith(".task", ignoreCase = true) }
}

data class HFRemoteModelGroup(
    val id: String,
    val downloads: Int,
    val likes: Int,
    val isGated: Boolean,
    val versionFiles: List<HFSibling>
) {
    val displayName: String get() = id.substringAfter("/")
    val owner: String get() = id.substringBefore("/")

    fun toLlmModels(): List<LlmModel> = versionFiles.map { file ->
        val baseName = file.fileName.removeSuffix(".task")
        LlmModel(
            id = LlmModel.modelId(id, file.fileName),
            name = baseName,
            provider = owner,
            size = LlmModel.readableSize(file.size),
            description = "From $id.\n$downloads downloads • $likes likes" +
                if (isGated) "\n\nGated repository: requires a Hugging Face access token." else "",
            downloadUrl = file.downloadUrl(id),
            format = ModelFormat.fromFileName(file.fileName),
            fileName = file.fileName,
            repoId = id,
            sizeBytes = file.size ?: 0L,
            isGated = isGated,
            isReasoningModel = REASONING_HINTS.any { file.fileName.contains(it, ignoreCase = true) }
        )
    }

    private companion object {
        val REASONING_HINTS = listOf("deepseek", "r1", "qwen3", "reason")
    }
}

fun HFModel.toRemoteGroup(): HFRemoteModelGroup? {
    val files = runnableFiles()
    if (files.isEmpty()) return null
    return HFRemoteModelGroup(
        id = id,
        downloads = downloads ?: 0,
        likes = likes ?: 0,
        isGated = isGated,
        versionFiles = files.distinctBy { it.fileName }
    )
}

interface HFApi {
    /**
     * `full=true` is used instead of `expand[]=siblings` because the `expand[]`
     * form is rejected when combined with `sort`/`direction`, which previously made
     * every listing request fail and left the catalog empty.
     */
    @GET("api/models")
    suspend fun listModels(
        @Query("author") author: String?,
        @Query("search") search: String?,
        @Query("expand") expand: String,
        @Query("limit") limit: Int,
        @Query("sort") sort: String,
        @Query("direction") direction: String
    ): List<HFModel>

    /** Repo detail always includes siblings; `blobs=true` adds per-file byte sizes. */
    @GET("api/models/{repoId}")
    suspend fun modelInfo(
        @Path(value = "repoId", encoded = true) repoId: String,
        @Query("blobs") blobs: Boolean
    ): HFModel
}

object RetrofitClient {
    private const val BASE_URL = "https://huggingface.co/"

    private fun buildApi(client: OkHttpClient): HFApi =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(HFApi::class.java)

    val api: HFApi by lazy { buildApi(HttpClients.api) }

    /**
     * Debug-only twin of [api] that skips certificate validation.
     * `null` in release builds; see [HttpClients.relaxedTls].
     */
    val debugTlsFallbackApi: HFApi? by lazy {
        HttpClients.relaxedTls(HttpClients.api)?.let(::buildApi)
    }
}

class HuggingFaceModelsRepository(
    private val api: HFApi = RetrofitClient.api,
    private val tlsFallbackApi: HFApi? = RetrofitClient.debugTlsFallbackApi
) {
    private companion object {
        const val TAG = "HuggingFaceModelsRepo"

        /** Organisations that publish MediaPipe `.task` bundles. */
        val AUTHORS = listOf("litert-community")

        /** Free-text searches that surface community mirrors outside those orgs. */
        val SEARCHES = listOf("litert", "mediapipe task", "on-device llm")

        /**
         * `full=true` returns every sibling file, which is the only reliable way to
         * know whether a repo ships a `.task` bundle. Limits are kept modest so the
         * combined payload stays reasonable on mobile data.
         */
        const val AUTHOR_LIMIT = 150
        const val SEARCH_LIMIT = 40
    }

    /**
     * Runs [block] against the normal client and, in debug builds only, retries it
     * once with relaxed TLS when the failure was a certificate-trust problem.
     * Without this, a proxy/hotspot that intercepts HTTPS leaves the catalog empty
     * with a misleading "no internet" message.
     */
    private suspend fun <T> withTlsFallback(block: suspend (HFApi) -> T): T =
        try {
            block(api)
        } catch (e: Exception) {
            val fallback = tlsFallbackApi
            if (fallback != null && e.isTlsTrustFailure()) {
                Log.w(TAG, "TLS trust failure; retrying with debug-only relaxed TLS", e)
                block(fallback)
            } else {
                throw e
            }
        }

    /**
     * Fetches every repository that publishes at least one `.task` bundle.
     *
     * All sources are queried in parallel and merged; a single failing source no
     * longer wipes out the whole catalog.
     */
    suspend fun fetchModelGroups(): Result<List<HFRemoteModelGroup>> = coroutineScope {
        val requests = buildList {
            AUTHORS.forEach { author ->
                add(
                    async {
                        runCatching {
                            withTlsFallback { it.listModels(author, null, "siblings", AUTHOR_LIMIT, "downloads", "-1") }
                        }
                    }
                )
            }
            SEARCHES.forEach { search ->
                add(
                    async {
                        runCatching {
                            withTlsFallback { it.listModels(null, search, "siblings", SEARCH_LIMIT, "downloads", "-1") }
                        }
                    }
                )
            }
        }

        val results = requests.awaitAll()
        val models = results.mapNotNull { it.getOrNull() }.flatten()

        if (models.isEmpty()) {
            val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            return@coroutineScope if (failure != null) {
                Log.w(TAG, "Failed to fetch remote model list", failure)
                Result.failure(IllegalStateException(failure.toFriendlyMessage()))
            } else {
                Result.success(emptyList())
            }
        }

        val groups = models
            .distinctBy { it.id }
            .mapNotNull { it.toRemoteGroup() }
            .sortedWith(compareByDescending<HFRemoteModelGroup> { it.downloads }.thenBy { it.displayName })

        Result.success(groups)
    }

    /** Loads full file list + byte sizes for a single repository. */
    suspend fun fetchRepoDetail(repoId: String): Result<HFRemoteModelGroup> = runCatching {
        val info = withTlsFallback { it.modelInfo(repoId, true) }
        info.toRemoteGroup()
            ?: throw IllegalStateException("$repoId does not publish any '.task' model files.")
    }.recoverCatching { throwable ->
        throw IllegalStateException(throwable.toFriendlyMessage(), throwable)
    }
}

/** Turns network/HTTP failures into something worth showing a user. */
internal fun Throwable.toFriendlyMessage(): String = when {
    this is HttpException && code() == 401 ->
        "Hugging Face rejected the request (401). Add a valid access token in AI settings."
    this is HttpException && code() == 403 ->
        "Access denied (403). Accept the model licence on huggingface.co, then retry."
    this is HttpException && code() == 429 ->
        "Hugging Face is rate limiting this device. Try again in a minute."
    this is HttpException -> "Hugging Face request failed (HTTP ${code()})."
    // Must be checked before IOException: SSLHandshakeException *is* an IOException,
    // so a certificate problem would otherwise be reported as "no internet".
    isTlsTrustFailure() -> TLS_TRUST_ERROR
    this is UnknownHostException || this is ConnectException ->
        "No internet connection. Downloaded models still work offline."
    this is SocketTimeoutException ->
        "Hugging Face timed out. Check your connection and retry; downloaded models still work offline."
    this is IOException ->
        "Could not reach Hugging Face (${javaClass.simpleName}). Downloaded models still work offline."
    else -> message ?: "Could not reach Hugging Face."
}
