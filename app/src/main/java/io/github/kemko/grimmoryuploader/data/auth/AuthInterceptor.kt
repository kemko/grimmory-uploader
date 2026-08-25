package io.github.kemko.grimmoryuploader.data.auth

import kotlinx.coroutines.runBlocking
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AuthInterceptor(
    private val tokens: AccessTokenProvider,
    private val trustedServer: suspend () -> ServerUrl?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val server = runBlocking { trustedServer() }
        if (server == null || !isTrusted(original, server)) return chain.proceed(original)
        if (isAuthenticationEndpoint(original.url.encodedPath)) return chain.proceed(original)

        val accessToken = runBlocking { tokens.validAccessToken() }
        if (runBlocking { trustedServer() }?.normalized != server.normalized) return chain.proceed(original)
        val authenticated = original.withBearer(accessToken)
        val response = chain.proceed(authenticated)
        if (response.code != 401 || original.header(RETRY_HEADER) != null) return response

        val rejected = accessToken ?: return response
        val refreshed = runCatching { runBlocking { tokens.refresh(rejected) } }.getOrNull()
            ?: return response
        if (runBlocking { trustedServer() }?.normalized != server.normalized) return response
        response.close()
        return chain.proceed(
            original.withBearer(refreshed.accessToken).newBuilder()
                .header(RETRY_HEADER, "1")
                .build(),
        )
    }

    private fun Request.withBearer(token: String?): Request = newBuilder().apply {
        if (token.isNullOrBlank()) removeHeader("Authorization")
        else header("Authorization", "Bearer $token")
    }.build()

    private fun isAuthenticationEndpoint(path: String): Boolean =
        path.endsWith("/auth/login") || path.endsWith("/auth/refresh") ||
            path.endsWith("/auth/oidc/mobile/callback")

    private fun isTrusted(request: Request, server: ServerUrl): Boolean {
        val url = request.url
        val base = server.url
        val prefix = base.encodedPath.trimEnd('/')
        return url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            (prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/"))
    }

    private companion object { const val RETRY_HEADER = "X-Grimmory-Auth-Retry" }
}
