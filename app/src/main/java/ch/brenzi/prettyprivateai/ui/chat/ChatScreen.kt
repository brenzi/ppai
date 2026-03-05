package ch.brenzi.prettyprivateai.ui.chat

import android.content.Intent
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ch.brenzi.prettyprivateai.data.model.MODEL_CONFIG
import ch.brenzi.prettyprivateai.data.model.Message
import ch.brenzi.prettyprivateai.data.model.MessageRole
import ch.brenzi.prettyprivateai.data.model.countWords
import ch.brenzi.prettyprivateai.tts.TtsModelState
import ch.brenzi.prettyprivateai.ui.theme.*
import ch.brenzi.prettyprivateai.util.MarkdownRenderer
import ch.brenzi.prettyprivateai.whisper.WhisperModelState
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSecurity: () -> Unit,
) {
    val currentChat by viewModel.currentChat.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isTranscribing by viewModel.isTranscribing.collectAsState()
    val audioAmplitudes by viewModel.audioAmplitudes.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val extendedThinking by viewModel.extendedThinking.collectAsState()
    val searchApproved by viewModel.searchApprovedForChat.collectAsState()
    val pendingSearchApproval by viewModel.pendingSearchApproval.collectAsState()
    val attachedFiles by viewModel.attachedFiles.collectAsState()
    val filteredModels by viewModel.filteredModels.collectAsState()
    val modelsLoaded by viewModel.modelsLoaded.collectAsState()
    val whisperModelState by viewModel.whisperModelState.collectAsState()
    val liveTranscription by viewModel.liveTranscription.collectAsState()
    val whisperLanguage by viewModel.whisperLanguage.collectAsState()
    val ttsModelState by viewModel.ttsModelState.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val speakingMessageId by viewModel.speakingMessageId.collectAsState()

    // Keep screen on while recording or transcribing
    if (isRecording || isTranscribing) {
        val activity = LocalContext.current as? android.app.Activity
        DisposableEffect(Unit) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    val messages = currentChat?.messages ?: emptyList()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundLight)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Top bar
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentChat?.title ?: "Pretty Private AI",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (modelsLoaded) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = "Secure",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onNavigateToSecurity() },
                            tint = SecurityGreen,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = SurfaceWhite,
            ),
        )

        // Messages area
        if (currentChat == null || messages.isEmpty()) {
            EmptyState(
                hasChat = currentChat != null,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        ttsReady = ttsModelState is TtsModelState.Ready,
                        isSpeaking = speakingMessageId == message.id,
                        onSpeak = { viewModel.speakMessage(message.id, message.content) },
                        onStopSpeaking = { viewModel.stopSpeaking() },
                    )
                }
            }
        }

        // Input area
        ChatInputBar(
            messageText = messageText,
            onMessageChange = { viewModel.setMessageText(it) },
            selectedModel = selectedModel,
            extendedThinking = extendedThinking,
            searchApproved = searchApproved,
            isGenerating = isGenerating,
            isUploading = isUploading,
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            audioAmplitudes = audioAmplitudes,
            statusMessage = statusMessage,
            attachedFiles = attachedFiles,
            onSend = { viewModel.sendMessage() },
            onStop = { viewModel.stopGeneration() },
            onModelSelect = { viewModel.selectModel(it) },
            onToggleThinking = { viewModel.toggleExtendedThinking() },
            onAttachFile = { context, uri -> viewModel.uploadFile(context, uri) },
            onAttachImage = { context, uri -> viewModel.attachImage(context, uri) },
            onStartRecording = { context -> viewModel.startRecording(context) },
            onStopRecording = { viewModel.stopRecording() },
            onRemoveFile = { viewModel.removeAttachedFile(it) },
            supportsFileUploads = viewModel.supportsFileUploads(),
            supportsImageInput = viewModel.supportsImageInput(),
            supportsExtendedThinking = viewModel.supportsExtendedThinking(),
            wordCount = viewModel.getWordCount(),
            maxWords = viewModel.getMaxWords(),
            messageWordCount = countWords(messageText),
            attachedFilesWordCount = attachedFiles.sumOf { countWords(it.content) },
            filteredModels = filteredModels,
            whisperModelState = whisperModelState,
            liveTranscription = liveTranscription,
            whisperLanguage = whisperLanguage,
            onWhisperLanguageChange = { viewModel.setWhisperLanguage(it) },
            onAttachAudio = { ctx, uri -> viewModel.attachAudioFile(ctx, uri) },
            onCancelTranscription = { viewModel.cancelTranscription() },
            ttsReady = ttsModelState is TtsModelState.Ready,
            onReadToMe = { viewModel.sendReadToMe() },
        )
    }

    pendingSearchApproval?.let { pending ->
        SearchApprovalDialog(
            query = pending.query,
            onApprove = { viewModel.approveSearch() },
            onApproveAll = { viewModel.approveAllSearches() },
            onSkip = { viewModel.answerWithoutSearch() },
            onNever = { viewModel.neverSearchThisChat() },
        )
    }
}

