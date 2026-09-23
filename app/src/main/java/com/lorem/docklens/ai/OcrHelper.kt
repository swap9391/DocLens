package com.lorem.docklens.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import androidx.core.graphics.createBitmap

class OcrHelper(
    private val context: Context
) {

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(file: File): String {
        return try {
            when (file.extension.lowercase()) {
                "jpg", "jpeg", "png", "webp" -> {
                    extractTextFromImage(file)
                }

                "pdf" -> {
                    extractTextFromPdf(file)
                }

                else -> {
                    "Unsupported file type: ${file.extension}"
                }
            }
        } catch (e: Exception) {
            "Error extracting text: ${e.message}"
        }
    }

    private suspend fun extractTextFromImage(file: File): String {
        val image = InputImage.fromFilePath(
            context,
            Uri.fromFile(file)
        )

        val result = recognizer.process(image).await()
        return result.text
    }

    private suspend fun extractTextFromPdf(file: File): String {

        val output = StringBuilder()

        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        val renderer = PdfRenderer(pfd)

        try {
            for (pageIndex in 0 until renderer.pageCount) {

                val page = renderer.openPage(pageIndex)

                val bitmap = createBitmap(page.width * 2, page.height * 2)

                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )

                val inputImage = InputImage.fromBitmap(
                    bitmap,
                    0
                )

                val result = recognizer.process(inputImage).await()

                output.appendLine("----- Page ${pageIndex + 1} -----")
                output.appendLine(result.text)
                output.appendLine()

                page.close()
                bitmap.recycle()
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        return output.toString()
    }
}