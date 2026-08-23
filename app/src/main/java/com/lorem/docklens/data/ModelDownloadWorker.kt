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
        .build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelName = inputData.getString("modelName") ?: "Model"
        return createForegroundInfo(modelName, 0)
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val modelId = inputData.getString("modelId") ?: return androidx.work.ListenableWorker.Result.failure()
        val modelName = inputData.getString("modelName") ?: "Model"
        val downloadUrl = inputData.getString("downloadUrl") ?: return androidx.work.ListenableWorker.Result.failure()

        createNotificationChannel()
        
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w("ModelDownloadWorker", "Could not set foreground info", e)
        }

        val destFile = File(applicationContext.filesDir, "$modelId.task")
        val tempFile = File(applicationContext.cacheDir, "$modelId.tmp")

        return try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Accept", "*/*")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("ModelDownloadWorker", "Download failed with code: ${response.code} for URL: $downloadUrl")
                    return androidx.work.ListenableWorker.Result.failure()
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val data = ByteArray(16384)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0
                        
                        while (input.read(data).also { count = it } != -1) {
                            if (isStopped) {
                                tempFile.delete()
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
                Log.e("ModelDownloadWorker", "Failed to rename temp file to dest file")
                androidx.work.ListenableWorker.Result.failure()
            }
        } catch (e: Exception) {
            Log.e("ModelDownloadWorker", "Error downloading model", e)
            if (tempFile.exists()) tempFile.delete()
            androidx.work.ListenableWorker.Result.failure()
        }
    }

    private fun updateNotification(modelName: String, progress: Int, notificationId: Int) {
        try {
            notificationManager.notify(
                notificationId,
                createNotification(modelName, progress)
            )
        } catch (e: Exception) {
            // Notification updates might fail if suppressed
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
