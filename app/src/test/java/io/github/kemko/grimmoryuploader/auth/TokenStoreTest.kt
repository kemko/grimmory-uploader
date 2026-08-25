package io.github.kemko.grimmoryuploader.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.data.auth.EncryptedTokenStore
import io.github.kemko.grimmoryuploader.data.auth.OidcPendingRequest
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TokenStoreTest {
    @Test
    fun aesGcmPayloadDoesNotContainPlaintext() {
        val secret = "access-token|refresh-token".encodeToByteArray()
        val cipher = AesGcmTokenCipher(SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"))
        val encrypted = cipher.encrypt(secret)
        assertFalse(encrypted.decodeToString().contains("access-token"))
        assertArrayEquals(secret, cipher.decrypt(encrypted))
    }

    @Test
    fun encryptedStoreSurvivesRecreationAndDropsCorruptPayloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tokenFile = context.noBackupFilesDir.resolve("auth.tokens").apply { delete() }
        val oidcFile = context.noBackupFilesDir.resolve("auth.oidc").apply { delete() }
        val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        val cipher = AesGcmTokenCipher(key)
        val store = EncryptedTokenStore(context, tokenCipher = cipher)
        val tokens = TokenPair("access-secret", "refresh-secret", 42)
        val pending = OidcPendingRequest(
            "state",
            "verifier",
            "nonce",
            "app:/callback",
            "https://one.example",
        )

        store.write(tokens)
        store.writePendingOidc(pending)
        assertTrue(tokenFile.parentFile == context.noBackupFilesDir)
        assertFalse(tokenFile.readBytes().decodeToString().contains("access-secret"))

        val recreated = EncryptedTokenStore(context, tokenCipher = cipher)
        assertEquals(tokens, recreated.read())
        assertEquals(pending, recreated.readPendingOidc())
        tokenFile.writeText("corrupt")
        assertNull(recreated.read())
        assertFalse(tokenFile.exists())

        recreated.clearPendingOidc()
        assertFalse(oidcFile.exists())
        recreated.clear()
    }
}