@Composable
private fun EmptyState(hasChat: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextTertiary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (hasChat) "Start a conversation" else "No chat selected",
            style = MaterialTheme.typography.headlineMedium,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasChat) "Send a message to begin chatting" else "Start a new conversation to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
        )
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    ttsReady: Boolean = false,
    isSpeaking: Boolean = false,
    onSpeak: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Assistant header
        if (!isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Purple,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "PPAI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (ttsReady && message.content.isNotEmpty()) {
                    IconButton(
                        onClick = { if (isSpeaking) onStopSpeaking() else onSpeak() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isSpeaking) "Stop" else "Speak",
                            modifier = Modifier.size(14.dp),
                            tint = if (isSpeaking) Purple else TextTertiary,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = TextTertiary,
                    )
                }
            }
        }

        // Attached files
        if (!message.attachedFiles.isNullOrEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (file in message.attachedFiles) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUser) SurfaceWhite.copy(alpha = 0.1f) else BackgroundLight,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = TextSecondary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp),
                            )
                        }
                    }
                }
            }
        }

        // Message content
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) SurfaceWhite else androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextUser,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                if (message.content.isEmpty()) {
                    // Streaming indicator
                    Text(
                        text = "...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        modifier = Modifier.padding(4.dp),
                    )
                } else {
                    // Markdown rendered content
                    MarkdownContent(
                        content = message.content,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }

        // User message actions (speak + copy)
        if (isUser && message.content.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ttsReady) {
                    IconButton(
                        onClick = { if (isSpeaking) onStopSpeaking() else onSpeak() },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (isSpeaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isSpeaking) "Stop" else "Speak",
                            modifier = Modifier.size(14.dp),
                            tint = if (isSpeaking) Purple else TextTertiary,
                        )
                    }
                }
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownContent(content: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val textColor = TextPrimary.toArgb()
    val linkColor = Purple.toArgb()

    val markwon = remember(context) {
        MarkdownRenderer.create(context)
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                textSize = 15f
                linksClickable = true
            }
        },
        update = { textView ->
            val spanned = markwon.toMarkdown(content)
            markwon.setParsedMarkdown(textView, spanned)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatInputBar(
    messageText: String,
    onMessageChange: (String) -> Unit,
    selectedModel: String?,
    extendedThinking: Boolean,
    searchApproved: Boolean,
    isGenerating: Boolean,
    isUploading: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    audioAmplitudes: List<Float>,
    statusMessage: String?,
    attachedFiles: List<ch.brenzi.prettyprivateai.data.model.AttachedFile>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModelSelect: (String) -> Unit,
    onToggleThinking: () -> Unit,
    onAttachFile: (context: android.content.Context, uri: android.net.Uri) -> Unit,
    onAttachImage: (context: android.content.Context, uri: android.net.Uri) -> Unit,
    onStartRecording: (context: android.content.Context) -> Unit,
    onStopRecording: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    supportsFileUploads: Boolean,
    supportsImageInput: Boolean,
    supportsExtendedThinking: Boolean,
    wordCount: Int,
    maxWords: Int,
    messageWordCount: Int,
    attachedFilesWordCount: Int,
    filteredModels: List<ch.brenzi.prettyprivateai.data.model.ApiModel>,
    whisperModelState: WhisperModelState = WhisperModelState.NotDownloaded,
    liveTranscription: String = "",
    whisperLanguage: String = "auto",
    onWhisperLanguageChange: (String) -> Unit = {},
    onAttachAudio: (context: android.content.Context, uri: android.net.Uri) -> Unit = { _, _ -> },
    onCancelTranscription: () -> Unit = {},
    ttsReady: Boolean = false,
    onReadToMe: () -> Unit = {},
) {
    val languageOptions = listOf("auto", "en", "de")
    val whisperModelReady = whisperModelState is WhisperModelState.Ready
    val context = LocalContext.current
    val totalWordCount = wordCount + messageWordCount + attachedFilesWordCount
    val usagePercentage = min((totalWordCount.toFloat() / maxWords * 100), 100f)
    val wouldExceedLimit = totalWordCount > maxWords

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onAttachFile(context, uri)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onAttachImage(context, uri)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onAttachAudio(context, uri)
        }
    }

    var cameraImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { onAttachImage(context, it) }
        }
    }

    val launchCamera = {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            java.io.File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg"),
        )
        cameraImageUri = uri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    var showAttachMenu by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartRecording(context)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceWhite,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            // Attached files
            AnimatedVisibility(visible = attachedFiles.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    attachedFiles.forEachIndexed { index, file ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BackgroundLight,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (file.imageBase64 != null) Icons.Default.Image else Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = TextSecondary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onRemoveFile(index) },
                                    modifier = Modifier.size(18.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp),
                                        tint = TextTertiary,
                                    )
                                }
                            }
                        }
                    }
                    if (ttsReady) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Purple.copy(alpha = 0.1f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { onReadToMe() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Purple,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Read to me",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Purple,
                                )
                            }
                        }
                    }
                }
            }

            // Text input, waveform, or live transcription
            if (isRecording) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (liveTranscription.isNotBlank()) {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(liveTranscription) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                        Text(
                            text = liveTranscription,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(bottom = 4.dp),
                        )
                    }
                    WaveformIndicator(
                        amplitudes = audioAmplitudes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (liveTranscription.isNotBlank()) 36.dp else 56.dp),
                    )
                }
            } else if (isTranscribing && liveTranscription.isNotBlank()) {
                val scrollState = rememberScrollState()
                LaunchedEffect(liveTranscription) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                Text(
                    text = liveTranscription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .verticalScroll(scrollState)
                        .padding(4.dp),
                )
            } else {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type a message...") },
                    enabled = !isGenerating,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BorderLight,
                        unfocusedBorderColor = BorderMedium,
                        disabledBorderColor = BorderMedium.copy(alpha = 0.5f),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status row (above buttons)
            val downloadingState = whisperModelState as? WhisperModelState.Downloading
            if (isUploading || isTranscribing || statusMessage != null || downloadingState != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = statusMessage
                            ?: if (downloadingState != null) "STT model ${(downloadingState.progress * 100).roundToInt()}%"
                            else if (isTranscribing) "Transcribing..."
                            else "Uploading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusMessage != null && (statusMessage.contains("failed") || statusMessage.contains("timed out"))) ErrorRed else TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isTranscribing) {
                        IconButton(
                            onClick = onCancelTranscription,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel transcription",
                                modifier = Modifier.size(16.dp),
                                tint = TextTertiary,
                            )
                        }
                    }
                }
            }

            // Button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Attach button
                    Box {
                        IconButton(
                            onClick = {
                                if (supportsImageInput || whisperModelReady) {
                                    showAttachMenu = true
                                } else {
                                    filePickerLauncher.launch("*/*")
                                }
                            },
                            enabled = !isGenerating && !isUploading && (supportsFileUploads || whisperModelReady),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach file",
                                modifier = Modifier.size(20.dp),
                                tint = if (supportsFileUploads) TextSecondary else TextTertiary,
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo Gallery") },
                                onClick = {
                                    showAttachMenu = false
                                    imagePickerLauncher.launch("image/*")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                onClick = {
                                    showAttachMenu = false
                                    val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.CAMERA,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (hasPerm) {
                                        launchCamera()
                                    } else {
                                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                            )
                            if (whisperModelReady) {
                                DropdownMenuItem(
                                    text = { Text("Transcribe Audio") },
                                    onClick = {
                                        showAttachMenu = false
                                        audioPickerLauncher.launch("audio/*")
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                                    },
                                )
                            }
                        }
                    }

                    // Mic button
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                onStopRecording()
                            } else {
                                val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    onStartRecording(context)
                                } else {
                                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !isGenerating && !isTranscribing && (isRecording || whisperModelReady),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Voice input",
                            modifier = Modifier.size(20.dp),
                            tint = if (isRecording) ErrorRed
                                else if (whisperModelReady) TextSecondary
                                else TextTertiary,
                        )
                    }

                    // Language selector — cycles auto → en → de
                    if (whisperModelReady && !isGenerating) {
                        Surface(
                            modifier = Modifier
                                .clickable(enabled = !isRecording && !isTranscribing) {
                                    val idx = languageOptions.indexOf(whisperLanguage)
                                    val next = languageOptions[(idx + 1) % languageOptions.size]
                                    onWhisperLanguageChange(next)
                                },
                            shape = RoundedCornerShape(6.dp),
                            color = BackgroundLight,
                        ) {
                            Text(
                                text = whisperLanguage.uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (whisperLanguage == "auto") TextTertiary else Purple,
                            )
                        }
                    }

                    // Copy & share — visible when text field has content
                    if (messageText.isNotBlank() && !isRecording && !isGenerating) {
                        val clipboardManager = LocalClipboardManager.current
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(messageText)) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy text",
                                modifier = Modifier.size(20.dp),
                                tint = TextSecondary,
                            )
                        }
                        IconButton(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, messageText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share text",
                                modifier = Modifier.size(20.dp),
                                tint = TextSecondary,
                            )
                        }
                    }

                    // Extended thinking toggle
                    AnimatedVisibility(visible = supportsExtendedThinking) {
                        IconButton(
                            onClick = onToggleThinking,
                            enabled = !isGenerating,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = "Extended thinking",
                                modifier = Modifier.size(20.dp),
                                tint = if (extendedThinking) Purple else TextSecondary,
                            )
                        }
                    }

                    // Web search indicator (read-only)
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Web search",
                        modifier = Modifier.size(20.dp).padding(8.dp),
                        tint = if (searchApproved) Purple else TextSecondary.copy(alpha = 0.3f),
                    )
                }

                // Right controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Token usage indicator
                    if (usagePercentage >= 75) {
                        val bgColor = if (usagePercentage >= 100) DangerBg else WarningBg
                        val textColor = if (usagePercentage >= 100) ErrorRed else WarningYellow
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = bgColor,
                        ) {
                            Text(
                                text = "${usagePercentage.roundToInt()}%",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                            )
                        }
                    }

                    // Model picker
                    ModelPickerButton(
                        selectedModel = selectedModel,
                        models = filteredModels,
                        onModelSelect = onModelSelect,
                    )

                    // Send/Stop button
                    if (isGenerating) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ErrorRed.copy(alpha = 0.1f)),
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = ErrorRed,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onSend,
                            enabled = messageText.isNotBlank() && selectedModel != null && !wouldExceedLimit && !isUploading,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (messageText.isNotBlank() && selectedModel != null)
                                        Purple else Purple.copy(alpha = 0.3f)
                                ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = TextOnPurple,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaveformIndicator(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val barColor = Purple
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = BackgroundLight,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val barCount = 30
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
            val centerY = size.height / 2
            for (i in 0 until barCount) {
                val amp = amplitudes.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                val barHeight = (size.height * 0.15f + size.height * 0.85f * amp).coerceAtLeast(4f)
                val x = i * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
                )
            }
        }
    }
}

