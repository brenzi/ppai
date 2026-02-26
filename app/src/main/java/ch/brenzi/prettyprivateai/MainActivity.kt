package ch.brenzi.prettyprivateai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.brenzi.prettyprivateai.proxy.ProxyManager
import ch.brenzi.prettyprivateai.ui.navigation.MainNavigation
import ch.brenzi.prettyprivateai.ui.setup.SetupScreen
import ch.brenzi.prettyprivateai.ui.theme.BackgroundLight
import ch.brenzi.prettyprivateai.ui.theme.PrivatemodeTheme
import ch.brenzi.prettyprivateai.ui.theme.Purple
import ch.brenzi.prettyprivateai.ui.theme.TextSecondary
import ch.brenzi.prettyprivateai.ui.theme.TextTertiary
import ch.brenzi.prettyprivateai.whisper.WhisperModelSize
import ch.brenzi.prettyprivateai.whisper.WhisperModelState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as PrivatemodeApp

        setContent {
            PrivatemodeTheme {
                AppContent(app)
            }
        }
    }
}

@Composable
private fun AppContent(app: PrivatemodeApp) {
    val scope = rememberCoroutineScope()

    val apiKey by app.preferences.apiKey.collectAsState(initial = null)
    var initialized by remember { mutableStateOf(false) }
    var apiKeyChecked by remember { mutableStateOf(false) }

    val proxyState by app.proxyManager.state.collectAsState()
    val sttEnabled by app.preferences.sttEnabled.collectAsState(initial = false)
    val sttPromptShown by app.preferences.sttPromptShown.collectAsState(initial = true)
    var showSttDialog by remember { mutableStateOf(false) }

    // Initialize storage and proxy on first composition
    LaunchedEffect(Unit) {
        app.repository.initialize()
        app.proxyManager.initialize()
        apiKeyChecked = true
    }

    // When connection is ready and we have an API key, load models
    LaunchedEffect(proxyState, apiKey) {
        if (app.proxyManager.isReady() && apiKey != null) {
            app.repository.loadModels()
            initialized = true
        }
    }

    // Show STT prompt after app is ready
    LaunchedEffect(initialized, sttPromptShown) {
        if (initialized && !sttPromptShown) {
            showSttDialog = true
        }
    }

    // Initialize whisper if STT enabled — no dependency on proxy/API
    LaunchedEffect(sttEnabled) {
        if (sttEnabled) {
            val sizeName = app.preferences.sttModelSize.first()
            app.whisperManager.setModelSize(WhisperModelSize.fromString(sizeName))
            app.whisperManager.initialize()
            if (app.whisperManager.modelState.value is WhisperModelState.NotDownloaded) {
                app.whisperManager.downloadModel()
            }
        }
    }

    if (showSttDialog) {
        SttEnableDialog(
            onEnable = {
                showSttDialog = false
                scope.launch {
                    app.preferences.setSttEnabled(true)
                    app.preferences.setSttPromptShown(true)
                }
            },
            onDismiss = {
                showSttDialog = false
                scope.launch {
                    app.preferences.setSttPromptShown(true)
                }
            },
        )
    }

    when {
        // Still checking API key
        !apiKeyChecked -> {
            LoadingScreen("Starting Pretty Private AI...")
        }
        // No API key -> show setup
        apiKey == null || apiKey?.isEmpty() == true -> {
            SetupScreen(
                onApiKeySubmitted = { key ->
                    scope.launch {
                        app.repository.setApiKey(key)
                    }
                },
            )
        }
        // Connection loading
        proxyState is ProxyManager.ProxyState.Loading || proxyState is ProxyManager.ProxyState.NotStarted -> {
            LoadingScreen("Connecting to secure backend...")
        }
        // Connection error
        proxyState is ProxyManager.ProxyState.Error -> {
            ErrorScreen(
                message = (proxyState as ProxyManager.ProxyState.Error).message,
                onRetry = {
                    scope.launch {
                        app.proxyManager.initialize()
                    }
                },
            )
        }
        // Ready (either native proxy running or direct HTTPS mode)
        proxyState is ProxyManager.ProxyState.Running ||
            proxyState is ProxyManager.ProxyState.DirectMode -> {
            MainNavigation(
                repository = app.repository,
                proxyManager = app.proxyManager,
                whisperManager = app.whisperManager,
            )
        }
    }
}

@Composable
private fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundLight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Purple,
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = Purple)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundLight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Purple.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Connection Error",
                style = MaterialTheme.typography.headlineMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            androidx.compose.material3.Button(
                onClick = onRetry,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Purple),
            ) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun SttEnableDialog(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech to text") },
        text = {
            Text(
                "Enable on-device speech-to-text? This downloads a model (~31–105 MB). " +
                    "Transcription runs entirely on your device. " +
                    "You can change the model size in Settings.",
            )
        },
        confirmButton = {
            TextButton(onClick = onEnable) { Text("Enable") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
