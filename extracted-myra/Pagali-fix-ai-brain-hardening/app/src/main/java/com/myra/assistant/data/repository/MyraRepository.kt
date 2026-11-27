package com.myra.assistant.data.repository

import com.myra.assistant.data.db.ChatEntity
import com.myra.assistant.data.db.DiagnosticLogEntity
import com.myra.assistant.data.db.MyraDatabase
import com.myra.assistant.data.db.PairedDeviceEntity
import com.myra.assistant.data.db.ParkingSpotEntity
import com.myra.assistant.data.db.UserMemoryEntity
import com.myra.assistant.data.db.EmotionalMemoryEntity
import com.myra.assistant.data.db.PendingMemoryEntity
import com.myra.assistant.data.db.ActionAuditEntity
import com.myra.assistant.data.backup.MyraBackupCodec
import androidx.room.withTransaction
import com.myra.assistant.model.ChatMessage
import com.myra.assistant.security.OtpSanitizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MyraRepository(private val database: MyraDatabase) {

    private val chatDao = database.chatDao()
    private val logDao = database.diagnosticLogDao()
    private val parkingDao = database.parkingSpotDao()
    private val pairedDao = database.pairedDeviceDao()
    private val memoryDao = database.userMemoryDao()
    private val emotionalMemoryDao = database.emotionalMemoryDao()
    private val pendingMemoryDao = database.pendingMemoryDao()
    private val actionAuditDao = database.actionAuditDao()

    val chatMessages: Flow<List<ChatMessage>> = chatDao.observeMessages().map { list ->
        list.map { ChatMessage(id = it.id, text = it.text, isUser = it.isUser, timestamp = it.timestamp, source = it.source) }
    }

    val diagnosticLogs: Flow<List<DiagnosticLogEntity>> = logDao.observeLogs()
    val latestParkingSpot: Flow<ParkingSpotEntity?> = parkingDao.observeLatestSpot()
    val pairedDevices: Flow<List<PairedDeviceEntity>> = pairedDao.observePairedDevices()
    val memories: Flow<List<UserMemoryEntity>> = memoryDao.observeMemories()
    val emotionalMemories: Flow<List<EmotionalMemoryEntity>> = emotionalMemoryDao.observeAll()
    val pendingMemories: Flow<List<PendingMemoryEntity>> = pendingMemoryDao.observeAll()
    val recentActionAudit: Flow<List<ActionAuditEntity>> = actionAuditDao.observeRecent()


    suspend fun createBackupArchive(): MyraBackupCodec.Archive = database.withTransaction {
        val chats = chatDao.getAllForBackup().asReversed()
        val stable = memoryDao.getRecentMemories(2_000).filter { it.userConfirmed }
        val emotional = emotionalMemoryDao.getRecent(2_000)
        val pending = pendingMemoryDao.getAllForBackup().asReversed()
        val audit = actionAuditDao.getAllForBackup().asReversed()
        MyraBackupCodec.Archive(
            exportedAt = System.currentTimeMillis(),
            chatMessages = chats.map { MyraBackupCodec.ChatRecord(it.text, it.isUser, it.timestamp, it.source) },
            stableMemories = stable.map {
                MyraBackupCodec.StableMemoryRecord(it.category, it.memoryKey, it.value, it.sourceText, it.confidence, it.createdAt, it.updatedAt, it.userConfirmed)
            },
            emotionalMemories = emotional.map {
                MyraBackupCodec.EmotionalMemoryRecord(it.memoryKey, it.emotion, it.context, it.sourceText, it.confidence, it.createdAt)
            },
            pendingMemories = pending.map {
                MyraBackupCodec.PendingMemoryRecord(it.category, it.memoryKey, it.value, it.sourceText, it.confidence, it.proposedAt)
            },
            actionHistory = audit.map {
                MyraBackupCodec.ActionRecord(it.commandType, it.parameterNames, it.success, it.resultSummary, it.timestamp)
            },
        )
    }

    suspend fun restoreBackupArchive(archive: MyraBackupCodec.Archive) {
        require(archive.schemaVersion == 1 && archive.exportedAt >= 0L) {
            "Backup metadata is invalid or unsupported."
        }
        require(archive.chatMessages.size <= 50_000) { "Backup contains too many chat messages." }
        require(archive.stableMemories.size <= 2_000 && archive.emotionalMemories.size <= 2_000 && archive.pendingMemories.size <= 2_000) {
            "Backup contains too many memory records."
        }
        require(archive.actionHistory.size <= 1_000) { "Backup contains too many action records." }
        require(archive.chatMessages.all {
            it.text.length <= 20_000 && it.source.length in 1..40 && it.timestamp >= 0L &&
                '\u0000' !in it.text && '\u0000' !in it.source
        }) { "Backup contains invalid chat data." }
        require(archive.stableMemories.all {
            it.category.length in 1..40 && it.memoryKey.isNotBlank() && it.memoryKey.length <= 200 &&
                it.value.length in 1..2_000 && it.sourceText.length <= 2_000 &&
                it.confidence in 0f..1f && it.createdAt >= 0L && it.updatedAt >= it.createdAt &&
                it.userConfirmed && '\u0000' !in it.value && '\u0000' !in it.sourceText
        }) { "Backup contains invalid or unapproved stable memory data." }
        require(archive.emotionalMemories.all {
            it.memoryKey.isNotBlank() && it.memoryKey.length <= 200 && it.emotion.length in 1..40 &&
                it.context.length in 1..2_000 && it.sourceText.length <= 2_000 &&
                it.confidence in 0f..1f && it.createdAt >= 0L &&
                '\u0000' !in it.emotion && '\u0000' !in it.context && '\u0000' !in it.sourceText
        }) { "Backup contains invalid emotional memory data." }
        require(archive.pendingMemories.all {
            it.category.length in 1..40 && it.memoryKey.isNotBlank() && it.memoryKey.length <= 200 &&
                it.value.length in 1..2_000 && it.sourceText.length <= 2_000 &&
                it.confidence in 0f..1f && it.proposedAt >= 0L &&
                '\u0000' !in it.value && '\u0000' !in it.sourceText
        }) { "Backup contains invalid pending memory data." }
        require(archive.actionHistory.all {
            it.commandType.length in 1..80 && it.parameterNames.length <= 240 &&
                it.resultSummary.length <= 1_000 && it.timestamp >= 0L &&
                '\u0000' !in it.commandType && '\u0000' !in it.parameterNames &&
                '\u0000' !in it.resultSummary
        }) { "Backup contains invalid action history data." }
        database.withTransaction {
            chatDao.insertAll(archive.chatMessages.map {
                ChatEntity(text = it.text, isUser = it.isUser, timestamp = it.timestamp, source = it.source)
            })
            memoryDao.upsertAll(archive.stableMemories.map {
                UserMemoryEntity(category = it.category, memoryKey = it.memoryKey, value = it.value, sourceText = it.sourceText,
                    confidence = it.confidence, createdAt = it.createdAt, updatedAt = it.updatedAt, userConfirmed = it.userConfirmed)
            })
            emotionalMemoryDao.upsertAll(archive.emotionalMemories.map {
                EmotionalMemoryEntity(memoryKey = it.memoryKey, emotion = it.emotion, context = it.context,
                    sourceText = it.sourceText, confidence = it.confidence, createdAt = it.createdAt)
            })
            pendingMemoryDao.insertAll(archive.pendingMemories.map {
                PendingMemoryEntity(category = it.category, memoryKey = it.memoryKey, value = it.value,
                    sourceText = it.sourceText, confidence = it.confidence, proposedAt = it.proposedAt)
            })
            actionAuditDao.insertAll(archive.actionHistory.map {
                ActionAuditEntity(commandType = it.commandType, parameterNames = it.parameterNames,
                    success = it.success, resultSummary = it.resultSummary, timestamp = it.timestamp)
            })
            actionAuditDao.pruneOldEntries()
        }
    }

    suspend fun addChatMessage(text: String, isUser: Boolean, source: String = "voice") {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val safeText = OtpSanitizer.redactIfSensitive(clean)
        if (!isUser) {
            val last = chatDao.getLastMyraMessage()
            if (last != null && last.text == safeText) return
        }
        chatDao.insert(ChatEntity(text = safeText, isUser = isUser, source = source))
    }

    suspend fun proposeMemory(candidate: com.myra.assistant.ai.MemoryCandidate) {
        pendingMemoryDao.insert(
            PendingMemoryEntity(
                category = candidate.category,
                memoryKey = candidate.key,
                value = candidate.value,
                sourceText = candidate.sourceText,
                confidence = candidate.confidence,
            ),
        )
    }

    suspend fun approvePendingMemory(id: Long): Boolean {
        val pending = pendingMemoryDao.getById(id) ?: return false
        if (pending.category == "emotion") {
            val emotion = Regex("emotion=([^;]+)").find(pending.value)?.groupValues?.getOrNull(1) ?: "unknown"
            val context = Regex("context=(.*)$").find(pending.value)?.groupValues?.getOrNull(1) ?: pending.value
            emotionalMemoryDao.upsert(
                EmotionalMemoryEntity(
                    memoryKey = pending.memoryKey,
                    emotion = emotion,
                    context = context,
                    sourceText = pending.sourceText,
                    confidence = pending.confidence,
                    createdAt = pending.proposedAt,
                ),
            )
        } else {
            memoryDao.upsert(
                UserMemoryEntity(
                    category = pending.category,
                    memoryKey = pending.memoryKey,
                    value = pending.value,
                    sourceText = pending.sourceText,
                    confidence = pending.confidence,
                    createdAt = pending.proposedAt,
                    updatedAt = System.currentTimeMillis(),
                    userConfirmed = true,
                ),
            )
        }
        pendingMemoryDao.deleteById(id)
        return true
    }

    suspend fun rejectPendingMemory(id: Long) {
        pendingMemoryDao.deleteById(id)
    }

    suspend fun getMemoryContext(limit: Int = 40): String {
        val stable = memoryDao.getRecentMemories(limit)
            .filter { it.userConfirmed && it.category != "emotion" }
            .asReversed()
            .map { "${it.category}: ${it.value}" }
        val emotional = emotionalMemoryDao.getRecent(limit)
            .asReversed()
            .map { "past emotional moment (${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(it.createdAt))}): ${it.emotion}; ${it.context}" }
        return (stable + emotional).joinToString("\n").take(4000)
    }

    suspend fun deleteEmotionalMemory(memoryKey: String) {
        emotionalMemoryDao.deleteByKey(memoryKey)
    }

    suspend fun clearEmotionalMemories() {
        emotionalMemoryDao.clearAll()
    }

    suspend fun updateMemory(memoryKey: String, value: String) {
        val clean = value.trim().take(160)
        if (clean.isBlank()) return
        memoryDao.updateByKey(
            memoryKey = memoryKey,
            value = clean,
            sourceText = "Edited by user",
            confidence = 1.0f,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun updateEmotionalMemory(memoryKey: String, emotion: String, context: String) {
        val cleanEmotion = emotion.trim().lowercase(java.util.Locale.ROOT).take(40)
        val cleanContext = context.trim().take(180)
        if (cleanEmotion.isBlank() || cleanContext.isBlank()) return
        emotionalMemoryDao.updateByKey(
            memoryKey = memoryKey,
            emotion = cleanEmotion,
            context = cleanContext,
            sourceText = "Edited by user",
        )
    }

    suspend fun deleteMemory(memoryKey: String) {
        memoryDao.deleteByKey(memoryKey)
    }

    suspend fun clearMemories() {
        memoryDao.clearAll()
    }

    suspend fun searchChatHistory(queries: List<String>, limit: Int = 12, afterTimestamp: Long = 0L, beforeTimestamp: Long = Long.MAX_VALUE): List<ChatMessage> {
        val terms = queries.asSequence()
            .map { it.trim() }
            .filter { it.length in 2..80 && !it.contains('\n') && !it.contains('\r') }
            .distinctBy { it.lowercase() }
            .take(5)
            .map(::escapeSqlLikeLiteral)
            .toList()
            .let { it + List(5 - it.size) { "" } }
        if (terms.all { it.isBlank() }) return emptyList()
        return chatDao.searchHistory(
            terms[0], terms[1], terms[2], terms[3], terms[4], afterTimestamp.coerceAtLeast(0L), beforeTimestamp.coerceAtLeast(afterTimestamp), limit.coerceIn(1, 12),
        ).map {
            ChatMessage(id = it.id, text = it.text, isUser = it.isUser, timestamp = it.timestamp, source = it.source)
        }
    }

    suspend fun clearChatHistory() {
        chatDao.clearAll()
    }

    suspend fun log(level: String, tag: String, message: String) {
        val safeMsg = OtpSanitizer.redactIfSensitive(message)
        logDao.insert(DiagnosticLogEntity(level = level, tag = tag, message = safeMsg))
    }

    suspend fun clearLogs() {
        logDao.clearAll()
    }

    suspend fun recordActionOutcome(
        commandType: String,
        parameterNames: String,
        success: Boolean,
        resultSummary: String,
    ) {
        actionAuditDao.insert(
            ActionAuditEntity(
                commandType = commandType.take(80),
                parameterNames = parameterNames.take(240),
                success = success,
                resultSummary = OtpSanitizer.redactIfSensitive(resultSummary)
                    .replace(Regex("(?<!\\d)\\+?\\d[\\d\\s().-]{7,}\\d(?!\\d)"), "[REDACTED_NUMBER]")
                    .take(500),
            ),
        )
        actionAuditDao.pruneOldEntries()
    }

    suspend fun clearActionAudit() {
        actionAuditDao.clearAll()
    }


    suspend fun saveParkingSpot(latitude: Double, longitude: Double, note: String) {
        parkingDao.insert(ParkingSpotEntity(latitude = latitude, longitude = longitude, note = note))
    }

    suspend fun getLatestParkingSpot(): ParkingSpotEntity? = parkingDao.getLatestSpot()

    suspend fun clearParkingSpot() {
        parkingDao.clearAll()
    }

    suspend fun upsertPairedDevice(device: PairedDeviceEntity) {
        pairedDao.upsert(device)
    }

    suspend fun getPairedDevice(deviceId: String): PairedDeviceEntity? = pairedDao.getById(deviceId)

    suspend fun removePairedDevice(deviceId: String) {
        pairedDao.deleteById(deviceId)
    }
}


/** Escape SQLite LIKE metacharacters so recall queries match literal user text. */
internal fun escapeSqlLikeLiteral(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
