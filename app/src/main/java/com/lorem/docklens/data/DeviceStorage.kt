package com.lorem.docklens.data

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Free-space checks for model downloads.
 *
 * Models here are multi-gigabyte files, so "no space left on device" is a routine
 * failure rather than an edge case. Running out of space mid-download is the worst
 * possible outcome: several GB of mobile data are spent, the `.part` file then
 * squats on the storage that is already exhausted, and the user is shown whatever
 * exception happened to be thrown last. These helpers make the check happen
 * *before* the first byte is transferred.
 *
 * Just as important, the check must not *invent* a shortage. Three bugs here made
 * downloads fail on devices that could actually hold the file:
 *
 *  1. A flat 500 MB of "working room" was demanded on top of the model. The
 *     finished file is moved with `renameTo` inside the same filesystem, so a
 *     second copy is never made; half a gigabyte of slack is what turns
 *     "10 MB short" into "510 MB short".
 *  2. Only `StorageManager.getAllocatableBytes` was consulted, and a "no" from it
 *     ended the check. That figure has the platform's low-storage reserve already
 *     deducted, so it is routinely *smaller* than `File.usableSpace`.
 *  3. The app gave up without first handing back storage it owns and does not
 *     need: its own caches and unresumable `.part` files from other models.
 */
object DeviceStorage {

    private const val TAG = "DeviceStorage"

    private const val PART_SUFFIX = ".part"

    /** Bounds for the slack kept free on top of the model itself. */
    private const val MIN_HEADROOM_BYTES = 32L * 1024L * 1024L
    private const val MAX_HEADROOM_BYTES = 128L * 1024L * 1024L

    /** A `.part` untouched for this long is treated as abandoned, not paused. */
    private const val STALE_PART_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Slack kept free on top of the download itself: 2% of the model, clamped to a
     * sane range. Enough to keep the OS out of its low-storage state, small enough
     * that it never becomes the reason a download is refused.
     */
    fun headroomFor(totalBytes: Long): Long =
        (totalBytes / 50L).coerceIn(MIN_HEADROOM_BYTES, MAX_HEADROOM_BYTES)

    /** Bytes currently writable in the app's internal storage. */
    fun usableBytes(context: Context): Long =
        runCatching { context.filesDir.usableSpace }
            .onFailure { Log.w(TAG, "Could not read usable space", it) }
            .getOrDefault(0L)

