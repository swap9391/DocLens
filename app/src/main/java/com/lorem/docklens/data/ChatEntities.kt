package com.lorem.docklens.data

import androidx.room.*

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val modelId: String? = null
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val messageId: Long = 0,
    val sessionId: Long,
    val content: String,
    val reasoning: String? = null, // For reasoning models like DeepSeek
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null // For vision support
)

data class ChatSessionWithMessages(
    @Embedded val session: ChatSessionEntity,
    @Relation(
        parentColumn = "sessionId",
        entityColumn = "sessionId"
    )
    val messages: List<ChatMessageEntity>
)
