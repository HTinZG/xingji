package com.llyytt.path.data.repo

import com.llyytt.path.data.db.AiChatDao
import com.llyytt.path.data.db.AiChatMessageEntity
import com.llyytt.path.data.db.ChatRole
import kotlinx.coroutines.flow.Flow

/**
 * 聊天记录。只存本地，不进导出文件；清空数据时一起删。
 */
class AiChatRepository(private val dao: AiChatDao) {

    fun observe(): Flow<List<AiChatMessageEntity>> = dao.observeAll()

    suspend fun addUser(content: String, tripId: Long? = null) {
        dao.insert(AiChatMessageEntity(role = ChatRole.USER, content = content, tripId = tripId))
    }

    suspend fun addAssistant(content: String, tripId: Long? = null) {
        dao.insert(AiChatMessageEntity(role = ChatRole.ASSISTANT, content = content, tripId = tripId))
    }

    /** 最近 N 条，按时间正序返回（给模型当历史）。 */
    suspend fun recent(limit: Int): List<AiChatMessageEntity> =
        dao.recent(limit).reversed()

    suspend fun clear() = dao.deleteAll()
}
