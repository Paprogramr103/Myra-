package com.myra.assistant.data.db

import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * One-time, fail-closed plaintext Room database migration to SQLCipher.
 *
 * The original database remains untouched while sqlcipher_export creates a separate encrypted
 * file. The encrypted file is reopened and integrity-checked before the original is renamed.
 */
internal object MyraDatabaseEncryptionMigration {

    private const val DATABASE_NAME = "myra_assistant.db"
    private const val STATE_PREFERENCES = "myra_database_encryption_state"
    private const val STATE_KEY = "database_encryption_version"
    private const val ENCRYPTED_MARKER = "sqlcipher_v1"

    @Volatile
    private var sqlCipherLoaded = false

    @Synchronized
    fun prepare(context: Context): ByteArray {
        loadSqlCipher()
        val appContext = context.applicationContext
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        val backupFile = File(databaseFile.parentFile, "$DATABASE_NAME.plaintext-backup")
        val keyStore = MyraDatabaseKeyStore(appContext)
        val state = appContext.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

        val existingKey = keyStore.getExistingKey()
        if (state.getString(STATE_KEY, null) == ENCRYPTED_MARKER) {
            val key = existingKey
                ?: throw IllegalStateException("MYRA encrypted database key is missing; database was not changed")
            if (databaseFile.exists() && !isValidEncryptedDatabase(databaseFile, key)) {
                key.fill(0)
                throw IllegalStateException("MYRA encrypted database could not be verified; database was not replaced")
            }
            cleanupPlaintextBackup(backupFile)
            return key
        }

        val key = existingKey ?: keyStore.getOrCreateForMigration()
        try {
            databaseFile.parentFile?.let { directory ->
                if (!directory.exists() && !directory.mkdirs()) error("Could not create MYRA database directory")
            }

            recoverInterruptedSwap(databaseFile, backupFile, key)

            if (!databaseFile.exists() || databaseFile.length() == 0L) {
                check(state.edit().putString(STATE_KEY, ENCRYPTED_MARKER).commit()) {
                    "Could not persist MYRA database encryption state"
                }
                return key
            }

            // A prior launch may have completed the encrypted file swap but not its state marker.
            if (isValidEncryptedDatabase(databaseFile, key)) {
                cleanupPlaintextBackup(backupFile)
                check(state.edit().putString(STATE_KEY, ENCRYPTED_MARKER).commit()) {
                    "Could not persist MYRA database encryption state"
                }
                return key
            }

            migratePlaintextCopy(databaseFile, key, backupFile)
            check(state.edit().putString(STATE_KEY, ENCRYPTED_MARKER).commit()) {
                "Encrypted database is verified, but its state marker could not be saved"
            }
            return key
        } catch (error: Exception) {
            key.fill(0)
            throw IllegalStateException(
                "MYRA database encryption migration failed safely. The original database was preserved.",
                error,
            )
        }
    }

    private fun migratePlaintextCopy(databaseFile: File, key: ByteArray, backupFile: File) {
        val tempFile = File(databaseFile.parentFile, "$DATABASE_NAME.encrypted-tmp")
        if (tempFile.exists()) check(tempFile.delete()) { "Could not remove incomplete migration file" }
        check(!backupFile.exists()) { "A previous migration backup must be recovered before retrying" }

        checkpointPlaintextDatabase(databaseFile)
        val plaintext = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null,
        )
        try {
            val databaseVersion = plaintext.version
            val keyHex = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val escapedPath = tempFile.absolutePath.replace("'", "''")
            plaintext.execSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY \"x'$keyHex'\"")
            try {
                var exportCursor: Cursor? = null
                try {
                    exportCursor = plaintext.rawQuery("SELECT sqlcipher_export('encrypted')", null)
                    check(exportCursor.moveToFirst()) { "SQLCipher export did not complete" }
                } finally {
                    exportCursor?.close()
                }
                plaintext.execSQL("PRAGMA encrypted.user_version = $databaseVersion")
            } finally {
                plaintext.execSQL("DETACH DATABASE encrypted")
            }
        } finally {
            plaintext.close()
        }

        check(tempFile.exists() && tempFile.length() > 0L) { "SQLCipher did not create the encrypted database" }
        FileOutputStream(tempFile, true).use { it.fd.sync() }
        check(isValidEncryptedDatabase(tempFile, key)) { "Encrypted copy could not be reopened or failed integrity_check" }

        check(databaseFile.renameTo(backupFile)) { "Could not preserve the original database before swap" }
        if (!tempFile.renameTo(databaseFile)) {
            check(backupFile.renameTo(databaseFile)) { "Encrypted swap failed and original database restore also failed" }
            error("Could not install encrypted database; original database was restored")
        }

        if (!isValidEncryptedDatabase(databaseFile, key)) {
            check(databaseFile.delete()) { "Could not remove failed encrypted database during rollback" }
            check(backupFile.renameTo(databaseFile)) { "Encrypted verification failed and original database restore failed" }
            error("Encrypted database verification failed; original database was restored")
        }

        cleanupPlaintextBackup(backupFile)
        File(databaseFile.parentFile, "$DATABASE_NAME-wal").delete()
        File(databaseFile.parentFile, "$DATABASE_NAME-shm").delete()
    }

    private fun recoverInterruptedSwap(databaseFile: File, backupFile: File, key: ByteArray) {
        if (!backupFile.exists()) return

        if (databaseFile.exists() && isValidEncryptedDatabase(databaseFile, key)) {
            cleanupPlaintextBackup(backupFile)
            return
        }

        check(isValidPlaintextDatabase(backupFile)) {
            "Interrupted migration backup is not readable; original files were left untouched"
        }
        if (databaseFile.exists()) {
            check(databaseFile.delete()) { "Could not move aside incomplete database before rollback" }
        }
        check(backupFile.renameTo(databaseFile)) {
            "Could not restore original database after interrupted encryption swap"
        }
    }

    private fun checkpointPlaintextDatabase(databaseFile: File) {
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null,
        )
        try {
            var cursor: Cursor? = null
            try {
                cursor = database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                if (cursor.moveToFirst() && cursor.columnCount > 0) {
                    check(cursor.getInt(0) == 0) { "Could not checkpoint existing database WAL" }
                }
            } finally {
                cursor?.close()
            }
            check(database.isDatabaseIntegrityOk) { "Original database failed integrity_check" }
        } finally {
            database.close()
        }
        File(databaseFile.parentFile, "$DATABASE_NAME-wal").delete()
        File(databaseFile.parentFile, "$DATABASE_NAME-shm").delete()
    }

    private fun isValidPlaintextDatabase(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null,
            )
            try {
                database.isDatabaseIntegrityOk
            } finally {
                database.close()
            }
        }.getOrDefault(false)
    }

    private fun isValidEncryptedDatabase(file: File, key: ByteArray): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath,
                key,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null,
            )
            try {
                database.isDatabaseIntegrityOk
            } finally {
                database.close()
            }
        }.getOrDefault(false)
    }

    private fun cleanupPlaintextBackup(backupFile: File) {
        if (backupFile.exists()) {
            check(backupFile.delete()) { "Encrypted database is valid, but plaintext backup cleanup failed" }
        }
    }

    private fun loadSqlCipher() {
        if (!sqlCipherLoaded) {
            System.loadLibrary("sqlcipher")
            sqlCipherLoaded = true
        }
    }
}
