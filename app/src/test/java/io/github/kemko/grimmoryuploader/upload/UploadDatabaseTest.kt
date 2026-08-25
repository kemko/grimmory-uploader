package io.github.kemko.grimmoryuploader.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.upload.db.UploadDatabase
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadDatabaseTest {
    @Test
    fun persistsQueriesAndAtomicallyTransitionsJobs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "upload-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, UploadDatabase::class.java, name).build()
        val id = first.jobs().insert(job())
        assertEquals("book.fb2", first.jobs().find(id)?.displayName)
        assertEquals(1, first.jobs().byServer("https://one.example").size)
        assertEquals(1, first.jobs().observeAll().first().size)
        assertEquals(
            1,
            first.jobs().transition(
                id,
                listOf(UploadJobState.STAGED),
                UploadJobState.QUEUED,
                null,
                2,
            ),
        )
        assertEquals(
            0,
            first.jobs().transition(
                id,
                listOf(UploadJobState.STAGED),
                UploadJobState.CANCELLED,
                null,
                3,
            ),
        )
        assertEquals(1, first.jobs().updateProgress(id, "UPLOAD", 5, 10, 4))
        first.close()

        val reopened = Room.databaseBuilder(context, UploadDatabase::class.java, name).build()
        val persisted = reopened.jobs().find(id)
        assertNotNull(persisted)
        assertEquals(UploadJobState.QUEUED, persisted?.state)
        assertEquals(5L, persisted?.progressCurrent)
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun configuresDraftAndConfirmsCleartextWithGuardedQueries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, UploadDatabase::class.java).build()
        val id = database.jobs().insert(job(serverUrl = ""))
        assertEquals(id, database.jobs().pendingIntake()?.id)
        assertEquals(
            1,
            database.jobs().configure(id, "https://one.example", 7, 9, false, false, 2),
        )
        assertEquals(
            1,
            database.jobs().transition(
                id,
                listOf(UploadJobState.STAGED),
                UploadJobState.AWAITING_CLEARTEXT,
                "confirm",
                3,
            ),
        )
        assertEquals(1, database.jobs().confirmSourceCleartext(id, 4))
        assertEquals(true, database.jobs().find(id)?.sourceCleartextConfirmed)
        database.close()
    }

    @Test
    fun migratesVersionOneJavaSchemaWithoutLosingQueuedJobs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "upload-v1-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { versionOne ->
            versionOne.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `upload_jobs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sourceUri` TEXT,
                    `sourceUrl` TEXT,
                    `stagedPath` TEXT,
                    `displayName` TEXT,
                    `mimeType` TEXT,
                    `state` TEXT,
                    `serverUrl` TEXT,
                    `libraryId` INTEGER NOT NULL,
                    `pathId` INTEGER NOT NULL,
                    `recompressEpub` INTEGER NOT NULL,
                    `failureReason` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            versionOne.execSQL(
                """
                INSERT INTO upload_jobs (
                    sourceUrl, displayName, state, serverUrl, libraryId, pathId,
                    recompressEpub, createdAt, updatedAt
                ) VALUES ('https://books.example/book.fb2', 'book.fb2', 'QUEUED',
                    'https://one.example', 1, 1, 1, 1, 1)
                """.trimIndent(),
            )
            versionOne.version = 1
        }

        val migrated = Room.databaseBuilder(context, UploadDatabase::class.java, name)
            .addMigrations(UploadDatabase.MIGRATION_1_2)
            .build()
        val job = requireNotNull(migrated.jobs().find(1))
        assertEquals(UploadJobState.QUEUED, job.state)
        assertEquals("book.fb2", job.displayName)
        assertEquals(false, job.serverCleartextConfirmed)
        assertEquals(-1L, job.progressTotal)
        migrated.close()
        context.deleteDatabase(name)
        Unit
    }

    private fun job(serverUrl: String = "https://one.example") = UploadJobEntity(
        sourceUrl = "https://books.example/book.fb2",
        displayName = "book.fb2",
        serverUrl = serverUrl,
        libraryId = 1,
        pathId = 1,
        recompressEpub = true,
    )
}
