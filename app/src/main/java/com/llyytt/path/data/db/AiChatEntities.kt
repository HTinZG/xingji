package com.llyytt.path.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class ChatRole { USER, ASSISTANT }

/**
 * AI 聊天记录。
 *
 * 只存本地，不进导出文件；清空数据时随行程一起删。
 * tripId 可选：这条消息如果是在某条行程的上下文里问的，就记下来。
 */
@Entity(tableName = "ai_chat_message")
data class AiChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: ChatRole,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tripId: Long? = null,
)

@Dao
interface AiChatDao {

    @Insert
    suspend fun insert(message: AiChatMessageEntity): Long

    @Query("SELECT * FROM ai_chat_message ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_message ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AiChatMessageEntity>

    @Query("DELETE FROM ai_chat_message")
    suspend fun deleteAll()

    @Query("DELETE FROM ai_chat_message WHERE tripId = :tripId")
    suspend fun deleteByTrip(tripId: Long)
}

class ChatRoleConverter {
    @androidx.room.TypeConverter
    fun fromRole(value: ChatRole): String = value.name

    @androidx.room.TypeConverter
    fun toRole(value: String): ChatRole =
        runCatching { ChatRole.valueOf(value) }.getOrDefault(ChatRole.USER)
}
