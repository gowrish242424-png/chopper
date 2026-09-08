package com.example.choppermobile.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ChopperDao {

    @Insert
    fun insertConversation(
        conversation: Conversation
    ): Long

    @Insert
    fun insertMessage(
        message: ChatMessage
    ): Long

    @Query(
        """
        SELECT * FROM conversations
        ORDER BY updatedAt DESC
        """
    )
    fun getAllConversations(): List<Conversation>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC, id ASC
        """
    )
    fun getMessages(
        conversationId: Long
    ): List<ChatMessage>

    @Query(
        """
        UPDATE conversations
        SET title = :title,
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """
    )
    fun updateConversation(
        conversationId: Long,
        title: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        DELETE FROM chat_messages
        WHERE conversationId = :conversationId
        """
    )
    fun deleteMessages(
        conversationId: Long
    )

    @Query(
        """
        DELETE FROM conversations
        WHERE id = :conversationId
        """
    )
    fun deleteConversation(
        conversationId: Long
    )

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    fun saveMemory(
        memory: MemoryFact
    ): Long

    @Query(
        """
        SELECT * FROM memory_facts
        WHERE memoryKey = :key
        LIMIT 1
        """
    )
    fun getMemory(
        key: String
    ): MemoryFact?

    @Query(
        """
        SELECT * FROM memory_facts
        ORDER BY updatedAt DESC
        """
    )
    fun getAllMemories(): List<MemoryFact>

    @Query(
        """
        DELETE FROM memory_facts
        WHERE id = :id
        """
    )
    fun deleteMemory(
        id: Long
    )
}