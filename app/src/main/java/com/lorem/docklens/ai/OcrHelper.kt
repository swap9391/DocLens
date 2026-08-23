package com.lorem.docklens.ai

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

class OcrHelper(private val context: Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(file: File): String {
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val result = recognizer.process(image).await()
            result.text
        } catch (e: Exception) {
            "Error extracting text: ${e.message}"
        }
    }
}