    /** Bytes the platform is willing to free for this app (includes evictable caches). */
    fun allocatableBytes(context: Context): Long {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return 0L
        return try {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(context.filesDir))
        } catch (e: IOException) {
            Log.w(TAG, "getAllocatableBytes failed", e)
            0L
        }
    }

    /**
     * The realistic amount of space a download can use.
     *
     * `getAllocatableBytes` and `usableSpace` measure different things and either
     * can be the larger of the two, so trusting only one under-reports capacity.
     */
    fun availableBytes(context: Context): Long =
        maxOf(usableBytes(context), allocatableBytes(context))

    /**
     * Reports whether [totalBytes] can be downloaded, reclaiming the app's own
     * disposable storage first when it would otherwise not fit.
     *
     * [partFile] is the in-progress download: its bytes are already on disk, so only
     * the remainder has to be found, and it is never deleted by the reclaim pass.
     * Returns `null` when there is room, or a ready-to-show message when there is not.
     *
     * Touches the filesystem; call it off the main thread.
     */
    fun checkSpaceFor(
        context: Context,
        totalBytes: Long,
        partFile: File? = null,
        allowReclaim: Boolean = true
    ): String? {
        if (totalBytes <= 0L) return null

        val alreadyOnDisk = partFile?.takeIf { it.exists() }?.length() ?: 0L
        val remaining = (totalBytes - alreadyOnDisk).coerceAtLeast(0L)
        val headroom = headroomFor(totalBytes)
        val required = remaining + headroom

        if (fits(context, required)) return null

        // Before telling the user to go uninstall things, give back the storage this
        // app is sitting on but does not need.
        if (allowReclaim) {
            val reclaimed = reclaimDisposableStorage(context, keep = partFile)
            if (reclaimed > 0L) {
                Log.i(TAG, "Reclaimed ${LlmModel.readableSize(reclaimed)}; re-checking free space")
                if (fits(context, required)) return null
            }
        }

        return notEnoughSpaceMessage(
            remaining = remaining,
            headroom = headroom,
            available = availableBytes(context),
            resumableBytes = resumablePartialBytes(context, keep = partFile)
        )
    }

    /**
     * True when [required] bytes can be written, asking the platform to evict cached
     * data on our behalf when it says the space is obtainable.
     */
    private fun fits(context: Context, required: Long): Boolean {
        val storageManager = context.getSystemService(StorageManager::class.java)
        if (storageManager != null) {
            try {
                val uuid = storageManager.getUuidForPath(context.filesDir)
                if (storageManager.getAllocatableBytes(uuid) >= required) {
                    // Reserves the space and evicts cached data belonging to other apps.
                    runCatching { storageManager.allocateBytes(uuid, required) }
                        .onFailure { Log.w(TAG, "allocateBytes failed; continuing anyway", it) }
                    return true
                }
            } catch (e: IOException) {
                Log.w(TAG, "StorageManager space query failed; falling back to usableSpace", e)
            }
        }
        // getAllocatableBytes has the low-storage reserve deducted and is often the
        // more pessimistic figure, so a "no" from it is not the final word.
        return usableBytes(context) >= required
    }

    /**
     * Deletes storage the app can always recreate: its caches, plus `.part` files
     * that can no longer be resumed (superseded by a finished download, or untouched
     * for a week). [keep] - the download currently in flight - is never touched, and
     * neither are recent partials the user may still resume.
     *
     * Returns the number of bytes freed. Blocking; call it off the main thread.
     */
    fun reclaimDisposableStorage(context: Context, keep: File? = null): Long {
        var freed = 0L
        // The live engine memory-maps its XNNPack weight cache; deleting that out
        // from under native code is how you turn a full disk into a SIGABRT.
        freed += deleteContents(context.cacheDir) { WeightCacheStore.isProtected(it.name) }
        freed += runCatching { context.externalCacheDir?.let { deleteContents(it) } ?: 0L }
            .getOrDefault(0L)
        freed += deleteUnresumablePartFiles(context, keep)
        if (freed > 0L) Log.i(TAG, "Freed ${LlmModel.readableSize(freed)} of disposable storage")
        return freed
    }

    /** Bytes held by partial downloads the user could still resume, so we keep them. */
    fun resumablePartialBytes(context: Context, keep: File? = null): Long =
        partFiles(context)
            .filter { keep == null || it.absolutePath != keep.absolutePath }
            .sumOf { it.length() }

    private fun deleteUnresumablePartFiles(context: Context, keep: File?): Long {
        val staleBefore = System.currentTimeMillis() - STALE_PART_AGE_MS
        var freed = 0L
        partFiles(context)
            .filter { keep == null || it.absolutePath != keep.absolutePath }
            .forEach { part ->
                val completed = File(context.filesDir, part.name.removeSuffix(PART_SUFFIX))
                val superseded = completed.exists()
                val abandoned = part.lastModified() in 1 until staleBefore
                if (superseded || abandoned) {
                    val size = part.length()
                    if (part.delete()) {
                        freed += size
                        val why = if (superseded) "already downloaded" else "abandoned"
                        Log.i(TAG, "Reclaimed $why partial ${part.name} (${LlmModel.readableSize(size)})")
                    }
                }
            }
        return freed
    }

    private fun partFiles(context: Context): List<File> =
        runCatching {
            context.filesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
                .orEmpty()
        }.getOrDefault(emptyList())

    /** Recursively empties [dir] without removing [dir] itself. Returns bytes freed. */
    private fun deleteContents(dir: File?, protect: (File) -> Boolean = { false }): Long {
        if (dir == null || !dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            if (protect(child)) return@forEach
            freed += if (child.isDirectory) {
                val inner = deleteContents(child, protect)
                child.delete()
                inner
            } else {
                val size = child.length()
                if (child.delete()) size else 0L
            }
        }
        return freed
    }

    private fun notEnoughSpaceMessage(
        remaining: Long,
        headroom: Long,
        available: Long,
        resumableBytes: Long
    ): String = buildString {
        append("Not enough storage. This model needs ")
        append(LlmModel.readableSize(remaining))
        append(" more (plus ")
        append(LlmModel.readableSize(headroom))
        append(" of working room) but only ")
        append(LlmModel.readableSize(available))
        append(" is free. ")
        if (resumableBytes > 0L) {
            append("Other paused downloads are holding ")
            append(LlmModel.readableSize(resumableBytes))
            append(" — \"Clear incomplete downloads\" gets that back. ")
        }
        append("Otherwise free up space, or pick a smaller quantised build ")
        append("(int4/int8) instead of an f32 one.")
    }
}

/** True when the failure is the filesystem being full (`ENOSPC`). */
internal fun Throwable.isOutOfSpace(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        if (current is android.system.ErrnoException && current.errno == android.system.OsConstants.ENOSPC) {
            return true
        }
        if (current.message?.contains("ENOSPC", ignoreCase = true) == true) return true
        if (current.message?.contains("No space left on device", ignoreCase = true) == true) return true
        current = current.cause
        depth++
    }
    return false
}

