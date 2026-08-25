package io.github.kemko.grimmoryuploader.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class ApiException(val statusCode: Int, message: String) : IllegalStateException(message)

class GrimmoryApi(
    private val client: OkHttpClient,
    private val serverUrl: suspend () -> ServerUrl,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun healthcheck(): HealthcheckResponse = get("api/v1/healthcheck")
    suspend fun publicSettings(): PublicSettings = get("api/v1/public-settings")
    suspend fun login(username: String, password: String): TokenResponse =
        post("api/v1/auth/login", LoginRequest(username, password))
    suspend fun refresh(refreshToken: String): TokenResponse =
        post("api/v1/auth/refresh", RefreshRequest(refreshToken))
    suspend fun currentUser(): UserResponse = get("api/v1/users/me")
    suspend fun oidcState(): OidcStateResponse = get("api/v1/auth/oidc/state")

    suspend fun oidcDiscovery(issuer: String): OidcDiscoveryResponse {
        val issuerUrl = ServerUrl.parse(issuer)
        require(!issuerUrl.isCleartext || issuerUrl.url.host in setOf("127.0.0.1", "localhost")) {
            "OIDC discovery must use HTTPS"
        }
        return executeJson(
            Request.Builder().url(issuerUrl.endpoint(".well-known/openid-configuration")).get().build(),
        )
    }

    suspend fun oidcCallback(request: OidcCallbackRequest): TokenResponse =
        post("api/v1/auth/oidc/mobile/callback", request)

    suspend fun upload(
        libraryId: Int,
        pathId: Int,
        fileName: String,
        contentType: String,
        content: RequestBody,
    ): UploadResponse {
        require(fileName.isNotBlank() && fileName.none { it == '\u0000' || it == '/' || it == '\\' }) {
            "Unsafe upload file name"
        }
        val typedContent = object : RequestBody() {
            private val mediaType = contentType.toMediaType()
            override fun contentType() = mediaType
            override fun contentLength() = content.contentLength()
            override fun isOneShot() = content.isOneShot()
            override fun isDuplex() = content.isDuplex()
            override fun writeTo(sink: okio.BufferedSink) = content.writeTo(sink)
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, typedContent)
            .build()
        val url = serverUrl().endpoint("api/v1/files/upload").newBuilder()
            .addQueryParameter("libraryId", libraryId.toString())
            .addQueryParameter("pathId", pathId.toString())
            .build()
        return executeJson(Request.Builder().url(url).post(multipart).build())
    }

    private suspend inline fun <reified T> get(path: String): T = executeJson(
        Request.Builder().url(serverUrl().endpoint(path)).get().build(),
    )

    private suspend inline fun <reified T, reified B> post(path: String, body: B): T {
        val requestBody = json.encodeToString(body).toRequestBody("application/json".toMediaType())
        return executeJson(
            Request.Builder().url(serverUrl().endpoint(path)).post(requestBody).build(),
        )
    }

    private suspend inline fun <reified T> executeJson(request: Request): T {
        return client.newCall(request).await().use { response ->
            val source = response.body.source()
            val buffer = Buffer()
            var remaining = MAX_RESPONSE_BYTES + 1L
            while (remaining > 0) {
                val read = source.read(buffer, minOf(remaining, DEFAULT_BUFFER_SIZE.toLong()))
                if (read < 0) break
                remaining -= read
            }
            val bytes = buffer.readByteArray()
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw ApiException(response.code.takeIf { it >= 400 } ?: 502, "Grimmory response is too large")
            }
            val text = bytes.decodeToString()
            if (!response.isSuccessful) {
                throw ApiException(response.code, text.take(512).ifBlank { "Grimmory request failed" })
            }
            json.decodeFromString<T>(text)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}
