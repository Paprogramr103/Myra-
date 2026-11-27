package com.myra.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.data.db.ChatEntity
import com.myra.assistant.data.db.MyraDatabase
import com.myra.assistant.data.repository.MyraRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatHistorySearchTest {
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
    fun percentAndUnderscoreAreSearchedAsLiteralCharacters() = runBlocking {
        database.chatDao().insert(ChatEntity(text = "I am 100% sure", isUser = true))
        database.chatDao().insert(ChatEntity(text = "I am 1000 sure", isUser = true))
        database.chatDao().insert(ChatEntity(text = "my_file_backup", isUser = true))
        database.chatDao().insert(ChatEntity(text = "myXfileXbackup", isUser = true))

        val percent = repository.searchChatHistory(listOf("100%", "percent query"))
        val underscore = repository.searchChatHistory(listOf("my_file", "underscore query"))

        assertEquals(listOf("I am 100% sure"), percent.map { it.text })
        assertEquals(listOf("my_file_backup"), underscore.map { it.text })
    }

    @Test
    fun backslashIsSearchedAsLiteralCharacter() = runBlocking {
        database.chatDao().insert(ChatEntity(text = "folder\\name", isUser = true))
        database.chatDao().insert(ChatEntity(text = "foldername", isUser = true))

        val results = repository.searchChatHistory(listOf("folder\\", "backslash query"))

        assertEquals(listOf("folder\\name"), results.map { it.text })
    }
}
