package com.taskai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Parameter-bound persistence API. Repository enforces business invariants; backup uses one Room transaction. */
@Dao
interface AppDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt, id") fun observeTasks(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE, id") fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM projects ORDER BY name COLLATE NOCASE, id") fun observeProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM activity_logs ORDER BY createdAt DESC, rowid DESC") fun observeLogs(): Flow<List<ActivityLogEntity>>
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, rowid DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, rowid") fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt, id") suspend fun getTasks(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun getTask(id: String): TaskEntity?
    @Insert suspend fun insertTask(task: TaskEntity)
    @Update suspend fun updateTask(task: TaskEntity): Int
    @Query("DELETE FROM tasks WHERE id IN (:ids)") suspend fun deleteTasks(ids: List<String>): Int

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE, id") suspend fun getCategories(): List<CategoryEntity>
    @Query("SELECT * FROM projects ORDER BY name COLLATE NOCASE, id") suspend fun getProjects(): List<ProjectEntity>
    @Query("SELECT * FROM activity_logs ORDER BY createdAt, rowid") suspend fun getLogs(): List<ActivityLogEntity>
    @Query("SELECT * FROM conversations ORDER BY createdAt, rowid") suspend fun getConversations(): List<ConversationEntity>
    @Query("SELECT * FROM messages ORDER BY createdAt, rowid") suspend fun getAllMessages(): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, rowid") suspend fun getMessages(conversationId: String): List<MessageEntity>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun getCategory(id: String): CategoryEntity?
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun getProject(id: String): ProjectEntity?
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun getConversation(id: String): ConversationEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM activity_logs WHERE action = 'RECURRENCE_CREATED' AND entityType = 'TASK' AND entityId = :taskId)") suspend fun hasRecurrenceSuccessor(taskId: String): Boolean
    @Insert suspend fun insertCategory(category: CategoryEntity)
    @Update suspend fun updateCategory(category: CategoryEntity): Int
    @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: String): Int
    @Insert suspend fun insertProject(project: ProjectEntity)
    @Update suspend fun updateProject(project: ProjectEntity): Int
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String): Int
    @Insert suspend fun insertConversation(conversation: ConversationEntity)
    @Update suspend fun updateConversation(conversation: ConversationEntity): Int
    @Insert suspend fun insertMessage(message: MessageEntity)
    @Query("DELETE FROM conversations") suspend fun clearConversations()
    @Insert suspend fun insertLog(log: ActivityLogEntity)
}
