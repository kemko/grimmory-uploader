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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

interface OidcPendingStore {
    suspend fun readPendingOidc(): OidcPendingRequest?
    suspend fun writePendingOidc(request: OidcPendingRequest)
    suspend fun clearPendingOidc()
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
    tokenCipher: AesGcmTokenCipher? = null,
) : TokenStore, OidcPendingStore {
    private val file = File(context.noBackupFilesDir, "auth.tokens")
    private val pendingOidcFile = File(context.noBackupFilesDir, "auth.oidc")
    private val cipher = tokenCipher ?: AesGcmTokenCipher(loadOrCreateKey())

    override suspend fun read(): TokenPair? = withContext(Dispatchers.IO) {
        readEncrypted<StoredTokens>(file)?.let {
            TokenPair(it.accessToken, it.refreshToken, it.expiresAtMillis)
        }
    }

    override suspend fun write(tokens: TokenPair) = withContext(Dispatchers.IO) {
        val payload = StoredTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMillis)
        writeEncrypted(file, payload)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        Unit
    }

    override suspend fun readPendingOidc(): OidcPendingRequest? = withContext(Dispatchers.IO) {
        readEncrypted<OidcPendingRequest>(pendingOidcFile)
    }

    override suspend fun writePendingOidc(request: OidcPendingRequest) = withContext(Dispatchers.IO) {
        writeEncrypted(pendingOidcFile, request)
    }

    override suspend fun clearPendingOidc() = withContext(Dispatchers.IO) {
        pendingOidcFile.delete()
        Unit
    }

    private inline fun <reified T> readEncrypted(source: File): T? {
        if (!source.exists()) return null
        return runCatching {
            json.decodeFromString<T>(cipher.decrypt(source.readBytes()).decodeToString())
        }.getOrElse {
            source.delete()
            null
        }
    }

    private inline fun <reified T> writeEncrypted(target: File, value: T) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(cipher.encrypt(json.encodeToString(value).encodeToByteArray()))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
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
