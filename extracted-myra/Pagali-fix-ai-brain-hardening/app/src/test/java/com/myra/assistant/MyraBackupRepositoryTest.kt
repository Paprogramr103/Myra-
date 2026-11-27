package com.myra.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.data.db.ActionAuditEntity
import com.myra.assistant.data.db.ChatEntity
import com.myra.assistant.data.db.EmotionalMemoryEntity
import com.myra.assistant.data.db.MyraDatabase
import com.myra.assistant.data.db.PairedDeviceEntity
import com.myra.assistant.data.db.PendingMemoryEntity
import com.myra.assistant.data.db.UserMemoryEntity
import com.myra.assistant.data.repository.MyraRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyraBackupRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun invalidBackupRecordIsRejectedBeforeAnyRestoreWrites() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, MyraDatabase::class.java)
            .allowMainThreadQueries().build()
        val target = Room.inMemoryDatabaseBuilder(context, MyraDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            source.userMemoryDao().upsert(
                UserMemoryEntity(category = "preference", memoryKey = "pref:language", value = "Hindi",
                    sourceText = "I prefer Hindi", confidence = .99f, userConfirmed = true),
            )
            target.chatDao().insert(ChatEntity(text = "Keep this", isUser = true, timestamp = 1L, source = "text"))
            val archive = MyraRepository(source).createBackupArchive()
            val invalidArchive = archive.copy(
                stableMemories = archive.stableMemories.map { it.copy(sourceText = "x".repeat(2_001)) },
            )

            val result = runCatching { MyraRepository(target).restoreBackupArchive(invalidArchive) }
            assertTrue("Invalid record must be rejected", result.isFailure)
            assertEquals(listOf("Keep this"), target.chatDao().observeMessages().first().map { it.text })
            assertTrue(target.userMemoryDao().observeMemories().first().isEmpty())
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun restoreMergesChatsAndMemoriesTransactionallyWithoutRestoringSecretsOrDevicePairs() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, MyraDatabase::class.java)
            .allowMainThreadQueries().build()
        val target = Room.inMemoryDatabaseBuilder(context, MyraDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            source.chatDao().insert(ChatEntity(text = "Backup chat", isUser = true, timestamp = 10L, source = "text"))
            source.userMemoryDao().upsert(
                UserMemoryEntity(category = "preference", memoryKey = "pref:language", value = "Hindi",
                    sourceText = "I prefer Hindi", confidence = .99f, userConfirmed = true),
            )
            source.emotionalMemoryDao().upsert(
                EmotionalMemoryEntity(memoryKey = "emotion:day", emotion = "happy", context = "Good day",
                    sourceText = "I feel happy", confidence = .95f, createdAt = 11L),
            )
            source.pendingMemoryDao().insert(
                PendingMemoryEntity(category = "goal", memoryKey = "goal:study", value = "Study daily",
                    sourceText = "I want to study daily", confidence = .9f, proposedAt = 12L),
            )
            source.actionAuditDao().insert(
                ActionAuditEntity(commandType = "TORCH", parameterNames = "enabled", success = true,
                    resultSummary = "Torch switched", timestamp = 13L),
            )
            source.pairedDeviceDao().upsert(
                PairedDeviceEntity(deviceId = "device-secret", name = "Other phone", deviceType = "MYRA_MOBILE",
                    host = "192.0.2.1", port = 8765, sharedKeyHex = "do-not-export"),
            )

            target.chatDao().insert(ChatEntity(text = "Existing local chat", isUser = false, timestamp = 9L, source = "text"))
            val archive = MyraRepository(source).createBackupArchive()
            MyraRepository(target).restoreBackupArchive(archive)

            assertEquals(setOf("Existing local chat", "Backup chat"), target.chatDao().observeMessages().first().map { it.text }.toSet())
            assertEquals("Hindi", target.userMemoryDao().observeMemories().first().single().value)
            assertEquals("happy", target.emotionalMemoryDao().observeAll().first().single().emotion)
            assertEquals("goal:study", target.pendingMemoryDao().observeAll().first().single().memoryKey)
            assertEquals("TORCH", target.actionAuditDao().observeRecent().first().single().commandType)
            assertTrue(target.pairedDeviceDao().observePairedDevices().first().isEmpty())
        } finally {
            source.close()
            target.close()
        }
    }
}
