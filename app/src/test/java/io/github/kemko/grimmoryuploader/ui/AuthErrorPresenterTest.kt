package io.github.kemko.grimmoryuploader.ui

import io.github.kemko.grimmoryuploader.data.auth.OidcCallbackException
import io.github.kemko.grimmoryuploader.data.auth.OidcCallbackFailure
import io.github.kemko.grimmoryuploader.data.network.ApiErrorSource
import io.github.kemko.grimmoryuploader.data.network.ApiException
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorPresenter
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthErrorPresenterTest {
    @Test
    fun nestedInvalidClientIdentifiesGrimmoryToProviderBoundary() {
        val error =
            AuthErrorPresenter.present(
                ApiException(
                    statusCode = 502,
                    message = "OIDC callback failed",
                    source = ApiErrorSource.GRIMMORY,
                    errorCode = "invalid_client",
                    errorDescription = "client authentication failed",
                ),
            )

        assertEquals(AuthErrorSource.GRIMMORY_OIDC_PROVIDER, error.source)
        assertEquals("The OIDC provider rejected Grimmory's client authentication.", error.description)
        assertEquals("HTTP 502 · invalid_client", error.technicalCode)
        assertFalse(error.description.contains("client authentication failed"))
    }

    @Test
    fun knownProviderAndGrimmoryErrorsHaveSafeActions() {
        val denied =
            AuthErrorPresenter.present(
                OidcCallbackException(
                    failure = OidcCallbackFailure.PROVIDER_ERROR,
                    errorCode = "access_denied",
                    errorDescription = "User denied access",
                    message = "denied",
                ),
            )
        val provisioning =
            AuthErrorPresenter.present(
                ApiException(
                    statusCode = 403,
                    message = "user not provisioned",
                    errorCode = "user_not_provisioned",
                ),
            )

        assertEquals(AuthErrorSource.OIDC_PROVIDER, denied.source)
        assertEquals("Sign-in was denied by the OIDC provider.", denied.description)
        assertEquals(AuthErrorSource.GRIMMORY, provisioning.source)
        assertEquals("Grimmory has no account for this OIDC user.", provisioning.description)
    }

    @Test
    fun mapsKnownGrimmoryConfigurationAndTokenErrors() {
        val expectations =
            mapOf(
                "invalid_grant" to "The OIDC provider rejected the authorization code during Grimmory sign-in.",
                "invalid_token" to "Grimmory rejected the access token.",
                "invalid_redirect_uri" to "Grimmory rejected the OIDC redirect URI.",
                "invalid_state" to "Grimmory rejected the OIDC state.",
                "oidc_disabled" to "OIDC is disabled or misconfigured in Grimmory.",
            )

        expectations.forEach { (code, description) ->
            val error =
                AuthErrorPresenter.present(
                    ApiException(
                        statusCode = 400,
                        message = "authentication failed",
                        errorCode = code,
                    ),
                )
            assertEquals(
                if (code == "invalid_grant") AuthErrorSource.GRIMMORY_OIDC_PROVIDER else AuthErrorSource.GRIMMORY,
                error.source,
            )
            assertEquals(description, error.description)
        }
    }

    @Test
    fun unknownErrorsExposeOnlySourceAndStatus() {
        val error =
            AuthErrorPresenter.present(
                ApiException(
                    statusCode = 503,
                    message = "<html>https://issuer.example/token secret=do-not-show</html>",
                    source = ApiErrorSource.OIDC_PROVIDER,
                ),
            )

        assertEquals(AuthErrorSource.OIDC_PROVIDER, error.source)
        assertEquals("OIDC provider is unavailable.", error.description)
        assertEquals("HTTP 503", error.technicalCode)
        assertFalse(error.description.contains("issuer.example"))
        assertFalse(error.action.contains("token"))
    }

    @Test
    fun cancellationAndAppAuthFailuresAreNotStateMismatch() {
        val cancelled =
            AuthErrorPresenter.present(
                OidcCallbackException(
                    failure = OidcCallbackFailure.CANCELLED,
                    message = "cancelled",
                ),
            )
        val appAuth =
            AuthErrorPresenter.present(
                OidcCallbackException(
                    failure = OidcCallbackFailure.APP_AUTH_ERROR,
                    message = "browser unavailable",
                ),
            )

        assertEquals("OIDC sign-in was cancelled.", cancelled.description)
        assertEquals("The Android OIDC hand-off failed.", appAuth.description)
        assertFalse(cancelled.description.contains("state", ignoreCase = true))
        assertFalse(appAuth.description.contains("state", ignoreCase = true))
    }
}
