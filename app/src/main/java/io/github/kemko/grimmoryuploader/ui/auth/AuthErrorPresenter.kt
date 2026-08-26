package io.github.kemko.grimmoryuploader.ui.auth

import io.github.kemko.grimmoryuploader.data.auth.OidcCallbackException
import io.github.kemko.grimmoryuploader.data.auth.OidcCallbackFailure
import io.github.kemko.grimmoryuploader.data.network.ApiErrorSource
import io.github.kemko.grimmoryuploader.data.network.ApiException
import java.io.IOException

enum class AuthErrorSource(
    val label: String,
) {
    OIDC_PROVIDER("OIDC provider"),
    GRIMMORY("Grimmory"),
    GRIMMORY_OIDC_PROVIDER("Grimmory → OIDC provider"),
    LOCAL("App"),
}

data class AuthErrorPresentation(
    val source: AuthErrorSource,
    val description: String,
    val action: String,
    val technicalCode: String? = null,
)

object AuthErrorPresenter {
    fun present(error: Throwable): AuthErrorPresentation {
        val typedError = error.causes().firstOrNull { it is ApiException || it is OidcCallbackException }
        return when (typedError) {
            is ApiException -> presentApiError(typedError)
            is OidcCallbackException -> presentCallbackError(typedError)
            else -> presentLocalError(error)
        }
    }

