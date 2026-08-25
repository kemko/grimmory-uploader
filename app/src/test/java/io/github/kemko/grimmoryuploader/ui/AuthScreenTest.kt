package io.github.kemko.grimmoryuploader.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.ui.auth.AuthViewModel
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun localPolicyHidesOidcAndRendersPasswordField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cipher = AesGcmTokenCipher(
            SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
        )
        val container = AppContainer(context, cipher)
        compose.setContent {
            MaterialTheme {
                AuthScreen(
                    viewModel = AuthViewModel(container),
                    error = "OIDC sign-in was cancelled",
                    modeDecision = AuthModeDecision(AuthMode.LOCAL),
                    launchOidc = {},
                    onAuthenticated = {},
                )
            }
        }

        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("OIDC sign-in was cancelled").assertIsDisplayed()
        compose.onAllNodesWithText("Sign in").assertCountEquals(2)
        compose.onAllNodesWithText("Sign in with OIDC").assertCountEquals(0)
        container.database.close()
    }
}
