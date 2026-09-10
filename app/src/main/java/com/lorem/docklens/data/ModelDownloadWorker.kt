package com.lorem.docklens.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ModelDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    companion object {
        private const val TAG = "ModelDownloadWorker"

        const val KEY_MODEL_ID = "modelId"
        const val KEY_MODEL_NAME = "modelName"
        const val KEY_DOWNLOAD_URL = "downloadUrl"
        const val KEY_STORAGE_FILE_NAME = "storageFileName"
        const val KEY_AUTH_TOKEN = "authToken"

        /** Catalog-reported size, used for the free-space pre-flight check. */
        const val KEY_EXPECTED_BYTES = "expectedBytes"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloadedBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_ERROR = "error"

        /** Tag applied to every download so running work can be re-attached to the UI. */
        const val TAG_DOWNLOAD = "model_download"

        /** Per-model tag; the id is recovered from it when observing work. */
        const val TAG_MODEL_PREFIX = "model:"

        /** Reported by the worker when the user paused rather than cancelled. */
        const val ERROR_PAUSED = "Download paused"

        /** Partial download for [storageFileName]; kept on pause so it can resume. */
        fun partFile(context: Context, storageFileName: String): File =
            File(context.filesDir, "$storageFileName.part")
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val okHttpClient = HttpClients.download

    /** Debug builds only; `null` in release so a shipped APK can never skip TLS checks. */
    private val debugUnsafeOkHttpClient: OkHttpClient? by lazy { HttpClients.relaxedTls(okHttpClient) }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        return createForegroundInfo(modelName)
    }

    override suspend fun doWork(): Result {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing download URL"))
        val storageFileName = inputData.getString(KEY_STORAGE_FILE_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing model filename"))

        createNotificationChannel()
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "Could not set foreground info", it) }

        val destFile = File(applicationContext.filesDir, storageFileName)
        val partFile = partFile(applicationContext, storageFileName)

        // Already downloaded and valid: never pay for the same bytes twice.
        if (ModelFileValidator.validateForInference(destFile).isSuccess) {
            Log.i(TAG, "$storageFileName already present, skipping download")
            return Result.success()
        }

        // Fail before the first byte rather than after several GB of mobile data.
        // The check reclaims the app's own caches and unresumable partials first, so
        // it only says "no" when the device genuinely cannot hold the file.
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, 0L)
        withContext(Dispatchers.IO) {
            DeviceStorage.checkSpaceFor(applicationContext, expectedBytes, partFile)
        }?.let { message ->
            Log.e(TAG, "Refusing to start download: $message")
            return Result.failure(workDataOf(KEY_ERROR to message))
        }

        return try {
            Log.d(TAG, "Starting download from: $downloadUrl")
            downloadToFile(downloadUrl, partFile)
            promotePartFile(partFile, destFile)
        } catch (_: DownloadCancelledException) {
            Log.i(TAG, "Download stopped; partial file kept for resume")
            Result.failure(workDataOf(KEY_ERROR to ERROR_PAUSED))
        } catch (e: Exception) {
            // Checked before the generic branch: TLS failures are IOExceptions too,
            // and reporting them as a plain network error hides the real cause.
            if (e.isTlsTrustFailure()) {
                return handleTlsFailure(e, downloadUrl, partFile, destFile)
            }
            failureFor(e, partFile)
        }
    }

    /**
     * Maps a download exception to a WorkManager failure, cleaning up the partial
     * file when keeping it cannot help.
     */
    private fun failureFor(e: Exception, partFile: File): Result {
        if (e.isOutOfSpace()) {
            Log.e(TAG, "Ran out of storage while downloading model", e)
            // The partial file is now occupying the storage that is already full,
            // and it can never be completed on this device. Reclaim it immediately.
            val reclaimed = if (partFile.exists()) partFile.length() else 0L
            if (partFile.exists() && partFile.delete()) {
                Log.i(TAG, "Deleted incomplete download, reclaimed ${LlmModel.readableSize(reclaimed)}")
            }
            return Result.failure(workDataOf(KEY_ERROR to outOfSpaceMessage(reclaimed)))
        }

        Log.e(TAG, "Error downloading model", e)
        // A partial file is only useful if the server supports resuming; an
        // invalid payload (HTML error page) must not be kept around.
        if (e is UnrecoverableDownloadException && partFile.exists()) partFile.delete()
        return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown download error")))
    }

    private fun outOfSpaceMessage(reclaimedBytes: Long): String = buildString {
        append("Ran out of storage while downloading. ")
        if (reclaimedBytes > 0L) {
            append("The incomplete file (")
            append(LlmModel.readableSize(reclaimedBytes))
            append(") was deleted to free space. ")
        }
        append("Free up space and try again, or choose a smaller quantised build ")
        append("(int4/int8) — f32 variants are several times larger.")
    }

    /**
     * The certificate chain could not be validated. In debug builds this is retried
     * once with relaxed TLS so a developer behind an intercepting proxy can still
     * download; release builds always fail with actionable guidance.
     */
    private suspend fun handleTlsFailure(
        cause: Exception,
        downloadUrl: String,
        partFile: File,
        destFile: File
    ): Result {
        val fallbackClient = debugUnsafeOkHttpClient
        if (fallbackClient != null) {
            Log.w(TAG, "TLS trust failure; retrying with debug-only relaxed TLS", cause)
            return try {
                downloadToFile(downloadUrl, partFile, fallbackClient)
                promotePartFile(partFile, destFile)
            } catch (_: DownloadCancelledException) {
                Result.failure(workDataOf(KEY_ERROR to ERROR_PAUSED))
            } catch (retry: Exception) {
                Log.e(TAG, "Debug TLS retry also failed", retry)
                // The retry can fail for a completely unrelated reason (disk full,
                // 401, ...). Report *that* instead of blaming TLS a second time.
                if (retry.isTlsTrustFailure()) {
                    Result.failure(workDataOf(KEY_ERROR to TLS_TRUST_ERROR))
                } else {
                    failureFor(retry, partFile)
                }
            }
        }
        Log.e(TAG, "TLS trust failure while downloading model", cause)
        return Result.failure(workDataOf(KEY_ERROR to TLS_TRUST_ERROR))
    }

    private fun promotePartFile(partFile: File, destFile: File): Result {
        if (destFile.exists()) destFile.delete()
        return if (partFile.renameTo(destFile)) {
            Log.d(TAG, "Successfully downloaded model to ${destFile.absolutePath}")
            Result.success()
        } else {
            Log.e(TAG, "Failed to move completed download into place")
            Result.failure(workDataOf(KEY_ERROR to "Could not save the model to app storage."))
        }
    }

    private suspend fun downloadToFile(
        downloadUrl: String,
        partFile: File,
        client: OkHttpClient = okHttpClient
    ) {
        // Every call below is blocking socket/disk work; keep it off the worker's
        // default dispatcher so concurrent coroutines are not starved.
        withContext(Dispatchers.IO) {
            val alreadyDownloaded = if (partFile.exists()) partFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DocLens/1.0")

            // Hugging Face gated repos (Gemma, Llama, ...) answer 401 without this header.
            // The token is passed through input data as well as the in-memory holder so
            // the download still authenticates when WorkManager restarts a fresh process.
            resolveAuthToken()?.let { requestBuilder.header("Authorization", "Bearer $it") }

            if (alreadyDownloaded > 0L) {
                requestBuilder.header("Range", "bytes=$alreadyDownloaded-")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UnrecoverableDownloadException(httpErrorMessage(response.code, downloadUrl))
                }

                // The server ignored our Range header, so restart from byte 0.
                val resuming = alreadyDownloaded > 0L && response.code == 206
                val startOffset = if (resuming) alreadyDownloaded else 0L

                val body = response.body ?: throw UnrecoverableDownloadException("Empty response body")
                val remaining = body.contentLength()
                val totalBytes = if (remaining > 0L) startOffset + remaining else 0L

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("text/") || contentType.contains("json", ignoreCase = true)) {
                    throw UnrecoverableDownloadException(
                        "Hugging Face returned '$contentType' instead of a model file. " +
                            "The model may be gated or the file may have moved."
                    )
                }

                // Content-Length is the authoritative size; the catalog value used for the
                // pre-flight check can be missing or stale. Re-check here - this also frees
                // the app's caches and unresumable partials - rather than streaming
                // gigabytes into a filesystem that cannot hold them.
                DeviceStorage.checkSpaceFor(applicationContext, totalBytes, partFile)?.let { message ->
                    throw InsufficientStorageException(
                        "$message The partly downloaded file is kept so the transfer can " +
                            "resume; use Stop to discard it."
                    )
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile, resuming).use { output ->
                        val data = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var total = startOffset
                        var count: Int
                        var lastProgress = -1

                        while (input.read(data).also { count = it } != -1) {
                            if (isStopped) {
                                output.flush()
                                throw DownloadCancelledException()
                            }

                            output.write(data, 0, count)
                            total += count

                            if (totalBytes > 0) {
                                val progress = ((total * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    setProgress(
                                        workDataOf(
                                            KEY_PROGRESS to progress,
                                            KEY_DOWNLOADED_BYTES to total,
                                            KEY_TOTAL_BYTES to totalBytes
                                        )
                                    )
                                    updateNotification(progress)
                                }
                            }
                        }
                    }
                }

                ModelFileValidator.validateDownloadedFile(partFile, totalBytes).getOrElse {
                    throw UnrecoverableDownloadException(
                        it.message ?: "Downloaded file is not a valid model."
                    )
                }
            }
        }
    }

    private fun httpErrorMessage(code: Int, url: String): String {
        Log.e(TAG, "Download failed: HTTP $code for $url")
        return when (code) {
            401 -> "This model is gated on Hugging Face (401 Unauthorized). Open AI settings, " +
                "add a Hugging Face access token, and accept the model licence on huggingface.co."
            403 -> "Access to this model was denied (403). Accept its licence on huggingface.co " +
                "with the same account that issued your access token."
            404 -> "This model file no longer exists on Hugging Face (404). Pull to refresh the list."
            416 -> "The partially downloaded file is out of sync. Stop the download and start again."
            429 -> "Hugging Face is rate limiting downloads (429). Try again in a few minutes."
            in 500..599 -> "Hugging Face is unavailable right now (HTTP $code). Try again later."
            else -> "Download failed with HTTP $code."
        }
    }


    /**
     * Token precedence: the value captured when the download was enqueued, then the
     * process-wide holder (populated by `AppContainer` before any component runs).
     */
    private fun resolveAuthToken(): String? =
        inputData.getString(KEY_AUTH_TOKEN)?.takeIf { it.isNotBlank() }
            ?: HuggingFaceAuth.token?.takeIf { it.isNotBlank() }


    private fun createForegroundInfo(modelName: String): ForegroundInfo {
        val notification = createNotification(modelName, 0)
        return ForegroundInfo(
            notificationId(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notificationId(): Int =
        (inputData.getString(KEY_MODEL_ID) ?: "model").hashCode()

    private fun updateNotification(progress: Int) {
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        runCatching {
            notificationManager.notify(notificationId(), createNotification(modelName, progress))
        }
    }

    private fun createNotification(modelName: String, progress: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for AI model downloads"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }
}

private const val CHANNEL_ID = "model_downloads"

/** Thrown when the user paused/cancelled; the partial file is intentionally kept. */
private class DownloadCancelledException : IOException("Download was cancelled")

/** Thrown when retrying the same bytes cannot help (auth, 404, wrong payload). */
private class UnrecoverableDownloadException(message: String) : IOException(message)

/**
 * Thrown by the pre-flight check when the file cannot possibly fit. Deliberately
 * *not* an [UnrecoverableDownloadException]: the partial file is kept so the user
 * can free space and resume instead of re-downloading from zero.
 */
private class InsufficientStorageException(message: String) : IOException(message)

