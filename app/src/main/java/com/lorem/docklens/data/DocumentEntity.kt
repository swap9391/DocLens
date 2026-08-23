package com.lorem.docklens.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // "IMAGE" or "PDF"
    val uri: String,
    val timestamp: Long = System.currentTimeMillis()
)
