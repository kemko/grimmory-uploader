@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.kemko.grimmoryuploader.ui

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision
import io.github.kemko.grimmoryuploader.data.auth.AuthModeSelector
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.share.IncomingInput
import io.github.kemko.grimmoryuploader.share.IncomingIntentParser
import io.github.kemko.grimmoryuploader.ui.auth.AuthViewModel
import io.github.kemko.grimmoryuploader.ui.home.HomeViewModel
import io.github.kemko.grimmoryuploader.ui.incoming.IncomingBookViewModel
import io.github.kemko.grimmoryuploader.ui.onboarding.OnboardingViewModel
import io.github.kemko.grimmoryuploader.ui.settings.SettingsViewModel
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.launch

private enum class Destination { LOADING, ONBOARDING, AUTH, HOME, INCOMING, SETTINGS, ERROR }

@Composable
fun AppNavHost(
    container: AppContainer,
    launchIntent: Intent? = null,
    requestNotificationPermission: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.LOADING) }
    var incoming by remember { mutableStateOf<IncomingInput?>(null) }
    var incomingError by remember { mutableStateOf<String?>(null) }
    var pendingJobId by remember { mutableStateOf<Long?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authDecision by remember { mutableStateOf<AuthModeDecision?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(launchIntent, refreshKey) {
        if (launchIntent != null && launchIntent.action != Intent.ACTION_MAIN) {
            runCatching { IncomingIntentParser(context.contentResolver).parse(launchIntent) }
                .onSuccess { input -> incoming = input; destination = Destination.INCOMING }
                .onFailure { error ->
                    incomingError = error.message ?: "Unsupported input"
                    container.transferNotifications.showInputFailure(incomingError!!)
                    destination = Destination.ERROR
                }
            return@LaunchedEffect
        }
        val settings = container.settings.current()
        if (settings.serverUrl == null) {
            destination = Destination.ONBOARDING
        } else {
            destination = if (AuthViewModel(container).isAuthenticated()) Destination.HOME else Destination.AUTH
        }
    }

    when (destination) {
        Destination.LOADING -> LoadingScreen()
        Destination.ONBOARDING -> OnboardingScreen(
            viewModel = remember { OnboardingViewModel(container.settings, container.auth) },
            onConfigured = { decision -> authDecision = decision; destination = if (incoming != null) Destination.INCOMING else Destination.AUTH },
        )
        Destination.AUTH -> AuthScreen(
            viewModel = remember { AuthViewModel(container) },
            error = authError,
            modeDecision = authDecision,
            onAuthenticated = {
                scope.launch {
                    requestNotificationPermission()
                    AuthViewModel(container).resumeTransfers()
                    destination = if (incoming != null) Destination.INCOMING else Destination.HOME
                    refreshKey++
                }
            },
        )
        Destination.HOME -> HomeScreen(
            viewModel = remember { HomeViewModel(container) },
            onSettings = { destination = Destination.SETTINGS },
            onChanged = { refreshKey++ },
        )
        Destination.INCOMING -> IncomingBookScreen(
            input = requireNotNull(incoming),
            viewModel = remember { IncomingBookViewModel(container) },
            resolver = context.contentResolver,
            requestNotificationPermission = requestNotificationPermission,
            onAuthRequired = { id -> pendingJobId = id; destination = Destination.AUTH },
            onDone = { destination = Destination.HOME },
            onError = { incomingError = it; destination = Destination.ERROR },
        )
        Destination.SETTINGS -> SettingsScreen(
            viewModel = remember { SettingsViewModel(container) },
            onSaved = { destination = Destination.HOME },
        )
        Destination.ERROR -> ErrorScreen(
            message = incomingError ?: "Unable to open book",
            onBack = { destination = Destination.HOME },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text("Preparing Grimmory Uploader", Modifier.padding(top = 12.dp))
    }
}

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onConfigured: (AuthModeDecision) -> Unit) {
    var url by remember { mutableStateOf("") }
    var confirmHttp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Connect Grimmory") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter your Grimmory server URL.")
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(confirmHttp, { confirmHttp = it }); Text("I understand HTTP is unencrypted", Modifier.padding(top = 12.dp)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { scope.launch { viewModel.configureServer(url, confirmHttp).fold(onConfigured, { error = it.message }) } }, modifier = Modifier.fillMaxWidth()) { Text("Check server") }
        }
    }
}