    private fun presentApiError(error: ApiException): AuthErrorPresentation {
        val code = safeCode(error.errorCode)
        val source =
            when {
                error.source == ApiErrorSource.OIDC_PROVIDER -> AuthErrorSource.OIDC_PROVIDER
                code in setOf("invalid_client", "invalid_grant") -> AuthErrorSource.GRIMMORY_OIDC_PROVIDER
                else -> AuthErrorSource.GRIMMORY
            }
        val technicalCode = technicalCode(error.statusCode, code)
        return when (code) {
            "invalid_client" ->
                AuthErrorPresentation(
                    source = source,
                    description =
                        if (source == AuthErrorSource.GRIMMORY_OIDC_PROVIDER) {
                            "The OIDC provider rejected Grimmory's client authentication."
                        } else {
                            "The OIDC provider rejected this client."
                        },
                    action = "Check Client ID, Public Client mode, and that Client Secret is empty, then try again.",
                    technicalCode = technicalCode,
                )
            "invalid_grant" ->
                AuthErrorPresentation(
                    source = source,
                    description = "The OIDC provider rejected the authorization code during Grimmory sign-in.",
                    action = "Start sign-in again and verify the redirect URI and PKCE settings.",
                    technicalCode = technicalCode,
                )
            "access_denied" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.OIDC_PROVIDER,
                    description = "Sign-in was denied by the OIDC provider.",
                    action = "Allow access in the provider, then try again.",
                    technicalCode = technicalCode,
                )
            "invalid_token" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.GRIMMORY,
                    description = "Grimmory rejected the access token.",
                    action = "Sign in again.",
                    technicalCode = technicalCode,
                )
            "user_not_provisioned", "user_not_found", "user_not_allowed" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.GRIMMORY,
                    description = "Grimmory has no account for this OIDC user.",
                    action = "Enable OIDC auto-provisioning or create the user in Grimmory, then try again.",
                    technicalCode = technicalCode,
                )
            "invalid_redirect_uri", "redirect_uri_mismatch" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.GRIMMORY,
                    description = "Grimmory rejected the OIDC redirect URI.",
                    action = "Add the exact app callback URI to Grimmory and the provider, then try again.",
                    technicalCode = technicalCode,
                )
            "invalid_state", "state_mismatch" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.GRIMMORY,
                    description = "Grimmory rejected the OIDC state.",
                    action = "Start sign-in again and use one server configuration throughout the flow.",
                    technicalCode = technicalCode,
                )
            "oidc_disabled", "oidc_not_configured", "oidc_misconfigured" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.GRIMMORY,
                    description = "OIDC is disabled or misconfigured in Grimmory.",
                    action = "Enable OIDC Login and check the provider settings in Grimmory.",
                    technicalCode = technicalCode,
                )
            else -> {
                val unavailable = error.statusCode == null || error.statusCode >= 500
                AuthErrorPresentation(
                    source = source,
                    description =
                        if (unavailable) {
                            "${source.label} is unavailable."
                        } else {
                            "${source.label} returned an authentication error."
                        },
                    action = "Check the configuration and connection, then try again.",
                    technicalCode = technicalCode(error.statusCode, null),
                )
            }
        }
    }

    private fun presentCallbackError(error: OidcCallbackException): AuthErrorPresentation =
        when (error.failure) {
            OidcCallbackFailure.PROVIDER_ERROR -> presentProviderError(error.errorCode)
            OidcCallbackFailure.CANCELLED ->
                AuthErrorPresentation(
                    source = AuthErrorSource.LOCAL,
                    description = "OIDC sign-in was cancelled.",
                    action = "Start sign-in again when ready.",
                    technicalCode = safeCode(error.errorCode),
                )
            OidcCallbackFailure.APP_AUTH_ERROR ->
                AuthErrorPresentation(
                    source = AuthErrorSource.LOCAL,
                    description = "The Android OIDC hand-off failed.",
                    action = "Check that a browser is available, then start sign-in again.",
                    technicalCode = safeCode(error.errorCode),
                )
            OidcCallbackFailure.STATE_MISMATCH ->
                AuthErrorPresentation(
                    source = AuthErrorSource.LOCAL,
                    description = "The OIDC response state did not match the sign-in request.",
                    action = "Start sign-in again.",
                    technicalCode = "state_mismatch",
                )
        }

    private fun presentProviderError(codeValue: String?): AuthErrorPresentation {
        val code = safeCode(codeValue)
        return when (code) {
            "access_denied" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.OIDC_PROVIDER,
                    description = "Sign-in was denied by the OIDC provider.",
                    action = "Allow access in the provider, then try again.",
                    technicalCode = code,
                )
            "invalid_client" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.OIDC_PROVIDER,
                    description = "The OIDC provider rejected this client.",
                    action = "Check Client ID and Public Client mode in the provider, then try again.",
                    technicalCode = code,
                )
            "invalid_grant" ->
                AuthErrorPresentation(
                    source = AuthErrorSource.OIDC_PROVIDER,
                    description = "The OIDC provider rejected the authorization code.",
                    action = "Start sign-in again and verify the redirect URI and PKCE settings.",
                    technicalCode = code,
                )
            else ->
                AuthErrorPresentation(
                    source = AuthErrorSource.OIDC_PROVIDER,
                    description = "The OIDC provider rejected sign-in.",
                    action = "Check the provider configuration and try again.",
                )
        }
    }

    private fun presentLocalError(error: Throwable): AuthErrorPresentation {
        val message = error.message.orEmpty().lowercase()
        val oidcConfigurationError =
            message.contains("oidc") ||
                message.contains("issuer") ||
                message.contains("authorization endpoint") ||
                message.contains("provider details")
        return if (oidcConfigurationError) {
            AuthErrorPresentation(
                source = AuthErrorSource.GRIMMORY,
                description = "OIDC is disabled or misconfigured in Grimmory.",
                action = "Enable OIDC Login and check the provider settings in Grimmory.",
            )
        } else if (error is IOException) {
            AuthErrorPresentation(
                source = AuthErrorSource.GRIMMORY,
                description = "Grimmory is unavailable.",
                action = "Check the server connection and try again.",
            )
        } else {
            AuthErrorPresentation(
                source = AuthErrorSource.LOCAL,
                description = "The app could not complete sign-in.",
                action = "Try again.",
            )
        }
    }

    private fun technicalCode(
        statusCode: Int?,
        errorCode: String?,
    ): String? {
        if (statusCode == null) return errorCode
        return buildString {
            append("HTTP ")
            append(statusCode)
            errorCode?.let {
                append(" · ")
                append(it)
            }
        }
    }

    private fun safeCode(value: String?): String? =
        value
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,64}")) }
            ?.lowercase()

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
