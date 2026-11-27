package com.myra.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.data.db.MyraDatabase
import com.myra.assistant.data.repository.MyraRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionAuditPersistenceTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        MyraDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repository = MyraRepository(database)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun auditStoresOutcomeButNotPhoneNumber() = runBlocking {
        repository.recordActionOutcome(
            commandType = "CALL_CONTACT",
            parameterNames = "contact",
            success = true,
            resultSummary = "Call started for 9876543210",
        )

        val entry = repository.recentActionAudit.first().single()
        assertEquals("CALL_CONTACT", entry.commandType)
        assertEquals("contact", entry.parameterNames)
        assertTrue(entry.success)
        assertFalse(entry.resultSummary.contains("9876543210"))
        assertTrue(entry.resultSummary.contains("[REDACTED_NUMBER]"))
    }

    @Test
    fun auditKeepsOnlyLatestHundredInObservableHistory() = runBlocking {
        repeat(105) { index ->
            repository.recordActionOutcome(
                commandType = "TEST_$index",
                parameterNames = "none",
                success = index % 2 == 0,
                resultSummary = "test result",
            )
        }

        val entries = repository.recentActionAudit.first()
        assertEquals(100, entries.size)
        assertEquals("TEST_104", entries.first().commandType)
    }

    @Test
    fun personalAndEmotionalMemoriesCanBeEditedAndDeleted() = runBlocking {
        repository.proposeMemory(
            com.myra.assistant.ai.MemoryCandidate(
                category = "preference",
                key = "preference:tea",
                value = "Likes tea",
                sourceText = "मुझे चाय पसंद है",
                confidence = 0.95f,
            ),
        )
        val pending = repository.pendingMemories.first().single()
        assertTrue(repository.approvePendingMemory(pending.id))
        repository.updateMemory("preference:tea", "Prefers masala tea")
        assertEquals("Prefers masala tea", repository.memories.first().single().value)
        repository.deleteMemory("preference:tea")
        assertTrue(repository.memories.first().isEmpty())

        repository.proposeMemory(
            com.myra.assistant.ai.MemoryCandidate(
                category = "emotion",
                key = "emotion:moment",
                value = "emotion=happy; context=Celebrated a success",
                sourceText = "आज खुशी हुई",
                confidence = 0.96f,
            ),
        )
        val emotionalPending = repository.pendingMemories.first().single()
        assertTrue(repository.approvePendingMemory(emotionalPending.id))
        repository.updateEmotionalMemory("emotion:moment", "joyful", "Celebrated a family success")
        assertEquals("joyful", repository.emotionalMemories.first().single().emotion)
        assertEquals("Celebrated a family success", repository.emotionalMemories.first().single().context)
        repository.deleteEmotionalMemory("emotion:moment")
        assertTrue(repository.emotionalMemories.first().isEmpty())
    }
}
