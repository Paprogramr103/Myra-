package com.myra.assistant.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MyraDatabaseEncryptionMigrationTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseFile = context.getDatabasePath("myra_assistant.db")
        context.deleteDatabase("myra_assistant.db")
        File(databaseFile.parentFile, "myra_assistant.db-wal").delete()
        File(databaseFile.parentFile, "myra_assistant.db-shm").delete()
        File(databaseFile.parentFile, "myra_assistant.db.plaintext-backup").delete()
        File(databaseFile.parentFile, "myra_assistant.db.encrypted-tmp").delete()
        context.getSharedPreferences("myra_database_key_envelope", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("myra_database_encryption_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.deleteDatabase("myra_assistant.db")
        File(databaseFile.parentFile, "myra_assistant.db.plaintext-backup").delete()
        File(databaseFile.parentFile, "myra_assistant.db.encrypted-tmp").delete()
        context.getSharedPreferences("myra_database_key_envelope", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("myra_database_encryption_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun migrationEncryptsDatabaseAndPreservesRowsAndSchemaVersion() {
        createPlaintextDatabase()

        val key = MyraDatabaseEncryptionMigration.prepare(context)
        val encrypted = CipherSQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            key,
            null,
            CipherSQLiteDatabase.OPEN_READWRITE,
            null,
            null,
        )
        try {
            assertTrue(encrypted.isDatabaseIntegrityOk)
            assertEquals(7, encrypted.version)
            encrypted.rawQuery("SELECT value FROM migration_probe", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep-me", cursor.getString(0))
            }
        } finally {
            encrypted.close()
        }

        val reopenedKey = MyraDatabaseEncryptionMigration.prepare(context)
        assertArrayEquals(key, reopenedKey)
        assertFalse(File(databaseFile.parentFile, "myra_assistant.db.plaintext-backup").exists())
    }

    @Test
    fun interruptedRenameRestoresOriginalBeforeRetryingMigration() {
        createPlaintextDatabase()
        val backup = File(databaseFile.parentFile, "myra_assistant.db.plaintext-backup")
        assertTrue(databaseFile.renameTo(backup))

        val key = MyraDatabaseEncryptionMigration.prepare(context)
        val encrypted = CipherSQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            key,
            null,
            CipherSQLiteDatabase.OPEN_READWRITE,
            null,
            null,
        )
        try {
            assertTrue(encrypted.isDatabaseIntegrityOk)
            encrypted.rawQuery("SELECT value FROM migration_probe", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep-me", cursor.getString(0))
            }
        } finally {
            encrypted.close()
        }
    }

    @Test
    fun invalidPlaintextFileIsNeverReplacedWithAnEmptyDatabase() {
        databaseFile.parentFile?.mkdirs()
        val original = "not-a-sqlite-database".toByteArray()
        databaseFile.writeBytes(original)

        val result = runCatching { MyraDatabaseEncryptionMigration.prepare(context) }

        assertTrue(result.isFailure)
        assertArrayEquals(original, databaseFile.readBytes())
    }

    private fun createPlaintextDatabase() {
        databaseFile.parentFile?.mkdirs()
        val database = PlatformSQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            database.execSQL("CREATE TABLE migration_probe (value TEXT NOT NULL)")
            database.execSQL("INSERT INTO migration_probe(value) VALUES ('keep-me')")
            database.version = 7
        } finally {
            database.close()
        }
    }
}
