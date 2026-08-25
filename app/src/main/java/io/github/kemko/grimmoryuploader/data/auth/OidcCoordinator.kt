package io.github.kemko.grimmoryuploader.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.OidcCallbackRequest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
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
    private val pendingStore: OidcPendingStore,
    private val redirectUri: String = "io.github.kemko.grimmoryuploader:/oauth2redirect",
    private val authorizationIntentFactory: ((OidcAuthorizationData) -> Intent)? = null,
) {
    private val authorizationService = lazy { AuthorizationService(context.applicationContext) }

    suspend fun start(): Intent {
        val stateResponse = api.oidcState()
        val state = stateResponse.state ?: error("Grimmory did not return OIDC state")
        val discovery = stateResponse.issuer?.let { api.oidcDiscovery(it) }
        val authorizationEndpoint = stateResponse.authorizationEndpoint
            ?: discovery?.authorizationEndpoint
            ?: error("OIDC authorization endpoint is missing")
        requireSecureOidcUrl(authorizationEndpoint)
        val clientId = stateResponse.clientId ?: error("OIDC client id is missing")
        val redirect = stateResponse.redirectUri ?: redirectUri
        check(redirect == redirectUri) { "Grimmory returned an unsupported OIDC redirect URI" }
        val verifier = Pkce.verifier()
        val nonce = Pkce.nonce()
        val challenge = Pkce.challenge(verifier)
        pendingStore.writePendingOidc(OidcPendingRequest(state, verifier, nonce, redirect))
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

    suspend fun handleAuthorizationResult(intent: Intent?) {
        if (intent == null) {
            pendingStore.clearPendingOidc()
            error("OIDC sign-in was cancelled")
        }
        val data = intent
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)
        handleCallback(
            state = response?.state,
            error = exception?.errorDescription ?: exception?.error,
            code = response?.authorizationCode,
        )
    }

    suspend fun handleCallback(state: String?, error: String?, code: String?) {
        val request = pendingStore.readPendingOidc() ?: kotlin.error("No pending OIDC request")
        check(state == request.state) { "OIDC state mismatch" }
        try {
            check(error == null) { "OIDC authorization failed: $error" }
            val authorizationCode = code ?: error("OIDC code is missing")
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
            pendingStore.clearPendingOidc()
        }
    }

    fun close() {
        if (authorizationService.isInitialized()) authorizationService.value.dispose()
    }

    private fun requireSecureOidcUrl(value: String) {
        val url = value.toHttpUrlOrNull() ?: error("Invalid OIDC endpoint")
        check(url.scheme == "https" || url.host in setOf("127.0.0.1", "localhost")) {
            "OIDC endpoints must use HTTPS"
        }
    }
}
