@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.kemko.grimmoryuploader.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kemko.grimmoryuploader.data.auth.AuthModeDecision
import io.github.kemko.grimmoryuploader.data.settings.AuthMode
import io.github.kemko.grimmoryuploader.di.AppContainer
import io.github.kemko.grimmoryuploader.share.IncomingIntentParser
import io.github.kemko.grimmoryuploader.ui.auth.AuthErrorPresentation
import io.github.kemko.grimmoryuploader.ui.auth.AuthViewModel
import io.github.kemko.grimmoryuploader.ui.home.HomeViewModel
import io.github.kemko.grimmoryuploader.ui.incoming.IncomingBookViewModel
import io.github.kemko.grimmoryuploader.ui.onboarding.OnboardingViewModel
import io.github.kemko.grimmoryuploader.ui.settings.ServerChangeConfirmationRequired
import io.github.kemko.grimmoryuploader.ui.settings.SettingsViewModel
import io.github.kemko.grimmoryuploader.upload.TransferStage
import io.github.kemko.grimmoryuploader.upload.db.UploadJobEntity
import io.github.kemko.grimmoryuploader.upload.db.UploadJobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination { LOADING, ONBOARDING, AUTH, INCOMING, HOME, SETTINGS, ERROR }

@Composable
fun AppNavHost(
    container: AppContainer,
    launchIntent: Intent? = null,
    requestNotificationPermission: () -> Unit = {},
    launchOidc: (Intent) -> Unit = {},
    authError: AuthErrorPresentation? = null,
    onLaunchIntentConsumed: () -> Unit = {},
    notificationPermissionDenied: Boolean = false,
    awaitStartupReconciliation: suspend () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(Destination.LOADING) }
    var incomingError by remember { mutableStateOf<String?>(null) }
    var pendingJobId by remember { mutableStateOf<Long?>(null) }
    var authDecision by remember { mutableStateOf<AuthModeDecision?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var hasConfiguredServer by remember { mutableStateOf(false) }
    val incomingViewModel = remember { IncomingBookViewModel(container) }
    val authViewModel = remember { AuthViewModel(container) }
    val jobs by container.upload.observeAll().collectAsState(initial = emptyList())
    val pendingJob = jobs.firstOrNull { it.id == pendingJobId }

    suspend fun preparePending(id: Long) {
        incomingViewModel.prepare(id, requestNotificationPermission).fold(
            onSuccess = { preparation ->
                if (preparation.requiresAuth) {
                    authDecision = authViewModel.modeDecision()
                    destination = Destination.AUTH
                } else {
                    destination = Destination.INCOMING
                }
            },
            onFailure = { error ->
                incomingError = error.message ?: "Unable to prepare upload"
                destination = Destination.ERROR
            },
        )
    }

    LaunchedEffect(launchIntent, refreshKey) {
        runCatching { awaitStartupReconciliation() }.onFailure { error ->
            incomingError = error.message ?: "Unable to restore pending transfers"
            destination = Destination.ERROR
            return@LaunchedEffect
        }
        if (launchIntent != null && launchIntent.action != Intent.ACTION_MAIN) {
            val persisted =
                withContext(Dispatchers.IO) {
                    runCatching { IncomingIntentParser(context.contentResolver).parse(launchIntent) }
                        .mapCatching { input -> incomingViewModel.persist(input, context.contentResolver).getOrThrow() }
                }
            onLaunchIntentConsumed()
            persisted.fold(
                onSuccess = { pendingJobId = it.id },
                onFailure = { error ->
                    incomingError = error.message ?: "Unsupported input"
                    container.transferNotifications.showInputFailure(incomingError!!)
                    destination = Destination.ERROR
                    return@LaunchedEffect
                },
            )
        } else if (pendingJobId == null) {
            pendingJobId = container.upload.pendingIntake()?.id
        }
        val settings = container.settings.current()
        hasConfiguredServer = settings.serverUrl != null
        if (settings.serverUrl == null) {
            destination = Destination.ONBOARDING
        } else if (pendingJobId != null) {
            when (container.upload.find(requireNotNull(pendingJobId))?.state) {
                UploadJobState.STAGED -> preparePending(requireNotNull(pendingJobId))
                UploadJobState.AWAITING_AUTH -> {
                    runCatching { authViewModel.isAuthenticated() }.fold(
                        onSuccess = { authenticated ->
                            if (authenticated) {
                                requestNotificationPermission()
                                authViewModel.resumeTransfers()
                                destination = Destination.INCOMING
                            } else {
                                authDecision = authViewModel.modeDecision()
                                destination = Destination.AUTH
                            }
                        },
                        onFailure = { error ->
                            incomingError = error.message ?: "Unable to verify authentication"
                            destination = Destination.ERROR
                        },
                    )
                }
                null -> {
                    incomingError = "Incoming upload is missing"
                    destination = Destination.ERROR
                }
                else -> destination = Destination.INCOMING
            }
        } else {
            runCatching { authViewModel.isAuthenticated() }.fold(
                onSuccess = { authenticated ->
                    if (authenticated) {
                        destination = Destination.HOME
                    } else {
                        authDecision = authViewModel.modeDecision()
                        destination = Destination.AUTH
                    }
                },
                onFailure = { error ->
                    incomingError = error.message ?: "Unable to verify authentication"
                    destination = Destination.ERROR
                },
            )
        }
    }

    when (destination) {
        Destination.LOADING -> LoadingScreen()
        Destination.ONBOARDING ->
            OnboardingScreen(
                viewModel = remember { OnboardingViewModel(container.settings, container.onboardingProbe) },
                onConfigured = { decision ->
                    authDecision = decision
                    pendingJobId?.let { id -> scope.launch { preparePending(id) } }
                        ?: run { destination = Destination.AUTH }
                },
            )
        Destination.AUTH ->
            AuthScreen(
                viewModel = authViewModel,
                error = authError,
                modeDecision = authDecision,
                launchOidc = launchOidc,
                onSettings = { destination = Destination.SETTINGS },
                onAuthenticated = {
                    scope.launch {
                        requestNotificationPermission()
                        AuthViewModel(container).resumeTransfers()
                        destination = if (pendingJobId == null) Destination.HOME else Destination.INCOMING
                    }
                },
            )
        Destination.INCOMING ->
            pendingJob?.let { job ->
                IncomingBookScreen(
                    job = job,
                    viewModel = remember { HomeViewModel(container) },
                    requestNotificationPermission = requestNotificationPermission,
                    notificationPermissionDenied = notificationPermissionDenied,
                    onDone = {
                        pendingJobId = null
                        destination = Destination.HOME
                    },
                )
            } ?: LoadingScreen()
        Destination.HOME ->
            HomeScreen(
                viewModel = remember { HomeViewModel(container) },
                onSettings = { destination = Destination.SETTINGS },
                requestNotificationPermission = requestNotificationPermission,
                notificationPermissionDenied = notificationPermissionDenied,
            )
        Destination.SETTINGS ->
            SettingsScreen(
                viewModel = remember { SettingsViewModel(container) },
                onSaved = { serverChanged ->
                    if (serverChanged) {
                        pendingJobId = null
                        scope.launch {
                            authDecision = authViewModel.modeDecision()
                            destination = Destination.AUTH
                        }
                    } else {
                        destination = Destination.HOME
                    }
                },
            )
        Destination.ERROR ->
            ErrorScreen(
                message = incomingError ?: "Unable to open book",
                onBack = { refreshKey++ },
                onSettings = {
                    destination = if (hasConfiguredServer) Destination.SETTINGS else Destination.ONBOARDING
                },
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
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onConfigured: (AuthModeDecision) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var confirmHttp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Connect Grimmory") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter your Grimmory server URL.")
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(confirmHttp, { confirmHttp = it })
                Text("I understand HTTP is unencrypted", Modifier.padding(top = 12.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                scope.launch {
                    viewModel.configureServer(url, confirmHttp).fold(onConfigured, {
                        error = it.message
                    })
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Check server") }
        }
    }
}

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    error: AuthErrorPresentation?,
    modeDecision: AuthModeDecision? = null,
    launchOidc: (Intent) -> Unit,
    onSettings: () -> Unit,
    onAuthenticated: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(error) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(error) { message = error }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in") },
                actions = { TextButton(onSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                username,
                { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password,
                { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { authError ->
                Text("Source: ${authError.source.label}", color = MaterialTheme.colorScheme.error)
                Text(authError.description, color = MaterialTheme.colorScheme.error)
                Text(authError.action, color = MaterialTheme.colorScheme.error)
                authError.technicalCode?.let { code -> Text("Code: $code", color = MaterialTheme.colorScheme.error) }
            }
            if (modeDecision?.mode != AuthMode.OIDC) {
                Button(onClick = {
                    scope.launch {
                        if (modeDecision?.requiresUserChoice == true) viewModel.selectMode(AuthMode.LOCAL)
                        viewModel.login(username, password).fold({ onAuthenticated() }, { message = viewModel.presentAuthError(it) })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            }
            if (modeDecision?.mode != AuthMode.LOCAL) {
                OutlinedButton(onClick = {
                    scope.launch {
                        if (modeDecision?.requiresUserChoice == true) viewModel.selectMode(AuthMode.OIDC)
                        viewModel.startOidc().fold(launchOidc, { message = viewModel.presentAuthError(it) })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Sign in with OIDC") }
            }
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSettings: () -> Unit,
    requestNotificationPermission: () -> Unit,
    notificationPermissionDenied: Boolean,
) {
    val jobs by viewModel.jobs().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Grimmory Uploader") }, actions = { TextButton(onSettings) { Text("Settings") } })
    }) { padding ->
        if (jobs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No transfers")
                if (notificationPermissionDenied) Text("Notifications are disabled; background progress may be unavailable.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (notificationPermissionDenied) {
                    item { Text("Notifications are disabled; background progress may be unavailable.") }
                }
                items(jobs, key = { it.id }) { job ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(job.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(job.failureReason ?: job.state.label)
                        job.progressStage?.let { stage ->
                            Text(runCatching { TransferStage.valueOf(stage).label }.getOrDefault(stage))
                            if (job.progressTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { (job.progressCurrent.toFloat() / job.progressTotal).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (job.state == UploadJobState.RUNNING) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (job.state == UploadJobState.FAILED && job.sourceUrl != null) {
                            TextButton(onClick = {
                                requestNotificationPermission()
                                scope.launch { viewModel.retry(job) }
                            }) { Text("Retry") }
                        }
                        if (job.state == UploadJobState.AWAITING_AUTH) {
                            TextButton(onClick = {
                                requestNotificationPermission()
                                scope.launch { viewModel.resumeAwaitingAuth() }
                            }) { Text("Retry") }
                        }
                        if (job.state == UploadJobState.AWAITING_CLEARTEXT) {
                            TextButton(onClick = {
                                requestNotificationPermission()
                                scope.launch { viewModel.confirmCleartext(job) }
                            }) { Text("Allow HTTP") }
                        }
                        if (
                            job.state in
                            setOf(
                                UploadJobState.STAGED,
                                UploadJobState.AWAITING_AUTH,
                                UploadJobState.AWAITING_CLEARTEXT,
                                UploadJobState.QUEUED,
                                UploadJobState.RUNNING,
                            )
                        ) {
                            TextButton(onClick = { scope.launch { viewModel.cancel(job) } }) { Text("Cancel") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingBookScreen(
    job: UploadJobEntity,
    viewModel: HomeViewModel,
    requestNotificationPermission: () -> Unit,
    notificationPermissionDenied: Boolean,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Book transfer") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(job.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(job.failureReason ?: job.state.label)
            if (notificationPermissionDenied) {
                Text("Notifications are disabled; background progress may be unavailable.")
            }
            job.progressStage?.let { stage ->
                Text(runCatching { TransferStage.valueOf(stage).label }.getOrDefault(stage))
                if (job.progressTotal > 0) {
                    LinearProgressIndicator(
                        progress = { (job.progressCurrent.toFloat() / job.progressTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (job.state == UploadJobState.RUNNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (job.state == UploadJobState.FAILED && job.sourceUrl != null) {
                Button(onClick = {
                    requestNotificationPermission()
                    scope.launch { viewModel.retry(job) }
                }) { Text("Retry") }
            }
            if (job.state == UploadJobState.AWAITING_AUTH) {
                Button(onClick = {
                    requestNotificationPermission()
                    scope.launch { viewModel.resumeAwaitingAuth() }
                }) { Text("Retry") }
            }
            if (job.state == UploadJobState.AWAITING_CLEARTEXT) {
                Button(onClick = {
                    requestNotificationPermission()
                    scope.launch { viewModel.confirmCleartext(job) }
                }) { Text("Allow HTTP") }
            }
            if (
                job.state in
                setOf(
                    UploadJobState.STAGED,
                    UploadJobState.AWAITING_AUTH,
                    UploadJobState.AWAITING_CLEARTEXT,
                    UploadJobState.QUEUED,
                    UploadJobState.RUNNING,
                )
            ) {
                OutlinedButton(onClick = { scope.launch { viewModel.cancel(job) } }) { Text("Cancel") }
            } else {
                Button(onDone) { Text("Done") }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onSaved: (Boolean) -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var libraryId by remember { mutableStateOf("1") }
    var pathId by remember { mutableStateOf("1") }
    var mode by remember { mutableStateOf(AuthMode.AUTO) }
    var recompress by remember { mutableStateOf(true) }
    var httpConfirmed by remember { mutableStateOf(false) }
    var originalUrl by remember { mutableStateOf("") }
    var originalHttpConfirmed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmServerChange by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.current().also { settings ->
            url = settings.serverUrl.orEmpty()
            originalUrl = url
            libraryId =
                settings.libraryId.toString()
            pathId = settings.pathId.toString()
            mode = settings.authMode
            recompress = settings.recompressEpub
            httpConfirmed =
                settings.httpConfirmed
            originalHttpConfirmed = settings.httpConfirmed
            loaded = true
        }
    }
    if (!loaded) return
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(
                    url,
                    {
                        url = it
                        httpConfirmed = retainedHttpConfirmation(originalUrl, originalHttpConfirmed, it)
                    },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    libraryId,
                    { libraryId = it },
                    label = { Text("Library ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    pathId,
                    { pathId = it },
                    label = { Text("Path ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Text("Auth mode") }
            AuthMode.entries.forEach { candidate ->
                item {
                    Row {
                        RadioButton(mode == candidate, { mode = candidate })
                        Text(candidate.name, Modifier.padding(top = 12.dp))
                    }
                }
            }
            item {
                Row {
                    Checkbox(recompress, { recompress = it })
                    Text("Recompress EPUB", Modifier.padding(top = 12.dp))
                }
            }
            if (url.trim().lowercase().startsWith("http://")) {
                item {
                    Row {
                        Checkbox(httpConfirmed, { httpConfirmed = it }, Modifier.testTag("http-confirmation"))
                        Text("Allow cleartext HTTP", Modifier.padding(top = 12.dp))
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    scope.launch {
                        val library = libraryId.toIntOrNull()
                        val path = pathId.toIntOrNull()
                        if (library == null || path == null) {
                            error = "Library ID and path ID must be integers"
                            return@launch
                        }
                        runCatching { viewModel.save(url, mode, library, path, recompress, httpConfirmed) }
                            .fold(
                                onSaved,
                                {
                                    if (it === ServerChangeConfirmationRequired) {
                                        confirmServerChange = true
                                    } else {
                                        error = it.message
                                    }
                                },
                            )
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }
        }
    }
    if (confirmServerChange) {
        AlertDialog(
            onDismissRequest = { confirmServerChange = false },
            title = { Text("Change Grimmory server?") },
            text = { Text("Transfers for the old server will be removed with their files and sign-in tokens.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmServerChange = false
                    scope.launch {
                        val library = libraryId.toIntOrNull() ?: return@launch
                        val path = pathId.toIntOrNull() ?: return@launch
                        runCatching {
                            viewModel.save(url, mode, library, path, recompress, httpConfirmed, confirmServerChange = true)
                        }.fold(onSaved, { error = it.message })
                    }
                }) { Text("Change server") }
            },
            dismissButton = { TextButton(onClick = { confirmServerChange = false }) { Text("Cancel") } },
        )
    }
}

internal fun retainedHttpConfirmation(
    originalUrl: String,
    originalConfirmed: Boolean,
    editedUrl: String,
): Boolean =
    originalConfirmed &&
        runCatching {
            io.github.kemko.grimmoryuploader.data.network.ServerUrl
                .parse(originalUrl)
                .normalized ==
                io.github.kemko.grimmoryuploader.data.network.ServerUrl
                    .parse(editedUrl)
                    .normalized
        }.getOrDefault(false)

@Composable
private fun ErrorScreen(
    message: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Text(message, Modifier.padding(vertical = 12.dp))
        Button(onBack) { Text("Back") }
        OutlinedButton(onSettings) { Text("Settings") }
    }
}

private val UploadJobState.label: String
    get() =
        when (this) {
            UploadJobState.STAGED -> "Ready to upload"
            UploadJobState.AWAITING_AUTH -> "Sign-in required"
            UploadJobState.AWAITING_CLEARTEXT -> "HTTP confirmation required"
            UploadJobState.QUEUED -> "Queued"
            UploadJobState.RUNNING -> "Uploading"
            UploadJobState.SUCCEEDED -> "Complete"
            UploadJobState.FAILED -> "Failed"
            UploadJobState.CANCELLED -> "Cancelled"
        }

private val TransferStage.label: String
    get() =
        when (this) {
            TransferStage.DOWNLOAD -> "Downloading"
            TransferStage.VALIDATION -> "Validating"
            TransferStage.RECOMPRESSION -> "Recompressing"
            TransferStage.UPLOAD -> "Uploading"
        }
