package io.github.kemko.grimmoryuploader.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorPresentation
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorSource
import io.github.kemko.grimmoryuploader.ui.auth.AuthViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun localPolicyHidesOidcAndRendersPasswordField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher =
            AesGcmTokenCipher(
                SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
            )
        val container = AppContainer(context, cipher)
        var settingsOpened = false
        compose.setContent {
            MaterialTheme {
                AuthScreen(
                    viewModel = AuthViewModel(container),
                    error =
                        AuthErrorPresentation(
                            source = AuthErrorSource.LOCAL,
                            description = "OIDC sign-in was cancelled.",
                            action = "Start sign-in again when ready.",
                        ),
                    modeDecision = AuthModeDecision(AuthMode.LOCAL),
                    launchOidc = {},
                    onSettings = { settingsOpened = true },
                    onAuthenticated = {},
                )
            }
        }

        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Source: App").assertIsDisplayed()
        compose.onNodeWithText("OIDC sign-in was cancelled.").assertIsDisplayed()
        compose.onAllNodesWithText("Sign in").assertCountEquals(2)
        compose.onAllNodesWithText("Sign in with OIDC").assertCountEquals(0)
        compose.onNodeWithText("Settings").performClick()
        assertTrue(settingsOpened)
        container.database.close()
    }

    @Test
    fun displaysOidcErrorPublishedAfterComposition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher =
            AesGcmTokenCipher(
                SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
            )
        val container = AppContainer(context, cipher)
        var error by mutableStateOf<AuthErrorPresentation?>(null)
        compose.setContent {
            MaterialTheme {
                AuthScreen(
                    viewModel = AuthViewModel(container),
                    error = error,
                    modeDecision = AuthModeDecision(AuthMode.OIDC),
                    launchOidc = {},
                    onSettings = {},
                    onAuthenticated = {},
                )
            }
        }

        compose.runOnUiThread {
            error =
                AuthErrorPresentation(
                    source = AuthErrorSource.OIDC_PROVIDER,
                    description = "OIDC callback failed.",
                    action = "Check the provider configuration and try again.",
                    technicalCode = "access_denied",
                )
        }

        compose.onNodeWithText("Source: OIDC provider").assertIsDisplayed()
        compose.onNodeWithText("OIDC callback failed.").assertIsDisplayed()
        compose.onNodeWithText("Code: access_denied").assertIsDisplayed()
        container.database.close()
    }
}
