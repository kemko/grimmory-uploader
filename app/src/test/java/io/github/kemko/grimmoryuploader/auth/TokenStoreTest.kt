package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class TokenStoreTest {
    @Test
    fun aesGcmPayloadDoesNotContainPlaintext() {
        val secret = "access-token|refresh-token".encodeToByteArray()
        val cipher = AesGcmTokenCipher(SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"))
        val encrypted = cipher.encrypt(secret)
        assertFalse(encrypted.decodeToString().contains("access-token"))
        assertArrayEquals(secret, cipher.decrypt(encrypted))
    }
}
