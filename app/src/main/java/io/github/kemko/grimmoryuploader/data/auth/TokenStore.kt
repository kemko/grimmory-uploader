package io.github.kemko.grimmoryuploader.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

interface TokenStore {
    suspend fun read(): TokenPair?
    suspend fun write(tokens: TokenPair)
    suspend fun clear()
}

@Serializable
private data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

class AesGcmTokenCipher(private val key: SecretKey) {
    fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plainText)
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 12) { "Invalid encrypted token payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, encrypted.copyOfRange(0, 12)),
        )
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}

class EncryptedTokenStore(
    context: Context,
    private val json: Json = Json,
) : TokenStore {
    private val file = File(context.noBackupFilesDir, "auth.tokens")
    private val cipher = AesGcmTokenCipher(loadOrCreateKey())

    override suspend fun read(): TokenPair? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val stored = json.decodeFromString<StoredTokens>(
            cipher.decrypt(file.readBytes()).decodeToString(),
        )
        TokenPair(stored.accessToken, stored.refreshToken, stored.expiresAtMillis)
    }

    override suspend fun write(tokens: TokenPair) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val payload = StoredTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMillis)
        FileOutputStream(temporary).use { output ->
            output.write(cipher.encrypt(json.encodeToString(payload).encodeToByteArray()))
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Unable to replace encrypted token store" }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "grimmory-uploader.auth" }
}
