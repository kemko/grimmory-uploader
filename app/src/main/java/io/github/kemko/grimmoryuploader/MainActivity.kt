package io.github.kemko.grimmoryuploader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GrimmoryUploaderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    WelcomeScreen()
                }
            }
        }
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
