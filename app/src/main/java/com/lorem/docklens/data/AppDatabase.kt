package com.lorem.docklens.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        InstalledModelEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun chatDao(): ChatDao
    abstract fun installedModelDao(): InstalledModelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds the installed-model registry. Written as a real migration so that
         * existing documents and chat history are not destroyed on upgrade.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `installed_models` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `repoId` TEXT,
                        `fileName` TEXT NOT NULL,
                        `storageFileName` TEXT NOT NULL,
                        `downloadUrl` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `isReasoningModel` INTEGER NOT NULL,
                        `installedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "docklens_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
