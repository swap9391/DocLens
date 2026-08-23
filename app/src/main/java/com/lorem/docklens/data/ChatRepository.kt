package com.lorem.docklens.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {
    val allSessions: Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    suspend fun createSession(title: String, modelId: String?): Long {
        val session = ChatSessionEntity(title = title, modelId = modelId)
        return chatDao.insertSession(session)
    }

    fun getSessionWithMessages(sessionId: Long): Flow<ChatSessionWithMessages> {
        return chatDao.getSessionWithMessages(sessionId)
    }

    suspend fun saveMessage(sessionId: Long, content: String, isUser: Boolean, reasoning: String? = null, imagePath: String? = null) {
        val message = ChatMessageEntity(
            sessionId = sessionId,
            content = content,
            isUser = isUser,
            reasoning = reasoning,
            imagePath = imagePath
        )
        chatDao.insertMessage(message)
        
        // Update session last modified
        // In a real app, you'd fetch the session first or use a custom query
    }

    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSession(sessionId)
    }
}
