package io.github.kemko.grimmoryuploader.upload.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UploadJobEntity::class], version = 2, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun jobs(): UploadJobDao

    companion object {
        fun create(context: Context): UploadDatabase = Room.databaseBuilder(
            context.applicationContext,
            UploadDatabase::class.java,
            "upload.db",
        ).addMigrations(MIGRATION_1_2).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN serverCleartextConfirmed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN sourceCleartextConfirmed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN progressStage TEXT")
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN progressCurrent INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN progressTotal INTEGER NOT NULL DEFAULT -1")
            }
        }
    }
}