@Composable
private fun ModelPickerButton(
    selectedModel: String?,
    models: List<ch.brenzi.prettyprivateai.data.model.ApiModel>,
    onModelSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier.clickable { expanded = true },
            shape = RoundedCornerShape(6.dp),
            color = BackgroundLight,
        ) {
            Text(
                text = selectedModel?.let { MODEL_CONFIG[it]?.shortName } ?: "Model",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(SidebarDark),
        ) {
            models.forEach { model ->
                val config = MODEL_CONFIG[model.id]
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = config?.displayName ?: model.id,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextOnDark,
                                fontWeight = if (model.id == selectedModel) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (config?.subtitle != null) {
                                Text(
                                    text = config.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                )
                            }
                        }
                    },
                    onClick = {
                        onModelSelect(model.id)
                        expanded = false
                    },
                    modifier = Modifier.background(
                        if (model.id == selectedModel) SidebarItemActive else SidebarDark
                    ),
                )
            }
        }
    }
}

@Composable
private fun SearchApprovalDialog(
    query: String,
    onApprove: () -> Unit,
    onApproveAll: () -> Unit,
    onSkip: () -> Unit,
    onNever: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Web search requested") },
        text = {
            Column {
                Text("The model wants to search the web for:")
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = BackgroundLight) {
                    Text(
                        text = query,
                        modifier = Modifier.padding(12.dp),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = onApprove) { Text("Approve this search") }
                TextButton(onClick = onApproveAll) { Text("Approve all for this chat") }
                TextButton(onClick = onSkip) { Text("Answer without searching") }
                TextButton(onClick = onNever) { Text("Never search in this chat") }
            }
        },
    )
}
