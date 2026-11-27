package com.myra.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myra.assistant.data.db.MyraDatabase
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
class MyraDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun versionTwoMigratesStableAndEmotionalMemoriesToVersionFour() = runBlocking {
        val name = "myra-migration-v2-test.db"
        createLegacyDatabase(name, version = 2, includeMemoryTable = true)
        val database = openCurrentDatabase(name)
        try {
            database.openHelper.writableDatabase
            val stable = database.userMemoryDao().observeMemories().first()
            val emotional = database.emotionalMemoryDao().observeAll().first()

            assertEquals(1, stable.size)
            assertEquals("Likes tea", stable.single().value)
            assertTrue(stable.single().userConfirmed)
            assertEquals(1, emotional.size)
            assertEquals("happy", emotional.single().emotion)
            assertEquals("Celebrated a good day", emotional.single().context)

            database.actionAuditDao().insert(
                com.myra.assistant.data.db.ActionAuditEntity(
                    commandType = "MIGRATION_CHECK",
                    parameterNames = "none",
                    success = true,
                    resultSummary = "table exists",
                ),
            )
            assertEquals(1, database.actionAuditDao().observeRecent().first().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionOneMigratesThroughEveryRegisteredStepWithoutLosingChat() = runBlocking {
        val name = "myra-migration-v1-test.db"
        createLegacyDatabase(name, version = 1, includeMemoryTable = false)
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            legacy.execSQL(
                "INSERT INTO chat_messages (text, isUser, timestamp, source) VALUES ('old chat survives', 1, 123456789, 'text')",
            )
        } finally {
            legacy.close()
        }

        val database = openCurrentDatabase(name)
        try {
            database.openHelper.writableDatabase
            val messages = database.chatDao().observeMessages().first()
            assertEquals(1, messages.size)
            assertEquals("old chat survives", messages.single().text)
            assertTrue(database.userMemoryDao().observeMemories().first().isEmpty())
            assertTrue(database.emotionalMemoryDao().observeAll().first().isEmpty())
            assertTrue(database.pendingMemoryDao().observeAll().first().isEmpty())
            assertTrue(database.actionAuditDao().observeRecent().first().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun openCurrentDatabase(name: String): MyraDatabase =
        Room.databaseBuilder(context, MyraDatabase::class.java, name)
            .addMigrations(*MyraDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun createLegacyDatabase(name: String, version: Int, includeMemoryTable: Boolean) {
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TABLE chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, isUser INTEGER NOT NULL, timestamp INTEGER NOT NULL, source TEXT NOT NULL)")
            db.execSQL("CREATE TABLE diagnostic_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, message TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE parking_spots (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, note TEXT NOT NULL, timestamp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE paired_devices (deviceId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, deviceType TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, sharedKeyHex TEXT NOT NULL, pairedAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL)")
            if (includeMemoryTable) {
                db.execSQL("CREATE TABLE user_memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, category TEXT NOT NULL, memoryKey TEXT NOT NULL, value TEXT NOT NULL, sourceText TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, userConfirmed INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_user_memories_memoryKey ON user_memories(memoryKey)")
                db.execSQL("INSERT INTO user_memories (category, memoryKey, value, sourceText, confidence, createdAt, updatedAt, userConfirmed) VALUES ('preference', 'preference:tea', 'Likes tea', 'I like tea', 0.95, 1000, 1000, 1)")
                db.execSQL("INSERT INTO user_memories (category, memoryKey, value, sourceText, confidence, createdAt, updatedAt, userConfirmed) VALUES ('emotion', 'emotion:good-day', 'emotion=happy; context=Celebrated a good day', 'I felt happy', 0.96, 2000, 2000, 1)")
            }
            db.version = version
        } finally {
            db.close()
        }
    }
}
