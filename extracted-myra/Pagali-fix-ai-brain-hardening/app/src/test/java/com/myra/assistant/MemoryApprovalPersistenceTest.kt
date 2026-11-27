package com.myra.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.ai.MemoryCandidate
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
class MemoryApprovalPersistenceTest {
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
    fun personalFactIsNotSavedUntilUserApproves() = runBlocking {
        repository.proposeMemory(
            MemoryCandidate(
                category = "preference",
                key = "preference:tea",
                value = "Likes tea",
                sourceText = "मुझे चाय पसंद है",
                confidence = 0.95f,
            ),
        )

        assertTrue(repository.memories.first().isEmpty())
        assertEquals(1, repository.pendingMemories.first().size)

        val pendingId = repository.pendingMemories.first().single().id
        assertTrue(repository.approvePendingMemory(pendingId))
        assertEquals("Likes tea", repository.memories.first().single().value)
        assertTrue(repository.pendingMemories.first().isEmpty())
    }

    @Test
    fun emotionalEventIsStoredSeparatelyAfterApproval() = runBlocking {
        repository.proposeMemory(
            MemoryCandidate(
                category = "emotion",
                key = "emotion:test-event",
                value = "emotion=happy; context=User felt happy about a good day",
                sourceText = "आज मैं बहुत खुश हूँ",
                confidence = 0.96f,
            ),
        )

        assertTrue(repository.memories.first().isEmpty())
        assertTrue(repository.emotionalMemories.first().isEmpty())
        val pendingId = repository.pendingMemories.first().single().id

        assertTrue(repository.approvePendingMemory(pendingId))
        assertTrue(repository.memories.first().isEmpty())
        val saved = repository.emotionalMemories.first().single()
        assertEquals("happy", saved.emotion)
        assertEquals("User felt happy about a good day", saved.context)
    }

    @Test
    fun rejectedProposalNeverEntersApprovedMemory() = runBlocking {
        repository.proposeMemory(
            MemoryCandidate(
                category = "goal",
                key = "goal:study",
                value = "Wants to study",
                sourceText = "मैं पढ़ाई करना चाहता हूँ",
                confidence = 0.9f,
            ),
        )
        val pendingId = repository.pendingMemories.first().single().id

        repository.rejectPendingMemory(pendingId)

        assertTrue(repository.pendingMemories.first().isEmpty())
        assertTrue(repository.memories.first().isEmpty())
        assertTrue(repository.emotionalMemories.first().isEmpty())
    }
}
