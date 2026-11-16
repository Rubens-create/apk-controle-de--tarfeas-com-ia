package com.taskai.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("categoryId"), Index("projectId"), Index("dueDate"), Index("status")]
)
data class TaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val dueDate: String? = null,
    val dueTime: String? = null,
    val priority: String = "MEDIA",
    val status: String = "PENDING",
    val categoryId: String? = null,
    val projectId: String? = null,
    val recurrenceRule: String? = null,
    val reminderEnabled: Boolean = false,
    val reminderMinutes: Int = 15,
    val reminderDatetime: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
