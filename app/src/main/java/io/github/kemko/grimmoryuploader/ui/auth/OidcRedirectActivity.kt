package io.github.kemko.grimmoryuploader.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.kemko.grimmoryuploader.GrimmoryUploaderApp
import io.github.kemko.grimmoryuploader.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OidcRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GrimmoryUploaderApp
        CoroutineScope(Dispatchers.Main).launch {
            runCatching { app.container.oidc.handleCallback(intent.data ?: error("Missing OIDC callback")) }
                .onSuccess { app.container.transferScheduler.resumeAwaitingAuth() }
            startActivity(Intent(this@OidcRedirectActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}
