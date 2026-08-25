package io.github.kemko.grimmoryuploader.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AuthInterceptor(private val tokens: AccessTokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (isAuthenticationEndpoint(original.url.encodedPath)) return chain.proceed(original)

        val authenticated = original.withBearer(runBlocking { tokens.validAccessToken() })
        val response = chain.proceed(authenticated)
        if (response.code != 401 || original.header(RETRY_HEADER) != null) return response

        val refreshed = runCatching { runBlocking { tokens.refresh(force = true) } }.getOrNull()
            ?: return response
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

    private companion object { const val RETRY_HEADER = "X-Grimmory-Auth-Retry" }
}
