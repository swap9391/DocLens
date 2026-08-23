package com.lorem.docklens.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY lastModifiedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Transaction
    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    fun getSessionWithMessages(sessionId: Long): Flow<ChatSessionWithMessages>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)
}
