package io.github.kemko.grimmoryuploader.data.auth

import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.network.TokenResponse
import io.github.kemko.grimmoryuploader.data.network.UserResponse
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AccessTokenProvider {
    suspend fun validAccessToken(forceRefresh: Boolean = false): String?
    suspend fun refresh(force: Boolean = false): TokenPair?
}

class AuthRepository(
    private val api: GrimmoryApi,
    private val tokenStore: TokenStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : AccessTokenProvider {
    private val refreshMutex = Mutex()
    private val expirySkewMillis = 30_000L
    private var lastRefreshedAccessToken: String? = null

    suspend fun login(username: String, password: String): TokenPair =
        save(api.login(username, password), null)

    override suspend fun validAccessToken(forceRefresh: Boolean): String? {
        val current = tokenStore.read() ?: return null
        if (!forceRefresh && current.expiresAtMillis > nowMillis() + expirySkewMillis) {
            return current.accessToken
        }
        return refresh(force = true)?.accessToken
    }

    override suspend fun refresh(force: Boolean): TokenPair? = refreshMutex.withLock {
        val current = tokenStore.read() ?: return@withLock null
        if (force && current.accessToken == lastRefreshedAccessToken &&
            current.expiresAtMillis > nowMillis() + expirySkewMillis
        ) {
            return@withLock current
        }
        if (!force && current.expiresAtMillis > nowMillis() + expirySkewMillis) {
            return@withLock current
        }
        try {
            return@withLock save(api.refresh(current.refreshToken), current).also {
                lastRefreshedAccessToken = it.accessToken
            }
        } catch (error: ApiException) {
            if (error.statusCode == 401) tokenStore.clear()
            throw error
        }
    }

    suspend fun accept(tokens: TokenResponse): TokenPair = save(tokens, tokenStore.read())
    suspend fun healthcheck() = api.healthcheck()
    suspend fun publicSettings(): PublicSettings = api.publicSettings()
    suspend fun currentUser(): UserResponse = api.currentUser()
    suspend fun logout() = tokenStore.clear()

    private suspend fun save(response: TokenResponse, previous: TokenPair?): TokenPair {
        val refreshToken = response.refreshToken ?: previous?.refreshToken
            ?: error("Grimmory response did not contain a refresh token")
        val expiresAt = response.expiresAtMillis ?: response.expiresInSeconds
            ?.let { nowMillis() + it * 1_000L }
            ?: (nowMillis() + 3_600_000L)
        val tokens = TokenPair(response.accessToken, refreshToken, expiresAt)
        tokenStore.write(tokens)
        return tokens
    }
}

data class AuthModeDecision(val mode: AuthMode, val requiresUserChoice: Boolean = false)

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
