package io.github.kemko.grimmoryuploader.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.OidcCallbackRequest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class OidcPendingRequest(
    val state: String,
    val codeVerifier: String,
    val nonce: String,
    val redirectUri: String,
)

data class OidcAuthorizationData(
    val authorizationEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val nonce: String,
)

object Pkce {
    private val random = SecureRandom()

    fun verifier(): String = randomBytes(32).let(::base64Url)

    fun challenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(verifier.encodeToByteArray()).let(::base64Url)

    fun nonce(): String = randomBytes(24).let(::base64Url)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

class OidcCoordinator(
    context: Context,
    private val api: GrimmoryApi,
    private val auth: AuthRepository,
    private val redirectUri: String = "io.github.kemko.grimmoryuploader:/oauth2redirect",
    private val authorizationIntentFactory: ((OidcAuthorizationData) -> Intent)? = null,
) {
    private val authorizationService = lazy { AuthorizationService(context.applicationContext) }
    private var pending: OidcPendingRequest? = null

    suspend fun start(): Intent {
        val stateResponse = api.oidcState()
        val state = stateResponse.state ?: error("Grimmory did not return OIDC state")
        val discovery = stateResponse.issuer?.let { api.oidcDiscovery(it) }
        val authorizationEndpoint = stateResponse.authorizationEndpoint
            ?: discovery?.authorizationEndpoint
            ?: error("OIDC authorization endpoint is missing")
        val clientId = stateResponse.clientId ?: error("OIDC client id is missing")
        val redirect = stateResponse.redirectUri ?: redirectUri.toString()
        val verifier = Pkce.verifier()
        val nonce = Pkce.nonce()
        val challenge = Pkce.challenge(verifier)
        pending = OidcPendingRequest(state, verifier, nonce, redirect)
        authorizationIntentFactory?.let {
            return it(
                OidcAuthorizationData(
                    authorizationEndpoint,
                    clientId,
                    redirect,
                    state,
                    verifier,
                    challenge,
                    nonce,
                ),
            )
        }
        val request = AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(
                Uri.parse(authorizationEndpoint),
                Uri.parse(discovery?.tokenEndpoint ?: authorizationEndpoint),
            ),
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirect),
        )
            .setState(state)
            .setCodeVerifier(verifier, challenge, "S256")
            .setNonce(nonce)
            .build()
        return authorizationService.value.getAuthorizationRequestIntent(request)
    }

    suspend fun handleCallback(callback: Uri) {
        handleCallback(
            state = callback.getQueryParameter("state"),
            error = callback.getQueryParameter("error"),
            code = callback.getQueryParameter("code"),
        )
    }

    suspend fun handleCallback(state: String?, error: String?, code: String?) {
        val request = pending ?: kotlin.error("No pending OIDC request")
        check(state == request.state) { "OIDC state mismatch" }
        check(error == null) { "OIDC authorization failed: $error" }
        val authorizationCode = code ?: error("OIDC code is missing")
        try {
            auth.accept(
                api.oidcCallback(
                    OidcCallbackRequest(
                        code = authorizationCode,
                        state = request.state,
                        redirectUri = request.redirectUri,
                        codeVerifier = request.codeVerifier,
                        nonce = request.nonce,
                    ),
                ),
            )
        } finally {
            pending = null
        }
    }

    fun close() {
        if (authorizationService.isInitialized()) authorizationService.value.dispose()
    }
}
