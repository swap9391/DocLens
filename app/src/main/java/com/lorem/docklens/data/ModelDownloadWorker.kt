package com.lorem.docklens.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelName = inputData.getString("modelName") ?: "Model"
        return createForegroundInfo(modelName, 0)
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val modelId = inputData.getString("modelId") ?: return androidx.work.ListenableWorker.Result.failure()
        val modelName = inputData.getString("modelName") ?: "Model"
        val downloadUrl = inputData.getString("downloadUrl") ?: return androidx.work.ListenableWorker.Result.failure()
        val format = inputData.getString("format") ?: "MEDIAPIPE_TASK"

        createNotificationChannel()
        
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w("ModelDownloadWorker", "Could not set foreground info", e)
        }

        val extension = if (format == "LITERT_LM") ".litertlm" else ".task"
        
        // Match path used in ModelRepository
        val modelsDir = File(applicationContext.getExternalFilesDir(null), "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        
        val destFile = File(modelsDir, "$modelId$extension")
        // Use the same directory for temp file to avoid cross-volume rename failures
        val tempFile = File(modelsDir, "$modelId.tmp")

        return try {
            Log.d("ModelDownloadWorker", "Starting download from: $downloadUrl")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DocLens/1.0")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = "Download failed: ${response.code} ${response.message}"
                    Log.e("ModelDownloadWorker", "$errorMsg for URL: $downloadUrl")
                    return androidx.work.ListenableWorker.Result.failure(
                        workDataOf("error" to errorMsg)
                    )
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val data = ByteArray(65536)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0
                        
                        while (input.read(data).also { count = it } != -1) {
                            if (isStopped) {
                                Log.d("ModelDownloadWorker", "Download stopped/cancelled. Cleaning up.")
                                if (tempFile.exists()) tempFile.delete()
                                return androidx.work.ListenableWorker.Result.failure()
                            }
                            
                            output.write(data, 0, count)
                            total += count
                            
                            if (contentLength > 0) {
                                val progress = (total * 100 / contentLength).toInt()
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    setProgress(workDataOf("progress" to progress))
                                    updateNotification(modelName, progress, modelId.hashCode())
                                }
                            }
                        }
                    }
                }
            }

            if (destFile.exists()) destFile.delete()
            if (tempFile.renameTo(destFile)) {
                Log.d("ModelDownloadWorker", "Successfully downloaded model to ${destFile.absolutePath}")
                androidx.work.ListenableWorker.Result.success()
            } else {
                // Fallback: Copy if rename fails
                try {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                    Log.d("ModelDownloadWorker", "Successfully copied model to ${destFile.absolutePath}")
                    androidx.work.ListenableWorker.Result.success()
                } catch (e: Exception) {
                    Log.e("ModelDownloadWorker", "Failed to rename and copy temp file to dest file", e)
                    if (tempFile.exists()) tempFile.delete()
                    androidx.work.ListenableWorker.Result.failure(
                        workDataOf("error" to "Failed to save file to destination: ${e.message}")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ModelDownloadWorker", "Error downloading model", e)
            if (tempFile.exists()) tempFile.delete()
            androidx.work.ListenableWorker.Result.failure(
                workDataOf("error" to (e.message ?: "Unknown download error"))
            )
        }
    }

    private fun updateNotification(modelName: String, progress: Int, notificationId: Int) {
        try {
            notificationManager.notify(
                notificationId,
                createNotification(modelName, progress)
            )
        } catch (e: Exception) {
        }
    }

    private fun createForegroundInfo(modelName: String, progress: Int): ForegroundInfo {
        val notification = createNotification(modelName, progress)
        val id = modelName.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun createNotification(modelName: String, progress: Int) =
        NotificationCompat.Builder(applicationContext, "model_downloads")
            .setContentTitle("Downloading $modelName")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "model_downloads",
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for AI model downloads"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
