package com.myra.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM (SELECT * FROM chat_messages ORDER BY timestamp DESC, id DESC LIMIT 200) ORDER BY timestamp ASC, id ASC")
    fun observeMessages(): Flow<List<ChatEntity>>

    @Query("""
        SELECT * FROM chat_messages
        WHERE timestamp >= :afterTimestamp AND timestamp <= :beforeTimestamp AND (
            (:q1 != '' AND text LIKE '%' || :q1 || '%' ESCAPE '\' COLLATE NOCASE) OR
            (:q2 != '' AND text LIKE '%' || :q2 || '%' ESCAPE '\' COLLATE NOCASE) OR
            (:q3 != '' AND text LIKE '%' || :q3 || '%' ESCAPE '\' COLLATE NOCASE) OR
            (:q4 != '' AND text LIKE '%' || :q4 || '%' ESCAPE '\' COLLATE NOCASE) OR
            (:q5 != '' AND text LIKE '%' || :q5 || '%' ESCAPE '\' COLLATE NOCASE)
        )
        ORDER BY
            (CASE WHEN :q1 != '' AND text LIKE '%' || :q1 || '%' ESCAPE '\' COLLATE NOCASE THEN 1 ELSE 0 END +
             CASE WHEN :q2 != '' AND text LIKE '%' || :q2 || '%' ESCAPE '\' COLLATE NOCASE THEN 1 ELSE 0 END +
             CASE WHEN :q3 != '' AND text LIKE '%' || :q3 || '%' ESCAPE '\' COLLATE NOCASE THEN 1 ELSE 0 END +
             CASE WHEN :q4 != '' AND text LIKE '%' || :q4 || '%' ESCAPE '\' COLLATE NOCASE THEN 1 ELSE 0 END +
             CASE WHEN :q5 != '' AND text LIKE '%' || :q5 || '%' ESCAPE '\' COLLATE NOCASE THEN 1 ELSE 0 END) DESC,
            timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun searchHistory(
        q1: String, q2: String, q3: String, q4: String, q5: String, afterTimestamp: Long, beforeTimestamp: Long, limit: Int,
    ): List<ChatEntity>

    @Query("SELECT * FROM chat_messages WHERE isUser = 0 ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLastMyraMessage(): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatEntity): Long

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC, id DESC LIMIT 50000")
    suspend fun getAllForBackup(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatEntity>)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface DiagnosticLogDao {
    @Query("SELECT * FROM diagnostic_logs ORDER BY timestamp DESC, id DESC LIMIT 150")
    fun observeLogs(): Flow<List<DiagnosticLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearAll()
}

@Dao
interface ParkingSpotDao {
    @Query("SELECT * FROM parking_spots ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestSpot(): Flow<ParkingSpotEntity?>

    @Query("SELECT * FROM parking_spots ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestSpot(): ParkingSpotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(spot: ParkingSpotEntity)

    @Query("DELETE FROM parking_spots")
    suspend fun clearAll()
}

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY lastSeenAt DESC")
    fun observePairedDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDeviceEntity)

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}

@Dao
interface UserMemoryDao {
    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC, id DESC")
    fun observeMemories(): Flow<List<UserMemoryEntity>>

    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<UserMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: UserMemoryEntity): Long

    @Query("UPDATE user_memories SET value = :value, sourceText = :sourceText, confidence = :confidence, updatedAt = :updatedAt WHERE memoryKey = :memoryKey")
    suspend fun updateByKey(memoryKey: String, value: String, sourceText: String, confidence: Float, updatedAt: Long)

    @Query("DELETE FROM user_memories WHERE memoryKey = :memoryKey")
    suspend fun deleteByKey(memoryKey: String)

    @Query("SELECT * FROM user_memories ORDER BY updatedAt ASC, id ASC")
    suspend fun getAllForBackup(): List<UserMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memories: List<UserMemoryEntity>)

    @Query("DELETE FROM user_memories")
    suspend fun clearAll()
}


@Dao
interface EmotionalMemoryDao {
    @Query("SELECT * FROM emotional_memories ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<EmotionalMemoryEntity>>

    @Query("SELECT * FROM emotional_memories ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<EmotionalMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: EmotionalMemoryEntity): Long

    @Query("UPDATE emotional_memories SET emotion = :emotion, context = :context, sourceText = :sourceText, confidence = 1.0 WHERE memoryKey = :memoryKey")
    suspend fun updateByKey(memoryKey: String, emotion: String, context: String, sourceText: String)

    @Query("DELETE FROM emotional_memories WHERE memoryKey = :memoryKey")
    suspend fun deleteByKey(memoryKey: String)

    @Query("SELECT * FROM emotional_memories ORDER BY createdAt ASC, id ASC")
    suspend fun getAllForBackup(): List<EmotionalMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memories: List<EmotionalMemoryEntity>)

    @Query("DELETE FROM emotional_memories")
    suspend fun clearAll()
}

@Dao
interface PendingMemoryDao {
    @Query("SELECT * FROM pending_memories ORDER BY proposedAt DESC, id DESC")
    fun observeAll(): Flow<List<PendingMemoryEntity>>

    @Query("SELECT * FROM pending_memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PendingMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: PendingMemoryEntity): Long

    @Query("DELETE FROM pending_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM pending_memories ORDER BY proposedAt DESC, id DESC LIMIT 2000")
    suspend fun getAllForBackup(): List<PendingMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<PendingMemoryEntity>)

    @Query("DELETE FROM pending_memories")
    suspend fun clearAll()
}


@Dao
interface ActionAuditDao {
    @Query("SELECT * FROM action_audit ORDER BY timestamp DESC, id DESC LIMIT 100")
    fun observeRecent(): Flow<List<ActionAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ActionAuditEntity)

    @Query("DELETE FROM action_audit WHERE id NOT IN (SELECT id FROM action_audit ORDER BY timestamp DESC, id DESC LIMIT 1000)")
    suspend fun pruneOldEntries()

    @Query("SELECT * FROM action_audit ORDER BY timestamp DESC, id DESC LIMIT 1000")
    suspend fun getAllForBackup(): List<ActionAuditEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ActionAuditEntity>)

    @Query("DELETE FROM action_audit")
    suspend fun clearAll()
}
