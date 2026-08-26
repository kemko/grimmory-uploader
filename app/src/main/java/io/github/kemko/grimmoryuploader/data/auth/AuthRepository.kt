package io.github.kemko.grimmoryuploader.data.auth

import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.OidcCallbackRequest
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.network.TokenResponse
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AccessTokenProvider {
    suspend fun validAccessToken(): String?

    suspend fun refresh(rejectedAccessToken: String): TokenPair?
}

class AuthRepository(
    private val api: GrimmoryApi,
    private val tokenStore: TokenStore,
    private val currentServerUrl: suspend () -> String,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : AccessTokenProvider {
    private val tokenMutex = Mutex()
    private val expirySkewMillis = 30_000L

    suspend fun login(
        username: String,
        password: String,
    ): TokenPair =
        tokenMutex.withLock {
            val serverUrl = currentServerUrl()
            save(api.login(username, password), null, serverUrl)
        }

    override suspend fun validAccessToken(): String? {
        val serverUrl = currentServerUrl()
        val current = tokenMutex.withLock { tokensFor(serverUrl) } ?: return null
        if (current.expiresAtMillis > nowMillis() + expirySkewMillis) {
            return current.accessToken
        }
        return refresh(current.accessToken)?.accessToken
    }

    override suspend fun refresh(rejectedAccessToken: String): TokenPair? =
        tokenMutex.withLock {
            val serverUrl = currentServerUrl()
            val current = tokensFor(serverUrl) ?: return@withLock null
            if (current.accessToken != rejectedAccessToken) {
                return@withLock current
            }
            try {
                return@withLock save(api.refresh(current.refreshToken), current, serverUrl)
            } catch (error: ApiException) {
                if (error.statusCode == 401) tokenStore.clear()
                throw error
            }
        }

    suspend fun exchangeOidc(
        serverUrl: String,
        request: OidcCallbackRequest,
    ): TokenPair =
        tokenMutex.withLock {
            check(currentServerUrl() == serverUrl) { "Grimmory server changed during OIDC sign-in" }
            save(api.oidcCallback(request), tokensFor(serverUrl), serverUrl)
        }

    suspend fun serverUrl(): String = currentServerUrl()

    suspend fun healthcheck() = api.healthcheck()

    suspend fun publicSettings(): PublicSettings = api.publicSettings()

    suspend fun logout() = tokenMutex.withLock { tokenStore.clear() }

    suspend fun invalidateForServerChange() = tokenMutex.withLock { tokenStore.clear() }

    suspend fun isAuthenticated(currentUser: suspend () -> Unit): Boolean =
        try {
            if (validAccessToken() == null) {
                false
            } else {
                currentUser()
                true
            }
        } catch (error: ApiException) {
            if (error.statusCode != 401) throw error
            logout()
            false
        }

    private suspend fun tokensFor(serverUrl: String): TokenPair? {
        val tokens = tokenStore.read() ?: return null
        if (tokens.serverUrl == serverUrl) return tokens
        tokenStore.clear()
        return null
    }

    private suspend fun save(
        response: TokenResponse,
        previous: TokenPair?,
        serverUrl: String,
    ): TokenPair {
        check(currentServerUrl() == serverUrl) { "Grimmory server changed during authentication" }
        val refreshToken =
            response.refreshToken ?: previous?.refreshToken
                ?: error("Grimmory response did not contain a refresh token")
        val expiresAt =
            response.expiresInSeconds?.let { nowMillis() + it * 1_000L }
                ?: (nowMillis() + 3_600_000L)
        val tokens = TokenPair(response.accessToken, refreshToken, expiresAt, serverUrl)
        tokenStore.write(tokens)
        return tokens
    }
}

data class AuthModeDecision(
    val mode: AuthMode,
    val requiresUserChoice: Boolean = false,
)

object AuthModeSelector {
    fun select(
        requested: AuthMode,
        publicSettings: PublicSettings?,
        manualFallback: AuthMode = AuthMode.LOCAL,
    ): AuthModeDecision {
        if (publicSettings == null) {
            return AuthModeDecision(if (requested == AuthMode.AUTO) manualFallback else requested)
        }
        if (publicSettings.oidcForceOnlyMode) return AuthModeDecision(AuthMode.OIDC)
        if (!publicSettings.oidcEnabled) return AuthModeDecision(AuthMode.LOCAL)
        return when (requested) {
            AuthMode.LOCAL -> AuthModeDecision(AuthMode.LOCAL)
            AuthMode.OIDC -> AuthModeDecision(AuthMode.OIDC)
            AuthMode.AUTO -> AuthModeDecision(AuthMode.AUTO, requiresUserChoice = true)
        }
    }
}
