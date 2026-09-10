package com.lorem.docklens.data

import android.util.Log
import java.io.File

/**
 * Validates downloaded model files before they are handed to MediaPipe.
 *
 * MediaPipe's LLM engine loads models in native code. If the file is truncated,
 * is an HTML/JSON error page, or uses an unsupported legacy format, the native
 * layer aborts the whole process instead of throwing a catchable Kotlin exception.
 * These checks run first so we can fail with a readable error instead of crashing.
 */
object ModelFileValidator {

    private const val TAG = "ModelFileValidator"

    /** Model files are always far larger than this; anything smaller is a failed download. */
    const val MIN_VALID_MODEL_BYTES = 100L * 1024L * 1024L

    /** Extension supported by the MediaPipe task bundle runtime used by this app. */
    private const val TASK_EXTENSION = ".task"

    /** Legacy MediaPipe format that the current runtime cannot load safely. */
    private const val LEGACY_BIN_EXTENSION = ".bin"

    /**
     * Returns true when the bytes look like an error page rather than a model payload.
     */
    fun looksLikeErrorPayload(prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return true

        val head = String(prefix, Charsets.ISO_8859_1)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .take(64)
            .lowercase()

        return head.startsWith("<!doctype") ||
            head.startsWith("<html") ||
            head.startsWith("<?xml") ||
            head.startsWith("{") ||
            head.startsWith("[")
    }

    /**
     * Validates a file that has just been downloaded.
     *
     * This intentionally does not enforce a specific binary layout, it only rejects
     * payloads that are clearly not a model.
     */
    fun validateDownloadedFile(file: File, expectedBytes: Long): Result<Unit> {
        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalStateException("Downloaded model file is missing."))
        }

        val length = file.length()
        if (length <= 0L) {
            return Result.failure(IllegalStateException("Downloaded model file is empty."))
        }

        if (expectedBytes > 0L && length != expectedBytes) {
            return Result.failure(
                IllegalStateException(
                    "Model download is incomplete ($length of $expectedBytes bytes). Please try again."
                )
            )
        }

        val prefix = readPrefix(file)
        if (looksLikeErrorPayload(prefix)) {
            return Result.failure(
                IllegalStateException(
                    "The server returned an error page instead of the model file. Please try again."
                )
            )
        }

        if (length < MIN_VALID_MODEL_BYTES) {
            return Result.failure(
                IllegalStateException(
                    "Downloaded model file is too small to be valid ($length bytes)."
                )
            )
        }

        return Result.success(Unit)
    }

    /**
     * Validates a model file immediately before it is passed to the native runtime.
     */
    fun validateForInference(file: File): Result<Unit> {
        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalStateException("Model file not found. Please download the model again."))
        }

        if (!file.canRead()) {
            return Result.failure(IllegalStateException("Model file cannot be read. Please download the model again."))
        }

        val name = file.name.lowercase()

        if (name.endsWith(LEGACY_BIN_EXTENSION)) {
            return Result.failure(
                IllegalStateException(
                    "This model uses the legacy '.bin' format, which is not supported by the current on-device AI runtime. " +
                        "Please download a '.task' model instead."
                )
            )
        }

        if (!name.endsWith(TASK_EXTENSION)) {
            return Result.failure(
                IllegalStateException(
                    "Unsupported model format '${file.extension}'. Only '.task' models are supported."
                )
            )
        }

        val length = file.length()
        if (length < MIN_VALID_MODEL_BYTES) {
            return Result.failure(
                IllegalStateException(
                    "Model file looks incomplete ($length bytes). Please download it again."
                )
            )
        }

        val prefix = readPrefix(file)
        if (looksLikeErrorPayload(prefix)) {
            return Result.failure(
                IllegalStateException("Model file is corrupted. Please download it again.")
            )
        }

        return Result.success(Unit)
    }

    private fun readPrefix(file: File, size: Int = 64): ByteArray {
        return try {
            file.inputStream().use { stream ->
                val buffer = ByteArray(size)
                val read = stream.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read model file header", e)
            ByteArray(0)
        }
    }
}

