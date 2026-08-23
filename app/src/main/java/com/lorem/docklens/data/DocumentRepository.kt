package com.lorem.docklens.data

import kotlinx.coroutines.flow.Flow

class DocumentRepository(private val documentDao: DocumentDao) {
    val allDocuments: Flow<List<DocumentEntity>> = documentDao.getAllDocuments()

    suspend fun insert(document: DocumentEntity) {
        documentDao.insertDocument(document)
    }

    suspend fun delete(document: DocumentEntity) {
        documentDao.deleteDocument(document)
    }

    suspend fun getDocumentById(id: Long): DocumentEntity? {
        return documentDao.getDocumentById(id)
    }
}
