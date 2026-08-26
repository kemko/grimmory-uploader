package io.github.kemko.grimmoryuploader.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.OidcCallbackRequest
import kotlinx.serialization.Serializable
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class OidcPendingRequest(
    val state: String,
    val codeVerifier: String,
    val nonce: String,
    val redirectUri: String,
    val serverUrl: String,
)

data class OidcAuthorizationData(
    val authorizationEndpoint: String,
    val clientId: String,
    val scope: String,
    val redirectUri: String,
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val nonce: String,
)

enum class OidcCallbackFailure {
    PROVIDER_ERROR,
    CANCELLED,
    APP_AUTH_ERROR,
    STATE_MISMATCH,
}

class OidcCallbackException(
    val failure: OidcCallbackFailure,
    val errorCode: String? = null,
    val errorDescription: String? = null,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

object Pkce {
    private val random = SecureRandom()

    fun verifier(): String = randomBytes(32).let(::base64Url)

    fun challenge(verifier: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.encodeToByteArray())
            .let(::base64Url)

    fun nonce(): String = randomBytes(24).let(::base64Url)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
        val serverUrl = auth.serverUrl()
        val provider =
            api.publicSettings().oidcProviderDetails
                ?: error("Grimmory did not return OIDC provider details")
        val issuer = provider.issuerUri ?: error("OIDC issuer is missing")
        val clientId = provider.clientId ?: error("OIDC client id is missing")
        val scope = provider.scopes?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_SCOPES
        require(scope.split(Regex("\\s+")).contains("openid")) { "OIDC scopes must include openid" }
        val discovery = api.oidcDiscovery(issuer)
        val authorizationEndpoint =
            discovery.authorizationEndpoint
                ?: error("OIDC authorization endpoint is missing")
        val tokenEndpoint = discovery.tokenEndpoint ?: error("OIDC token endpoint is missing")
        requireSecureOidcUrl(authorizationEndpoint)
        requireSecureOidcUrl(tokenEndpoint)
        val state = api.oidcState().state ?: error("Grimmory did not return OIDC state")
        val verifier = Pkce.verifier()
        val nonce = Pkce.nonce()
        val challenge = Pkce.challenge(verifier)
        check(auth.serverUrl() == serverUrl) { "Grimmory server changed during OIDC sign-in" }
        pendingStore.writePendingOidc(OidcPendingRequest(state, verifier, nonce, redirectUri, serverUrl))
        authorizationIntentFactory?.let {
            return it(
                OidcAuthorizationData(
                    authorizationEndpoint,
                    clientId,
                    scope,
                    redirectUri,
                    state,
                    verifier,
                    challenge,
                    nonce,
                ),
            )
        }
        val request =
            AuthorizationRequest
                .Builder(
                    AuthorizationServiceConfiguration(
                        Uri.parse(authorizationEndpoint),
                        Uri.parse(tokenEndpoint),
                    ),
                    clientId,
                    ResponseTypeValues.CODE,
                    Uri.parse(redirectUri),
                ).setScope(scope)
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
            errorDescription = callback.getQueryParameter("error_description"),
        )
    }

    suspend fun handleAuthorizationResult(intent: Intent?) {
        if (intent == null) {
            pendingStore.clearPendingOidc()
            throw OidcCallbackException(
                failure = OidcCallbackFailure.CANCELLED,
                message = "OIDC sign-in was cancelled",
            )
        }
        val response: AuthorizationResponse?
        val exception: AuthorizationException?
        try {
            response = AuthorizationResponse.fromIntent(intent)
            exception = AuthorizationException.fromIntent(intent)
        } catch (error: Exception) {
            pendingStore.clearPendingOidc()
            throw appAuthFailure(error)
        }

        val callback = intent.data
        val callbackState = callback?.getQueryParameter("state")
        when {
            exception?.type == AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR ->
                handleCallback(
                    state = callbackState,
                    error = exception.error ?: callback?.getQueryParameter("error"),
                    errorDescription = exception.errorDescription ?: callback?.getQueryParameter("error_description"),
                    code = null,
                )
            exception.matches(AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW) ->
                failCallback(
                    failure = OidcCallbackFailure.CANCELLED,
                    message = "OIDC sign-in was cancelled",
                    exception = requireNotNull(exception),
                )
            exception.matches(AuthorizationException.AuthorizationRequestErrors.STATE_MISMATCH) ->
                failCallback(
                    failure = OidcCallbackFailure.STATE_MISMATCH,
                    message = "OIDC state mismatch",
                    exception = requireNotNull(exception),
                )
            exception != null -> failAppAuth(exception)
            callback?.getQueryParameter("error") != null ->
                handleCallback(
                    state = callbackState,
                    error = callback.getQueryParameter("error"),
                    errorDescription = callback.getQueryParameter("error_description"),
                    code = null,
                )
            response != null ->
                handleCallback(
                    state = response.state ?: callbackState,
                    error = null,
                    code = response.authorizationCode,
                )
            else -> failAppAuth(null)
        }
    }

    suspend fun handleCallback(
        state: String?,
        error: String?,
        code: String?,
        errorDescription: String? = null,
    ) {
        val request = pendingStore.readPendingOidc() ?: throw appAuthFailure(null, "No pending OIDC request")
        try {
            if (state != request.state) {
                throw OidcCallbackException(
                    failure = OidcCallbackFailure.STATE_MISMATCH,
                    message = "OIDC state mismatch",
                )
            }
            if (error != null) {
                throw OidcCallbackException(
                    failure = OidcCallbackFailure.PROVIDER_ERROR,
                    errorCode = error.safeCallbackValue(MAX_ERROR_CODE_CHARS),
                    errorDescription = errorDescription.safeCallbackValue(MAX_ERROR_DESCRIPTION_CHARS),
                    message =
                        errorDescription.safeCallbackValue(MAX_ERROR_DESCRIPTION_CHARS)
                            ?: error.safeCallbackValue(MAX_ERROR_CODE_CHARS)
                            ?: "OIDC authorization failed",
                )
            }
            val authorizationCode = code ?: throw appAuthFailure(null, "OIDC authorization code is missing")
            auth.exchangeOidc(
                request.serverUrl,
                OidcCallbackRequest(
                    code = authorizationCode,
                    state = request.state,
                    redirectUri = request.redirectUri,
                    codeVerifier = request.codeVerifier,
                    nonce = request.nonce,
                ),
            )
        } finally {
            pendingStore.clearPendingOidc()
        }
    }

    private suspend fun failAppAuth(exception: AuthorizationException?): Nothing {
        pendingStore.clearPendingOidc()
        throw appAuthFailure(exception)
    }

    private suspend fun failCallback(
        failure: OidcCallbackFailure,
        message: String,
        exception: AuthorizationException,
    ): Nothing {
        pendingStore.clearPendingOidc()
        throw OidcCallbackException(failure = failure, message = message, cause = exception)
    }

    private fun appAuthFailure(
        cause: Throwable?,
        fallbackMessage: String = "OIDC authorization failed in AppAuth",
    ): OidcCallbackException {
        val exception = cause as? AuthorizationException
        val description = exception?.errorDescription.safeCallbackValue(MAX_ERROR_DESCRIPTION_CHARS)
        val code = exception?.error.safeCallbackValue(MAX_ERROR_CODE_CHARS)
        return OidcCallbackException(
            failure = OidcCallbackFailure.APP_AUTH_ERROR,
            errorCode = code,
            errorDescription = description,
            message = description ?: code ?: fallbackMessage,
            cause = cause,
        )
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

    private fun String?.safeCallbackValue(maxChars: Int): String? =
        this
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(maxChars)

    private fun AuthorizationException?.matches(template: AuthorizationException): Boolean =
        this?.type == template.type && this.code == template.code

    private companion object {
        const val DEFAULT_SCOPES = "openid profile email groups offline_access"
        const val MAX_ERROR_CODE_CHARS = 128
        const val MAX_ERROR_DESCRIPTION_CHARS = 512
    }
}
