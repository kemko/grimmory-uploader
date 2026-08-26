package io.github.kemko.grimmoryuploader.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException

enum class ApiErrorSource {
    GRIMMORY,
    OIDC_PROVIDER,
}

class ApiException(
    val statusCode: Int?,
    message: String,
    val source: ApiErrorSource = ApiErrorSource.GRIMMORY,
    val errorCode: String? = null,
    val errorDescription: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class GrimmoryApi(
    client: OkHttpClient,
    private val serverUrl: suspend () -> ServerUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun healthcheck() =
        executeSuccess(
            Request
                .Builder()
                .url(serverUrl().endpoint("api/v1/healthcheck"))
                .get()
                .build(),
        )

    suspend fun publicSettings(): PublicSettings = get("api/v1/public-settings")

    suspend fun login(
        username: String,
        password: String,
    ): TokenResponse = post("api/v1/auth/login", LoginRequest(username, password))

    suspend fun refresh(refreshToken: String): TokenResponse = post("api/v1/auth/refresh", RefreshRequest(refreshToken))

    suspend fun currentUser(): UserResponse = get("api/v1/users/me")

    suspend fun oidcState(): OidcStateResponse = get("api/v1/auth/oidc/state")

    suspend fun oidcDiscovery(issuer: String): OidcDiscoveryResponse {
        val issuerUrl = ServerUrl.parse(issuer)
        require(!issuerUrl.isCleartext || issuerUrl.url.host in setOf("127.0.0.1", "localhost")) {
            "OIDC discovery must use HTTPS"
        }
        return executeJson(
            Request
                .Builder()
                .url(issuerUrl.endpoint(".well-known/openid-configuration"))
                .get()
                .build(),
            source = ApiErrorSource.OIDC_PROVIDER,
        )
    }

    suspend fun oidcCallback(request: OidcCallbackRequest): TokenResponse {
        val url =
            serverUrl()
                .endpoint("api/v1/auth/oidc/mobile/callback")
                .newBuilder()
                .addQueryParameter("code", request.code)
                .addQueryParameter("code_verifier", request.codeVerifier)
                .addQueryParameter("redirect_uri", request.redirectUri)
                .addQueryParameter("nonce", request.nonce)
                .addQueryParameter("state", request.state)
                .build()
        return executeJson(
            Request
                .Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody())
                .build(),
        )
    }

    suspend fun upload(
        libraryId: Int,
        pathId: Int,
        fileName: String,
        contentType: String,
        content: RequestBody,
    ) {
        require(fileName.isNotBlank() && fileName.none { it == '\u0000' || it == '/' || it == '\\' }) {
            "Unsafe upload file name"
        }
        val typedContent =
            object : RequestBody() {
                private val mediaType = contentType.toMediaType()

                override fun contentType() = mediaType

                override fun contentLength() = content.contentLength()

                override fun isOneShot() = content.isOneShot()

                override fun isDuplex() = content.isDuplex()

                override fun writeTo(sink: okio.BufferedSink) = content.writeTo(sink)
            }
        val multipart =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, typedContent)
                .build()
        val url =
            serverUrl()
                .endpoint("api/v1/files/upload")
                .newBuilder()
                .addQueryParameter("libraryId", libraryId.toString())
                .addQueryParameter("pathId", pathId.toString())
                .build()
        executeSuccess(
            Request
                .Builder()
                .url(url)
                .post(multipart)
                .build(),
        )
    }

    private suspend inline fun <reified T> get(path: String): T =
        executeJson(
            Request
                .Builder()
                .url(serverUrl().endpoint(path))
                .get()
                .build(),
        )

    private suspend inline fun <reified T, reified B> post(
        path: String,
        body: B,
    ): T {
        val requestBody = json.encodeToString(body).toRequestBody("application/json".toMediaType())
        return executeJson(
            Request
                .Builder()
                .url(serverUrl().endpoint(path))
                .post(requestBody)
                .build(),
        )
    }

    private suspend inline fun <reified T> executeJson(
        request: Request,
        source: ApiErrorSource = ApiErrorSource.GRIMMORY,
    ): T =
        try {
            json.decodeFromString(execute(request, source))
        } catch (error: IOException) {
            if (source == ApiErrorSource.OIDC_PROVIDER) throw sourceFailure(source, error)
            throw error
        } catch (error: SerializationException) {
            if (source == ApiErrorSource.OIDC_PROVIDER) throw sourceFailure(source, error)
            throw error
        }

    private suspend fun executeSuccess(request: Request) {
        execute(request, ApiErrorSource.GRIMMORY)
    }

    private suspend fun execute(
        request: Request,
        source: ApiErrorSource,
    ): String =
        client.newCall(request).await().use { response ->
            val bodySource = response.body.source()
            val buffer = Buffer()
            var remaining = MAX_RESPONSE_BYTES + 1L
            while (remaining > 0) {
                val read = bodySource.read(buffer, minOf(remaining, DEFAULT_BUFFER_SIZE.toLong()))
                if (read < 0) break
                remaining -= read
            }
            val bytes = buffer.readByteArray()
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw ApiException(
                    statusCode = response.code.takeIf { it >= 400 } ?: 502,
                    message = "${source.label} response is too large",
                    source = source,
                )
            }
            val text = bytes.decodeToString()
            if (!response.isSuccessful) {
                throw parseApiException(response.code, text, source)
            }
            text
        }

    private fun parseApiException(
        statusCode: Int,
        body: String,
        source: ApiErrorSource,
    ): ApiException {
        val fallback = "${source.label} request failed"
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val directOAuth = root?.asOAuthError()
        val envelope = root?.let { runCatching { json.decodeFromJsonElement<GrimmoryErrorResponse>(it) }.getOrNull() }
        val grimmoryCode =
            envelope
                ?.message
                ?.takeIf { source == ApiErrorSource.GRIMMORY }
                .grimmoryErrorCode()
        val description = directOAuth?.errorDescription.safeMessage()
        val message =
            description
                ?: if (grimmoryCode != null) {
                    GRIMMORY_AUTH_ERROR_MESSAGE
                } else {
                    envelope?.message.safeMessage()
                }
                ?: directOAuth?.error.safeMessage()
                ?: fallback
        return ApiException(
            statusCode = statusCode,
            message = message,
            source = source,
            errorCode = directOAuth?.error.safeMessage() ?: grimmoryCode,
            errorDescription = description,
        )
    }

    private fun sourceFailure(
        source: ApiErrorSource,
        cause: Throwable,
    ): ApiException =
        ApiException(
            statusCode = null,
            message = "${source.label} request failed",
            source = source,
            cause = cause,
        )

    private fun JsonElement.asOAuthError(): OAuthErrorResponse? =
        (this as? JsonObject)
            ?.let { runCatching { json.decodeFromJsonElement<OAuthErrorResponse>(it) }.getOrNull() }
            ?.takeIf { it.error != null || it.errorDescription != null }

    private fun String?.grimmoryErrorCode(): String? {
        val message = this ?: return null
        return when {
            message == "OIDC is not enabled" -> "oidc_disabled"
            message == "OIDC is not properly configured" -> "oidc_misconfigured"
            message == "Invalid redirect URI" -> "invalid_redirect_uri"
            message == "Invalid or expired OIDC state parameter" -> "invalid_state"
            message.startsWith("OIDC user '") &&
                message.endsWith("' is not provisioned and auto-provisioning is disabled") -> "user_not_provisioned"
            message.startsWith("Invalid token from OIDC provider:") -> "invalid_token"
            message.startsWith("Cannot reach OIDC provider:") -> message.oauthErrorCode() ?: "provider_unreachable"
            message.startsWith("Failed to exchange authorization code:") -> message.oauthErrorCode()
            else -> null
        }
    }

    private fun String.oauthErrorCode(): String? {
        val fieldCode =
            OAUTH_ERROR_FIELD
                .find(this)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
                ?.takeIf(OAUTH_ERROR_CODES::contains)
        return fieldCode
            ?: OAUTH_ERROR_TOKEN
                .find(this)
                ?.value
                ?.lowercase()
    }

    private fun String?.safeMessage(): String? =
        this
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_ERROR_MESSAGE_CHARS)

    private val ApiErrorSource.label: String
        get() =
            when (this) {
                ApiErrorSource.GRIMMORY -> "Grimmory"
                ApiErrorSource.OIDC_PROVIDER -> "OIDC provider"
            }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_ERROR_MESSAGE_CHARS = 512
        const val GRIMMORY_AUTH_ERROR_MESSAGE = "Grimmory authentication failed"
        val OAUTH_ERROR_CODES =
            setOf(
                "invalid_client",
                "invalid_grant",
                "invalid_request",
                "invalid_scope",
                "unauthorized_client",
                "unsupported_grant_type",
            )
        val OAUTH_ERROR_FIELD =
            Regex(
                """\\?[\"']error\\?[\"']\s*:\s*\\?[\"']([A-Za-z0-9_.:-]{1,64})\\?[\"']""",
                RegexOption.IGNORE_CASE,
            )
        val OAUTH_ERROR_TOKEN =
            Regex(
                """(?<![A-Za-z0-9_.:-])(?:${OAUTH_ERROR_CODES.joinToString("|")})(?![A-Za-z0-9_.:-])""",
                RegexOption.IGNORE_CASE,
            )
    }
}
