package io.github.kemko.grimmoryuploader.data.network

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

enum class ApiErrorSource {
    GRIMMORY,
    OIDC_PROVIDER,
}

class ApiException(
    val statusCode: Int,
    message: String,
    val source: ApiErrorSource = ApiErrorSource.GRIMMORY,
    val errorCode: String? = null,
    val errorDescription: String? = null,
) : IllegalStateException(message)

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
            source = ApiErrorSource.GRIMMORY,
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
            source = ApiErrorSource.GRIMMORY,
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
            source = ApiErrorSource.GRIMMORY,
        )
    }

    private suspend inline fun <reified T> executeJson(
        request: Request,
        source: ApiErrorSource = ApiErrorSource.GRIMMORY,
    ): T = json.decodeFromString(execute(request, source))

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
        val nestedOAuth = envelope?.details?.asOAuthError()
        val oauth = directOAuth ?: nestedOAuth
        val description = oauth?.errorDescription.safeMessage()
        val message = description ?: envelope?.message.safeMessage() ?: oauth?.error.safeMessage() ?: fallback
        return ApiException(
            statusCode = statusCode,
            message = message,
            source = source,
            errorCode = oauth?.error.safeMessage(),
            errorDescription = description,
        )
    }

    private fun JsonElement.asOAuthError(): OAuthErrorResponse? =
        (this as? JsonObject)
            ?.let { runCatching { json.decodeFromJsonElement<OAuthErrorResponse>(it) }.getOrNull() }
            ?.takeIf { it.error != null || it.errorDescription != null }

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
    }
}
