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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `upload_jobs_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceUri` TEXT,
                        `sourceUrl` TEXT,
                        `stagedPath` TEXT,
                        `displayName` TEXT NOT NULL,
                        `mimeType` TEXT,
                        `state` TEXT NOT NULL,
                        `serverUrl` TEXT NOT NULL,
                        `libraryId` INTEGER NOT NULL,
                        `pathId` INTEGER NOT NULL,
                        `recompressEpub` INTEGER NOT NULL,
                        `serverCleartextConfirmed` INTEGER NOT NULL,
                        `sourceCleartextConfirmed` INTEGER NOT NULL,
                        `failureReason` TEXT,
                        `progressStage` TEXT,
                        `progressCurrent` INTEGER NOT NULL,
                        `progressTotal` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `upload_jobs_new` (
                        `id`, `sourceUri`, `sourceUrl`, `stagedPath`, `displayName`, `mimeType`, `state`,
                        `serverUrl`, `libraryId`, `pathId`, `recompressEpub`, `serverCleartextConfirmed`,
                        `sourceCleartextConfirmed`, `failureReason`, `progressStage`, `progressCurrent`,
                        `progressTotal`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `id`, `sourceUri`, `sourceUrl`, `stagedPath`, COALESCE(NULLIF(`displayName`, ''), 'book'),
                        `mimeType`,
                        CASE
                            WHEN `displayName` IS NOT NULL AND `serverUrl` IS NOT NULL AND `state` IN (
                                'STAGED', 'AWAITING_AUTH', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'
                            ) THEN `state`
                            ELSE 'FAILED'
                        END,
                        COALESCE(`serverUrl`, ''), `libraryId`, `pathId`, `recompressEpub`, 0, 0,
                        CASE
                            WHEN `displayName` IS NOT NULL AND `serverUrl` IS NOT NULL AND `state` IN (
                                'STAGED', 'AWAITING_AUTH', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'
                            )
                                THEN `failureReason`
                            ELSE COALESCE(`failureReason`, 'Recovered invalid upload record')
                        END,
                        NULL, 0, -1, `createdAt`, `updatedAt`
                    FROM `upload_jobs`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `upload_jobs`")
                db.execSQL("ALTER TABLE `upload_jobs_new` RENAME TO `upload_jobs`")
            }
        }
    }
}
