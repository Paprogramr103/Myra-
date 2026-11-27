package com.myra.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatEntity::class,
        DiagnosticLogEntity::class,
        ParkingSpotEntity::class,
        PairedDeviceEntity::class,
        UserMemoryEntity::class,
        EmotionalMemoryEntity::class,
        PendingMemoryEntity::class,
        ActionAuditEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MyraDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao
    abstract fun parkingSpotDao(): ParkingSpotDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun emotionalMemoryDao(): EmotionalMemoryDao
    abstract fun pendingMemoryDao(): PendingMemoryDao
    abstract fun actionAuditDao(): ActionAuditDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS user_memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, category TEXT NOT NULL, memoryKey TEXT NOT NULL, value TEXT NOT NULL, sourceText TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, userConfirmed INTEGER NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_user_memories_memoryKey ON user_memories(memoryKey)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS emotional_memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, memoryKey TEXT NOT NULL, emotion TEXT NOT NULL, context TEXT NOT NULL, sourceText TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_emotional_memories_memoryKey ON emotional_memories(memoryKey)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, category TEXT NOT NULL, memoryKey TEXT NOT NULL, value TEXT NOT NULL, sourceText TEXT NOT NULL, confidence REAL NOT NULL, proposedAt INTEGER NOT NULL)"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_memories_memoryKey ON pending_memories(memoryKey)")
                // Preserve previously saved emotional moments while moving them out of stable facts.
                database.execSQL(
                    """INSERT OR IGNORE INTO emotional_memories (memoryKey, emotion, context, sourceText, confidence, createdAt)
                       SELECT memoryKey,
                              CASE WHEN instr(value, ';') > 0 THEN substr(value, 9, instr(value, ';') - 9) ELSE 'unknown' END,
                              CASE WHEN instr(value, 'context=') > 0 THEN substr(value, instr(value, 'context=') + 8) ELSE value END,
                              sourceText, confidence, createdAt
                       FROM user_memories WHERE category = 'emotion'"""
                )
                database.execSQL("DELETE FROM user_memories WHERE category = 'emotion'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS action_audit (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, commandType TEXT NOT NULL, parameterNames TEXT NOT NULL, success INTEGER NOT NULL, resultSummary TEXT NOT NULL, timestamp INTEGER NOT NULL)"
                )
            }
        }

        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        )

        @Volatile
        private var instance: MyraDatabase? = null

        fun getInstance(context: Context): MyraDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val builder = Room.databaseBuilder(
                        context.applicationContext,
                        MyraDatabase::class.java,
                        "myra_assistant.db",
                    ).addMigrations(*ALL_MIGRATIONS)

                    // Robolectric runs on a host JVM and cannot load SQLCipher's Android JNI
                    // library. Unit tests use the platform SQLite driver; on every real Android
                    // device/emulator, the encrypted SQLCipher path remains mandatory.
                    val database = if (android.os.Build.FINGERPRINT == "robolectric") {
                        builder.build()
                    } else {
                        val databaseKey = MyraDatabaseEncryptionMigration.prepare(context.applicationContext)
                        builder.openHelperFactory(SupportOpenHelperFactory(databaseKey)).build()
                    }
                    database.also { instance = it }
                }
            }
        }
    }
}
