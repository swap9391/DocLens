package com.lorem.docklens.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the XNNPack weight cache that MediaPipe writes into `cacheDir` while a
 * `.task` model is loaded.
 *
 * Why this exists: loading a model makes TFLite repack every weight tensor into
 * `<cacheDir>/<model file name>.xnnpack_cache`, a file roughly the size of the
 * model itself. When that write fails - almost always because the device filled up
 * during the download that just finished - XNNPack does not return an error:
 *
 * ```
 * E tflite: XNNPack weight cache: cannot append buffer to cache file
 * E tflite: Inserting data in the cache failed.
 * A libc  : Fatal signal 6 (SIGABRT) ... MMapWeightCacheProvider::LookUpOrInsert
 * ```
 *
 * `SIGABRT` kills the process outright; it cannot be caught with `try`/`catch`, so
 * the only defence is to never enter native code unless the cache can be written.
 * Worse, the aborted run leaves a truncated cache behind, so the next launch loads
 * it and dies in the same place - an unrecoverable crash loop on every start.
 *
 * This object therefore handles three things the JNI layer cannot:
 *  1. sizing the cache so free space can be checked *before* `createFromOptions`,
 *  2. deleting truncated caches left by a previous abort (crash-loop recovery),
 *  3. tracking which cache belongs to the live engine so [DeviceStorage] never
 *     deletes a file that is currently memory-mapped.
 */
object WeightCacheStore {

    private const val TAG = "WeightCacheStore"

    /** Suffix TFLite appends to the model file name for its repacked weights. */
    const val CACHE_SUFFIX = ".xnnpack_cache"

    /** Written while a native load is in flight; its survival means the load aborted. */
    private const val MARKER_FILE = "weight_cache_load_in_progress"

    /** Records a cache that a completed load actually produced, so it can be reused. */
    private const val VERIFIED_FILE = "weight_cache_verified"

    /** Slack on top of the cache itself, for the engine's other scratch files. */
    private const val HEADROOM_BYTES = 64L * 1024L * 1024L

    /**
     * Consecutive aborts after which the model is no longer loaded automatically.
     * Two strikes is enough to prove it is not a transient disk-space blip, and
     * stopping here is what turns a boot loop into a message the user can act on.
     */
    private const val MAX_ABORTS = 2

    /** Cache file of the engine that is currently loaded; never safe to delete. */
    @Volatile
    private var inUseCacheName: String? = null

    /** The cache file MediaPipe will create for [modelFile]. */
    fun cacheFileFor(context: Context, modelFile: File): File =
        File(context.cacheDir, modelFile.name + CACHE_SUFFIX)

    /** True when [fileName] must survive a storage reclaim pass. */
    fun isProtected(fileName: String): Boolean =
        fileName == MARKER_FILE || fileName == VERIFIED_FILE || fileName == inUseCacheName

    /**
     * Free bytes needed in `cacheDir` before loading a model of [modelBytes].
     * XNNPack repacks the model's weights, so the cache lands close to the size of
     * the bundle it came from.
     */
    fun budgetFor(modelBytes: Long): Long = modelBytes + HEADROOM_BYTES

    /** Records the cache as good, so the next launch can reuse it instead of rebuilding. */
    fun markLoaded(context: Context, modelFile: File) {
        val cache = cacheFileFor(context, modelFile)
        inUseCacheName = cache.name
        runCatching {
            File(context.cacheDir, VERIFIED_FILE).writeText("${cache.name}\n${cache.length()}")
        }.onFailure { Log.w(TAG, "Could not record verified weight cache", it) }
        clearMarker(context)
    }

    fun markUnloaded() {
        inUseCacheName = null
    }

    /**
     * Deletes every weight cache that cannot be *proved* reusable, and returns the
     * bytes freed.
     *
     * Caches belonging to other models are dead weight. The cache for [modelFile] is
     * kept only when a completed load recorded it at exactly its current size; a
     * truncated file left behind by an abort looks perfectly normal on disk, so
     * "it exists" is not evidence that mapping it again is survivable. Rebuilding
     * costs about a second, which is a good trade against another SIGABRT.
     */
    fun purgeUnusableCaches(context: Context, modelFile: File): Long {
        val keep = cacheFileFor(context, modelFile)
        val verified = readVerified(context)
        var freed = 0L

        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(CACHE_SUFFIX) }
            ?.forEach { cache ->
                if (cache.name == inUseCacheName) return@forEach

                val provenGood = cache.name == keep.name &&
                    verified?.cacheName == cache.name &&
                    verified.sizeBytes == cache.length()
                if (provenGood) return@forEach

                val size = cache.length()
                if (cache.delete()) {
                    freed += size
                    val why = if (cache.name == keep.name) "unverified" else "unused"
                    Log.i(TAG, "Removed $why weight cache ${cache.name} (${LlmModel.readableSize(size)})")
                }
            }

        return freed
    }

    /**
     * True when a cache for [modelFile] survived [purgeUnusableCaches], meaning a
     * completed load produced it and it can be mapped again without rebuilding.
     * Call only after the purge, or the answer is meaningless.
     */
    fun hasReusableCache(context: Context, modelFile: File): Boolean =
        cacheFileFor(context, modelFile).exists()

    /**
     * Records that a native load is starting. Returns the number of times this exact
     * model has already aborted, so the caller can refuse to try a third time.
     */
    fun beginLoad(context: Context, modelFile: File): Int {
        val cacheName = cacheFileFor(context, modelFile).name
        val previous = readMarker(context)
        val aborts = if (previous?.cacheName == cacheName) previous.aborts else 0
        runCatching { File(context.cacheDir, MARKER_FILE).writeText("$cacheName\n${aborts + 1}") }
            .onFailure { Log.w(TAG, "Could not write load marker", it) }
        return aborts
    }

    /** True once a model has aborted the process [MAX_ABORTS] times in a row. */
    fun hasExhaustedRetries(aborts: Int): Boolean = aborts >= MAX_ABORTS

    /** Forgets the abort history so an explicit user retry is allowed to run. */
    fun clearAbortHistory(context: Context) = clearMarker(context)

    private fun clearMarker(context: Context) {
        runCatching {
            val marker = File(context.cacheDir, MARKER_FILE)
            if (marker.exists()) marker.delete()
        }.onFailure { Log.w(TAG, "Could not clear load marker", it) }
    }

    private data class Marker(val cacheName: String, val aborts: Int)

    private fun readMarker(context: Context): Marker? = runCatching {
        val marker = File(context.cacheDir, MARKER_FILE)
        if (!marker.exists()) return@runCatching null
        val lines = marker.readLines()
        val name = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        Marker(name, lines.getOrNull(1)?.toIntOrNull() ?: 1)
    }.getOrNull()

    private data class Verified(val cacheName: String, val sizeBytes: Long)

    private fun readVerified(context: Context): Verified? = runCatching {
        val record = File(context.cacheDir, VERIFIED_FILE)
        if (!record.exists()) return@runCatching null
        val lines = record.readLines()
        val name = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val size = lines.getOrNull(1)?.toLongOrNull() ?: return@runCatching null
        Verified(name, size)
    }.getOrNull()
}

