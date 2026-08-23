package com.lorem.docklens.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lorem.docklens.data.DocumentEntity
import com.lorem.docklens.data.DocumentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*

class HomeViewModel(private val repository: DocumentRepository) : ViewModel() {

    val allDocuments: StateFlow<List<DocumentEntity>> = repository.allDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun importDocument(context: Context, uri: Uri, type: String) {
        viewModelScope.launch {
            val displayName = getFileName(context, uri)
            val fileName = "imported_${System.currentTimeMillis()}.${if (type == "PDF") "pdf" else "jpg"}"
            val file = File(context.filesDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            
            val document = DocumentEntity(
                name = displayName,
                type = type,
                uri = file.absolutePath
            )
            repository.insert(document)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Unknown Document"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    fun addCapturedImage(filePath: String) {
        viewModelScope.launch {
            val document = DocumentEntity(
                name = "Captured_${System.currentTimeMillis()}",
                type = "IMAGE",
                uri = filePath
            )
            repository.insert(document)
        }
    }

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch {
            val file = File(document.uri)
            if (file.exists()) {
                file.delete()
            }
            repository.delete(document)
        }
    }
}

class HomeViewModelFactory(private val repository: DocumentRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
