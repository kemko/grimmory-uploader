package io.github.kemko.grimmoryuploader

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.kemko.grimmoryuploader.ui.AppNavHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launchIntent: Intent? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_VIEW }
        val app = application as GrimmoryUploaderApp
        setContent {
            GrimmoryUploaderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        container = app.container,
                        launchIntent = launchIntent,
                        requestNotificationPermission = ::requestNotificationPermission,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_VIEW }
        recreate()
    }

    override fun onResume() {
        super.onResume()
        requestNotificationPermission()
        CoroutineScope(Dispatchers.IO).launch {
            (application as GrimmoryUploaderApp).container.transferScheduler.resumeAwaitingAuth()
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
