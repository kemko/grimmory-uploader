package io.github.kemko.grimmoryuploader.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StagingStoreTest {
    @Test
    fun reconcilesOrphansAndRejectsPathsOutsideRoot() {
        val root = Files.createTempDirectory("pending").toFile()
        val active = File(root, "active").apply { writeText("x") }
        val orphan = File(root, "orphan").apply { writeText("x") }
        val store = StagingStore(root)
        store.reconcile(setOf(active.absolutePath))
        assertTrue(active.exists())
        assertFalse(orphan.exists())
        assertThrows(IllegalArgumentException::class.java) { store.cleanup(File(root.parentFile, "outside").absolutePath) }
        root.deleteRecursively()
    }

    @Test
    fun stagesContentUrisWithBoundedCopiesAndSafeNames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "source.fb2").apply { writeText("book") }
        val root = Files.createTempDirectory("pending-content").toFile()
        val uri = Uri.parse("content://books/1")
        shadowOf(context.contentResolver).registerInputStream(uri, source.inputStream())

        val staged = StagingStore(root).stage(context.contentResolver, uri, "../unsafe.fb2")

        assertEquals("book", staged.readText())
        assertFalse(staged.name.contains(".."))
        root.deleteRecursively()
    }

    @Test
    fun rejectsOversizedAndFailedContentCopiesWithoutResidue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "large.fb2").apply { writeText("12345") }
        val root = Files.createTempDirectory("pending-limit").toFile()
        val store = StagingStore(root, maxBytes = 4)
        val largeUri = Uri.parse("content://books/large")
        shadowOf(context.contentResolver).registerInputStream(largeUri, source.inputStream())

        assertThrows(StagingLimitException::class.java) {
            store.stage(context.contentResolver, largeUri, "large.fb2")
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
        val missingUri = Uri.parse("content://books/missing")
        shadowOf(context.contentResolver).registerInputStream(
            missingUri,
            object : InputStream() {
                override fun read(): Int = throw FileNotFoundException(missingUri.toString())
            },
        )
        assertThrows(FileNotFoundException::class.java) {
            store.stage(context.contentResolver, missingUri, "missing.fb2")
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun queueStagesLocalContentOffTheCallerThread() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("pending-dispatcher").toFile()
        val uri = Uri.parse("content://books/threaded")
        val readThread = AtomicReference<Thread>()
        val bytes = "book".encodeToByteArray()
        var index = 0
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            object : InputStream() {
                override fun read(): Int {
                    readThread.compareAndSet(null, Thread.currentThread())
                    return if (index < bytes.size) bytes[index++].toInt() else -1
                }
            },
        )
        val repository = UploadQueueRepository(FakeUploadJobDao(), StagingStore(root))
        val callerThread = Thread.currentThread()

        repository.persist(
            io.github.kemko.grimmoryuploader.share.IncomingInput.File(uri.toString(), "book.fb2", null),
            context.contentResolver,
        )

        assertTrue(readThread.get() != callerThread)
        root.deleteRecursively()
        Unit
    }

}
