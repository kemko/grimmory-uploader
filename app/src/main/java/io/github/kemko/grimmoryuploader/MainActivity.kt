package io.github.kemko.grimmoryuploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.kemko.grimmoryuploader.ui.AppNavHost
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorPresentation
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorPresenter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf<Intent?>(null)
    private var notificationPermissionDenied by mutableStateOf(false)
    private var oidcError by mutableStateOf<AuthErrorPresentation?>(null)
    private val notificationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> notificationPermissionDenied = !granted }
    private val oidcAuthorization =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            lifecycleScope.launch {
                runCatching {
                    val app = application as GrimmoryUploaderApp
                    app.container.oidc.handleAuthorizationResult(result.data)
                    app.container.transferScheduler.resumeAwaitingAuth()
                }.fold(
                    onSuccess = {
                        oidcError = null
                        recreate()
                    },
                    onFailure = { oidcError = AuthErrorPresenter.present(it) },
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_VIEW }
        notificationPermissionDenied = notificationPermissionWasDenied()
        val app = application as GrimmoryUploaderApp
        setContent {
            GrimmoryUploaderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        container = app.container,
                        launchIntent = launchIntent,
                        requestNotificationPermission = ::requestNotificationPermission,
                        launchOidc = oidcAuthorization::launch,
                        authError = oidcError,
                        onLaunchIntentConsumed = ::consumeLaunchIntent,
                        notificationPermissionDenied = notificationPermissionDenied,
                        awaitStartupReconciliation = app.startupReconciliation::await,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            launchIntent = intent
        } else {
            consumeLaunchIntent()
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        notificationPermissionDenied = notificationPermissionWasDenied()
        val app = application as GrimmoryUploaderApp
        lifecycleScope.launch {
            if (runCatching { app.startupReconciliation.await() }.isSuccess) {
                app.container.transferScheduler.ensureQueuedScheduled()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val preferences = getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATION_REQUESTED, false)) {
            notificationPermissionDenied = true
            return
        }
        preferences.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationPermissionWasDenied(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 &&
            getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_REQUESTED, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun consumeLaunchIntent() {
        launchIntent = null
        setIntent(Intent(Intent.ACTION_MAIN))
    }

    private companion object {
        const val PERMISSION_PREFERENCES = "runtime_permissions"
        const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
    }
}

@Composable
fun WelcomeScreen() {
    Text(text = "Grimmory Uploader")
}

@Composable
private fun GrimmoryUploaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Preview
@Composable
private fun WelcomeScreenPreview() {
    GrimmoryUploaderTheme { WelcomeScreen() }
}
