package io.github.kemko.grimmoryuploader.upload

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
