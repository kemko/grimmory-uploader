package io.github.kemko.grimmoryuploader.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.kemko.grimmoryuploader.auth.TestTokenStore
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppSettingsRepositoryTest {
    @Test
    fun storesSafeDefaultsAndNormalizedSettings() =
        runBlocking {
            val file = Files.createTempDirectory("settings").resolve("settings.preferences_pb").toFile()
            var changed = false
            val repository =
                AppSettingsRepository(
                    PreferenceDataStoreFactory.create { file },
                    onServerChanged = { changed = true },
                )

            assertEquals(1, repository.current().libraryId)
            assertEquals(1, repository.current().pathId)
            assertTrue(repository.current().recompressEpub)
            repository.setServerUrl("https://Example.com/base/")
            repository.setLibraryId(7)
            repository.setPathId(9)
            repository.setRecompressEpub(false)
            repository.setAuthMode(AuthMode.OIDC)
            repository.setServerUrl("https://other.example")

            val value = repository.current()
            assertEquals("https://other.example", value.serverUrl)
            assertEquals(7, value.libraryId)
            assertEquals(9, value.pathId)
            assertFalse(value.recompressEpub)
            assertEquals(AuthMode.OIDC, value.authMode)
            assertTrue(changed)
        }

    @Test
    fun changingServerCanClearCredentials() =
        runBlocking {
            val file = Files.createTempDirectory("settings").resolve("settings.preferences_pb").toFile()
            val tokenStore = TestTokenStore()
            val repository =
                AppSettingsRepository(
                    PreferenceDataStoreFactory.create { file },
                    onServerChanged = { tokenStore.clear() },
                )
            tokenStore.write(
                io.github.kemko.grimmoryuploader.data.auth
                    .TokenPair("a", "r", 0),
            )
            repository.setServerUrl("https://one.example")
            repository.setServerUrl("https://two.example")
            assertEquals(null, tokenStore.read())
        }

    @Test
    fun cleartextNeedsExplicitConfirmation() =
        runBlocking {
            val file = Files.createTempDirectory("settings").resolve("settings.preferences_pb").toFile()
            val repository = AppSettingsRepository(PreferenceDataStoreFactory.create { file })
            repository.setServerUrl("http://intranet.example")
            try {
                repository.requireCleartextConfirmation()
                throw AssertionError("Expected cleartext confirmation requirement")
            } catch (_: IllegalStateException) {
                repository.setHttpConfirmed(true)
                repository.requireCleartextConfirmation()
            }
            assertTrue(repository.isCleartextConfirmed("http://intranet.example"))
            assertFalse(repository.isCleartextConfirmed("http://other.example"))
        }

    @Test
    fun appliesOnlyFullyValidatedConfiguration() =
        runBlocking {
            val file = Files.createTempDirectory("settings-atomic").resolve("settings.preferences_pb").toFile()
            val repository = AppSettingsRepository(PreferenceDataStoreFactory.create { file })
            repository.applyConfiguration("https://one.example", 2, 3, false, AuthMode.LOCAL, false)

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.applyConfiguration("https://two.example", 0, 4, true, AuthMode.OIDC, false)
                }
            }

            val current = repository.current()
            assertEquals("https://one.example", current.serverUrl)
            assertEquals(2, current.libraryId)
            assertEquals(3, current.pathId)
            assertEquals(AuthMode.LOCAL, current.authMode)
        }
}
