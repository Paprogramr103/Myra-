package com.myra.assistant.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "voice",
)

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "parking_spots")
data class ParkingSpotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val note: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val deviceType: String, // "MYRA_MOBILE" or "PC_COMPANION"
    val host: String,
    val port: Int,
    val sharedKeyHex: String,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "user_memories",
    indices = [androidx.room.Index(value = ["memoryKey"], unique = true)],
)
data class UserMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val memoryKey: String,
    val value: String,
    val sourceText: String,
    val confidence: Float,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val userConfirmed: Boolean = false,
)


@Entity(tableName = "emotional_memories", indices = [androidx.room.Index(value = ["memoryKey"], unique = true)])
data class EmotionalMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val memoryKey: String,
    val emotion: String,
    val context: String,
    val sourceText: String,
    val confidence: Float,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pending_memories", indices = [androidx.room.Index(value = ["memoryKey"], unique = true)])
data class PendingMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val memoryKey: String,
    val value: String,
    val sourceText: String,
    val confidence: Float,
    val proposedAt: Long = System.currentTimeMillis(),
)


@Entity(tableName = "action_audit")
data class ActionAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val commandType: String,
    val parameterNames: String,
    val success: Boolean,
    val resultSummary: String,
    val timestamp: Long = System.currentTimeMillis(),
)