@Composable
fun AuthScreen(viewModel: AuthViewModel, error: String?, modeDecision: AuthModeDecision? = null, onAuthenticated: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(error) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Sign in") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (modeDecision?.mode != AuthMode.OIDC) Button(onClick = { scope.launch { viewModel.login(username, password).fold({ onAuthenticated() }, { message = it.message }) } }, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            if (modeDecision?.mode != AuthMode.LOCAL) OutlinedButton(onClick = { scope.launch { viewModel.startOidc().fold({ context.startActivity(it) }, { message = it.message }) } }, modifier = Modifier.fillMaxWidth()) { Text("Sign in with OIDC") }
        }
    }
}

@Composable
fun HomeScreen(viewModel: HomeViewModel, onSettings: () -> Unit, onChanged: () -> Unit) {
    var jobs by remember { mutableStateOf<List<UploadJobEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { jobs = viewModel.jobs() }
    Scaffold(topBar = { TopAppBar(title = { Text("Grimmory Uploader") }, actions = { TextButton(onSettings) { Text("Settings") } }) }) { padding ->
        if (jobs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) { Text("No transfers") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                items(jobs, key = { it.id }) { job ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(job.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(job.failureReason ?: job.state.label)
                        if (job.state == UploadJobState.FAILED) TextButton(onClick = { scope.launch { viewModel.retry(job); jobs = viewModel.jobs(); onChanged() } }) { Text("Retry") }
                        if (job.state in setOf(UploadJobState.STAGED, UploadJobState.AWAITING_AUTH, UploadJobState.QUEUED, UploadJobState.RUNNING)) TextButton(onClick = { scope.launch { viewModel.cancel(job); jobs = viewModel.jobs(); onChanged() } }) { Text("Cancel") }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingBookScreen(
    input: IncomingInput,
    viewModel: IncomingBookViewModel,
    resolver: ContentResolver,
    requestNotificationPermission: () -> Unit,
    onAuthRequired: (Long) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    var status by remember { mutableStateOf("Saving incoming book…") }
    var started by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(input) {
        if (started) return@LaunchedEffect
        started = true
        viewModel.persistAndPrepare(input, resolver, requestNotificationPermission).fold(
            { preparation -> if (preparation.requiresAuth) onAuthRequired(preparation.job.id) else { status = "Transfer scheduled"; onDone() } },
            { onError(it.message ?: "Unable to prepare upload") },
        )
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(input.displayName, Modifier.padding(top = 16.dp))
        Text(status)
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onSaved: () -> Unit) {
    var loaded by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var libraryId by remember { mutableStateOf("1") }
    var pathId by remember { mutableStateOf("1") }
    var mode by remember { mutableStateOf(AuthMode.AUTO) }
    var recompress by remember { mutableStateOf(true) }
    var httpConfirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.current().also { settings -> url = settings.serverUrl.orEmpty(); libraryId = settings.libraryId.toString(); pathId = settings.pathId.toString(); mode = settings.authMode; recompress = settings.recompressEpub; httpConfirmed = settings.httpConfirmed; loaded = true } }
    if (!loaded) return
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(libraryId, { libraryId = it }, label = { Text("Library ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(pathId, { pathId = it }, label = { Text("Path ID") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Text("Auth mode") }
            AuthMode.entries.forEach { candidate ->
                item { Row { RadioButton(mode == candidate, { mode = candidate }); Text(candidate.name, Modifier.padding(top = 12.dp)) } }
            }
            item { Row { Checkbox(recompress, { recompress = it }); Text("Recompress EPUB", Modifier.padding(top = 12.dp)) } }
            if (url.trim().lowercase().startsWith("http://")) item { Row { Checkbox(httpConfirmed, { httpConfirmed = it }); Text("Allow cleartext HTTP", Modifier.padding(top = 12.dp)) } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Button(onClick = { scope.launch { runCatching { viewModel.save(url, mode, libraryId.toInt(), pathId.toInt(), recompress, httpConfirmed) }.fold({ onSaved() }, { error = it.message }) } }, modifier = Modifier.fillMaxWidth()) { Text("Save") } }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Unable to open book", style = MaterialTheme.typography.headlineSmall)
        Text(message, Modifier.padding(vertical = 12.dp))
        Button(onBack) { Text("Back") }
    }
}

private val UploadJobState.label: String
    get() = when (this) {
        UploadJobState.STAGED -> "Ready to upload"
        UploadJobState.AWAITING_AUTH -> "Sign-in required"
        UploadJobState.QUEUED -> "Queued"
        UploadJobState.RUNNING -> "Uploading"
        UploadJobState.SUCCEEDED -> "Complete"
        UploadJobState.FAILED -> "Failed"
        UploadJobState.CANCELLED -> "Cancelled"
    }
