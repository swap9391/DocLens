package com.lorem.docklens.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ModelFileValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createFile(name: String, sizeBytes: Long, header: ByteArray? = null): File {
        val file = tempFolder.newFile(name)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(sizeBytes)
            if (header != null) {
                raf.seek(0)
                raf.write(header)
            }
        }
        return file
    }

    private fun validTaskFile(name: String = "model.task"): File {
        // Real MediaPipe task bundles are zip archives.
        return createFile(name, ModelFileValidator.MIN_VALID_MODEL_BYTES + 1, byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    }

    // --- looksLikeErrorPayload ---

    @Test
    fun `empty payload is treated as error`() {
        assertTrue(ModelFileValidator.looksLikeErrorPayload(ByteArray(0)))
    }

    @Test
    fun `html payload is detected`() {
        assertTrue(ModelFileValidator.looksLikeErrorPayload("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(ModelFileValidator.looksLikeErrorPayload("<html><body>nope</body>".toByteArray()))
    }

    @Test
    fun `json payload is detected`() {
        assertTrue(ModelFileValidator.looksLikeErrorPayload("{\"error\":\"not found\"}".toByteArray()))
        assertTrue(ModelFileValidator.looksLikeErrorPayload("[{\"error\":true}]".toByteArray()))
    }

    @Test
    fun `leading whitespace does not hide an error page`() {
        assertTrue(ModelFileValidator.looksLikeErrorPayload("\n\n   <html>".toByteArray()))
    }

    @Test
    fun `binary payload is not an error page`() {
        assertFalse(ModelFileValidator.looksLikeErrorPayload(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    // --- validateForInference ---

    @Test
    fun `valid task file passes inference validation`() {
        assertTrue(ModelFileValidator.validateForInference(validTaskFile()).isSuccess)
    }

    @Test
    fun `missing file fails inference validation`() {
        val missing = File(tempFolder.root, "does-not-exist.task")
        assertTrue(ModelFileValidator.validateForInference(missing).isFailure)
    }

    @Test
    fun `legacy bin model is rejected before reaching native code`() {
        val legacy = createFile(
            "gemma-1.1-2b-it-cpu-int4.bin",
            ModelFileValidator.MIN_VALID_MODEL_BYTES + 1
        )
        val result = ModelFileValidator.validateForInference(legacy)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("legacy"))
    }

    @Test
    fun `unsupported extension is rejected`() {
        val other = createFile("model.gguf", ModelFileValidator.MIN_VALID_MODEL_BYTES + 1)
        assertTrue(ModelFileValidator.validateForInference(other).isFailure)
    }

    @Test
    fun `truncated task file is rejected`() {
        val truncated = createFile("model.task", 1024L)
        assertTrue(ModelFileValidator.validateForInference(truncated).isFailure)
    }

    @Test
    fun `task file containing an error page is rejected`() {
        val corrupt = createFile(
            "corrupt.task",
            ModelFileValidator.MIN_VALID_MODEL_BYTES + 1,
            "<!DOCTYPE html>".toByteArray()
        )
        assertTrue(ModelFileValidator.validateForInference(corrupt).isFailure)
    }

    @Test
    fun `extension check is case insensitive`() {
        val upper = createFile(
            "MODEL.TASK",
            ModelFileValidator.MIN_VALID_MODEL_BYTES + 1,
            byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        )
        assertTrue(ModelFileValidator.validateForInference(upper).isSuccess)
    }

    // --- validateDownloadedFile ---

    @Test
    fun `download matching content length passes`() {
        val file = validTaskFile("downloaded.task")
        val result = ModelFileValidator.validateDownloadedFile(file, file.length())
        assertTrue(result.isSuccess)
    }

    @Test
    fun `download with unknown content length still passes`() {
        val file = validTaskFile("unknown-length.task")
        assertTrue(ModelFileValidator.validateDownloadedFile(file, -1L).isSuccess)
    }

    @Test
    fun `incomplete download is rejected`() {
        val file = validTaskFile("partial.task")
        val result = ModelFileValidator.validateDownloadedFile(file, file.length() + 4096)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("incomplete"))
    }

    @Test
    fun `empty download is rejected`() {
        val file = tempFolder.newFile("empty.task")
        assertTrue(ModelFileValidator.validateDownloadedFile(file, 0L).isFailure)
    }

    @Test
    fun `downloaded error page is rejected`() {
        val file = createFile(
            "error.task",
            ModelFileValidator.MIN_VALID_MODEL_BYTES + 1,
            "<html>404</html>".toByteArray()
        )
        val result = ModelFileValidator.validateDownloadedFile(file, file.length())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("error page"))
    }

    @Test
    fun `small but complete download is rejected as invalid model`() {
        val file = createFile("small.task", 2048L, byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val result = ModelFileValidator.validateDownloadedFile(file, file.length())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("too small"))
    }
}

