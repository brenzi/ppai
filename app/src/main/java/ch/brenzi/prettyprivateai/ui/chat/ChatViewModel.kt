package ch.brenzi.prettyprivateai.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ch.brenzi.prettyprivateai.data.model.ApiModel
import ch.brenzi.prettyprivateai.data.model.AttachedFile
import ch.brenzi.prettyprivateai.data.model.Chat
import ch.brenzi.prettyprivateai.data.model.MODEL_CONFIG
import ch.brenzi.prettyprivateai.data.model.Message
import ch.brenzi.prettyprivateai.data.model.MessageRole
import ch.brenzi.prettyprivateai.data.model.countWords
import ch.brenzi.prettyprivateai.data.remote.ApiException
import ch.brenzi.prettyprivateai.data.remote.StartpageSearchClient
import ch.brenzi.prettyprivateai.data.repository.ChatRepository
import ch.brenzi.prettyprivateai.whisper.AudioDecoder
import ch.brenzi.prettyprivateai.whisper.AudioRecorder
import ch.brenzi.prettyprivateai.whisper.WhisperManager
import ch.brenzi.prettyprivateai.whisper.WhisperModelState
import ch.brenzi.prettyprivateai.whisper.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class ChatSearchPolicy { ASK, APPROVE_ALL, NEVER }

data class PendingSearchApproval(
    val chatId: String,
    val assistantMessageId: String,
    val query: String,
    val messagesToSend: List<Message>,
    val model: String,
    val reasoningEffort: String,
    val systemPrompt: String?,
    val supportsSystemRole: Boolean = true,
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val whisperManager: WhisperManager,
) : ViewModel() {
    private val TAG = "ChatViewModel"

    val chats: StateFlow<List<Chat>> = repository.chats
    val currentChatId: StateFlow<String?> = repository.currentChatId
    val modelsLoaded: StateFlow<Boolean> = repository.modelsLoaded

    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()

    private val _extendedThinking = MutableStateFlow(false)
    val extendedThinking: StateFlow<Boolean> = _extendedThinking.asStateFlow()

    private val chatSearchPolicies = mutableMapOf<String, ChatSearchPolicy>()
    private val _searchApprovedForChat = MutableStateFlow(false)
    val searchApprovedForChat: StateFlow<Boolean> = _searchApprovedForChat.asStateFlow()

    private val _pendingSearchApproval = MutableStateFlow<PendingSearchApproval?>(null)
    val pendingSearchApproval: StateFlow<PendingSearchApproval?> = _pendingSearchApproval.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow(0)
    val transcriptionProgress: StateFlow<Int> = _transcriptionProgress.asStateFlow()

    private val _audioAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val audioAmplitudes: StateFlow<List<Float>> = _audioAmplitudes.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val whisperModelState: StateFlow<WhisperModelState> = whisperManager.modelState

    private val _liveTranscription = MutableStateFlow("")
    val liveTranscription: StateFlow<String> = _liveTranscription.asStateFlow()

    private val _whisperLanguage = MutableStateFlow("auto")
    val whisperLanguage: StateFlow<String> = _whisperLanguage.asStateFlow()

    fun setWhisperLanguage(language: String) {
        _whisperLanguage.value = language
        viewModelScope.launch {
            repository.setWhisperLanguage(language)
        }
    }

    private var audioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var pipelineJob: Job? = null
    private var transcriptionJob: Job? = null

    // Shared pipeline state — written by pipeline coroutine, read by stopRecording
    private var pipelineTranscribedUpTo = 0
    private val pipelineCommitted = StringBuilder()

    private val _attachedFiles = MutableStateFlow<List<AttachedFile>>(emptyList())
    val attachedFiles: StateFlow<List<AttachedFile>> = _attachedFiles.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private var streamingJob: Job? = null

    companion object {
        /** Max time to wait for whisper transcription before aborting (ms). */
        internal const val TRANSCRIPTION_TIMEOUT_MS = 120_000L

        // Pipeline chunk constants (sample counts at 16 kHz)
        private const val CHUNK_SAMPLES = 10 * 16000      // 10 s target chunk
        private const val STEP_SAMPLES = 8 * 16000        // 8 s of new audio triggers next chunk
        private const val MIN_FIRST_SAMPLES = CHUNK_SAMPLES  // wait for full chunk first time
        private const val MIN_TAIL_SAMPLES = 8000          // 0.5 s minimum tail

        internal val SEARCH_PROBE_INSTRUCTION = """
You have access to a web search tool. When the user's question requires current or real-time information you lack, you MUST invoke it by responding with exactly:
[SEARCH: "your search query"]
Output NOTHING else — no preamble, no explanation, no apology. Just the command.
If you can answer from your existing knowledge, answer normally without using the tool.""".trimIndent()

        internal val SEARCH_MARKER_REGEX = Regex("""\[SEARCH:\s*"(.+?)"\s*]""")

        /** Patterns that indicate the model refused to answer due to lacking real-time info. */
        internal val REFUSAL_PATTERNS = listOf(
            "real-time",
            "training data",
            "cutoff",
            "cannot access",
            "can't access",
            "don't have access",
            "do not have access",
            "cannot browse",
            "can't browse",
            "no ability to search",
            "unable to provide current",
            "unable to access",
        )
    }

    val currentChat: StateFlow<Chat?> = combine(chats, currentChatId) { chats, chatId ->
        chatId?.let { id -> chats.find { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredModels: StateFlow<List<ApiModel>> = repository.availableModels
        .map { available -> MODEL_CONFIG.keys.mapNotNull { id -> available.find { it.id == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.selectedModel.collect { model ->
                _selectedModel.value = model
            }
        }
        viewModelScope.launch {
            repository.extendedThinking.collect { enabled ->
                _extendedThinking.value = enabled
            }
        }
        viewModelScope.launch {
            repository.whisperLanguage.collect { lang ->
                _whisperLanguage.value = lang
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            repository.loadModels()
        }
    }

    fun setMessageText(text: String) {
        _messageText.value = text
    }

    fun selectModel(modelId: String) {
        _selectedModel.value = modelId
        viewModelScope.launch {
            repository.setSelectedModel(modelId)
        }
    }

    fun toggleExtendedThinking() {
        val newValue = !_extendedThinking.value
        _extendedThinking.value = newValue
        viewModelScope.launch {
            repository.setExtendedThinking(newValue)
        }
    }

    fun selectChat(chatId: String) {
        repository.setCurrentChatId(chatId)
        _searchApprovedForChat.value = chatSearchPolicies[chatId] == ChatSearchPolicy.APPROVE_ALL
    }

    fun createNewChat() {
        viewModelScope.launch {
            val chatId = repository.createChat()
            repository.setCurrentChatId(chatId)
            _searchApprovedForChat.value = false
        }
    }

    fun renameChat(chatId: String, newTitle: String) {
        viewModelScope.launch {
            repository.renameChat(chatId, newTitle)
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
        }
    }

    fun removeAttachedFile(index: Int) {
        _attachedFiles.value = _attachedFiles.value.filterIndexed { i, _ -> i != index }
    }

    fun uploadFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isUploading.value = true
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                val fileName = getFileName(context, uri)

                val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}")
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                val elements = repository.uploadFile(tempFile, fileName)
                val extractedText = elements.joinToString("\n\n") { it.text }

                _attachedFiles.value = _attachedFiles.value + AttachedFile(
                    name = fileName,
                    content = extractedText,
                )

                tempFile.delete()
            } catch (e: Exception) {
                // The UI will show an error via snackbar
                throw e
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun attachImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isUploading.value = true
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open image")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                bitmap ?: throw Exception("Cannot decode image")

                val maxDim = 1024
                val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true,
                    )
                } else {
                    bitmap
                }

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                val fileName = getFileName(context, uri)
                _attachedFiles.value = _attachedFiles.value + AttachedFile(
                    name = fileName,
                    content = "",
                    imageBase64 = base64,
                    mimeType = "image/jpeg",
                )
            } catch (e: Exception) {
                throw e
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        whisperManager.abortTranscription()
        _isTranscribing.value = false
        _liveTranscription.value = ""
        _statusMessage.value = null
    }

    fun attachAudioFile(context: Context, uri: Uri) {
        if (!whisperManager.isReady()) return
        transcriptionJob = viewModelScope.launch {
            _isTranscribing.value = true
            _liveTranscription.value = ""
            _statusMessage.value = "Transcribing audio file..."
            try {
                val language = _whisperLanguage.value
                val committed = StringBuilder()

                withContext(Dispatchers.IO) {
                    AudioDecoder.decodeChunked(context, uri) { chunk, chunkIndex, estTotal ->
                        val pct = ((chunkIndex + 1) * 100 / maxOf(estTotal, chunkIndex + 1))
                            .coerceIn(0, 100)
                        Log.i(TAG, "Transcribing file chunk ${chunkIndex+1}/$estTotal (${chunk.size / 16000f}s) $pct%")
                        _statusMessage.value = "Transcribing ${chunkIndex + 1}/$estTotal ($pct%)"
                        val text = whisperManager.transcribe(chunk, language)
                        if (text.isNotBlank()) {
                            if (committed.isEmpty()) {
                                committed.append(text.trim())
                            } else {
                                committed.append(" ").append(text.trim())
                            }
                            _liveTranscription.value = committed.toString()
                        }
                    }
                }

                val result = committed.toString().trim()
                if (result.isNotBlank()) {
                    val current = _messageText.value
                    _messageText.value = if (current.isBlank()) result else "$current $result"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Audio file transcription cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Audio file transcription failed", e)
                _statusMessage.value = "Audio transcription failed: ${e.message}"
                viewModelScope.launch {
                    delay(4000)
                    _statusMessage.compareAndSet("Audio transcription failed: ${e.message}", null)
                }
            } finally {
                _isTranscribing.value = false
                _liveTranscription.value = ""
                // Clear progress messages; error messages have their own delayed clear
                val msg = _statusMessage.value
                if (msg != null && !msg.contains("failed") && !msg.contains("timed out")) {
                    _statusMessage.value = null
                }
            }
        }
    }

    fun startRecording(context: Context) {
        if (_isRecording.value || !whisperManager.isReady()) return
        val recorder = AudioRecorder()
        audioRecorder = recorder
        _isRecording.value = true
        _audioAmplitudes.value = emptyList()
        _liveTranscription.value = ""
        pipelineTranscribedUpTo = 0
        pipelineCommitted.clear()

        // Collect amplitudes for waveform
        viewModelScope.launch {
            recorder.amplitudes.collect { amps ->
                _audioAmplitudes.value = amps
            }
        }

        // Start recording (blocking) on IO
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                recorder.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
            }
        }

        // Start pipelined transcription on Default
        pipelineJob = viewModelScope.launch(Dispatchers.Default) {
            runTranscriptionPipeline(recorder)
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        val recorder = audioRecorder ?: return
        recorder.stopRecording()
        _isRecording.value = false

        // Abort any in-progress pipeline transcription so the native call returns fast
        whisperManager.abortTranscription()

        val recJob = recordingJob
        val pipJob = pipelineJob
        recordingJob = null
        pipelineJob = null
        audioRecorder = null

        viewModelScope.launch {
            _isTranscribing.value = true
            _transcriptionProgress.value = 0
            try {
                // Wait for recording IO and pipeline to finish
                recJob?.join()
                pipJob?.cancel()
                pipJob?.join()

                // Transcribe un-processed tail
                val total = recorder.getSampleCount()
                val tailSamples = recorder.getSamplesRange(pipelineTranscribedUpTo, total)

                if (tailSamples.size >= MIN_TAIL_SAMPLES) {
                    val text = withContext(Dispatchers.Default) {
                        withTimeout(TRANSCRIPTION_TIMEOUT_MS) {
                            whisperManager.transcribe(tailSamples, _whisperLanguage.value)
                        }
                    }
                    if (text.isNotBlank()) {
                        if (pipelineCommitted.isEmpty()) {
                            pipelineCommitted.append(text.trim())
                        } else {
                            pipelineCommitted.append(" ").append(text.trim())
                        }
                    }
                }

                // Commit to message text
                val result = pipelineCommitted.toString().trim()
                if (result.isNotBlank()) {
                    val current = _messageText.value
                    _messageText.value = if (current.isBlank()) result else "$current $result"
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Tail transcription timed out", e)
                whisperManager.abortTranscription()
                // Still commit whatever the pipeline produced
                val result = pipelineCommitted.toString().trim()
                if (result.isNotBlank()) {
                    val current = _messageText.value
                    _messageText.value = if (current.isBlank()) result else "$current $result"
                }
                _statusMessage.value = "Transcription timed out"
                viewModelScope.launch {
                    delay(4000)
                    _statusMessage.compareAndSet("Transcription timed out", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tail transcription failed", e)
                // Still commit whatever the pipeline produced
                val result = pipelineCommitted.toString().trim()
                if (result.isNotBlank()) {
                    val current = _messageText.value
                    _messageText.value = if (current.isBlank()) result else "$current $result"
                }
                _statusMessage.value = "Transcription failed: ${e.message}"
                viewModelScope.launch {
                    delay(4000)
                    _statusMessage.compareAndSet("Transcription failed: ${e.message}", null)
                }
            } finally {
                _transcriptionProgress.value = 0
                _isTranscribing.value = false
                _liveTranscription.value = ""
            }
        }
    }

    private suspend fun runTranscriptionPipeline(recorder: AudioRecorder) {
        while (coroutineContext[Job]?.isActive == true && _isRecording.value) {
            val total = recorder.getSampleCount()
            val newAudio = total - pipelineTranscribedUpTo
            val threshold = if (pipelineCommitted.isEmpty()) MIN_FIRST_SAMPLES else STEP_SAMPLES

            if (newAudio < threshold) {
                delay(200)
                continue
            }

            // Find a silence gap near the target chunk end to avoid splitting words
            val targetEnd = minOf(total, pipelineTranscribedUpTo + CHUNK_SAMPLES)
            val chunkEnd = recorder.findSilenceGap(targetEnd)
                .coerceIn(pipelineTranscribedUpTo + 1, total)
            val samples = recorder.getSamplesRange(pipelineTranscribedUpTo, chunkEnd)

            try {
                val text = whisperManager.transcribe(samples, _whisperLanguage.value)
                if (text.isNotBlank()) {
                    if (pipelineCommitted.isEmpty()) {
                        pipelineCommitted.append(text.trim())
                    } else {
                        pipelineCommitted.append(" ").append(text.trim())
                    }
                    _liveTranscription.value = pipelineCommitted.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pipeline chunk transcription failed", e)
            }
            pipelineTranscribedUpTo = chunkEnd
        }
    }

    fun sendMessage() {
        val model = _selectedModel.value ?: return
        val text = _messageText.value.trim()
        if (text.isEmpty() || _isGenerating.value) return

        val modelInfo = MODEL_CONFIG[model]
        val maxWords = modelInfo?.maxWords ?: 60000
        val currentChat = currentChat.value
        val currentWordCount = currentChat?.wordCount ?: 0
        val messageWordCount = countWords(text)
        val attachedFilesWordCount = _attachedFiles.value.sumOf { countWords(it.content) }

        if (currentWordCount + messageWordCount + attachedFilesWordCount > maxWords) return

        viewModelScope.launch {
            var chatId = currentChatId.value
            if (chatId == null) {
                chatId = repository.createChat()
                repository.setCurrentChatId(chatId)
            }

            val filesToSend = _attachedFiles.value.toList()
            _messageText.value = ""
            _attachedFiles.value = emptyList()

            repository.addMessage(
                chatId,
                MessageRole.USER,
                text,
                filesToSend.ifEmpty { null },
            )

            val assistantMessageId = repository.addMessage(
                chatId,
                MessageRole.ASSISTANT,
                "",
            )

            _isGenerating.value = true
            repository.setStreaming(chatId, true)

            val reasoningEffort = if (_extendedThinking.value) "high" else "medium"
            val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
            val baseSystemPrompt = modelInfo?.systemPrompt?.let { "Current date and time: $now\n\n$it" }
            val supportsSystemRole = modelInfo?.supportsSystemRole ?: true
            val chatPolicy = chatSearchPolicies[chatId] ?: ChatSearchPolicy.ASK
            val useProbe = chatPolicy != ChatSearchPolicy.NEVER

            streamingJob = viewModelScope.launch {
                var handledByApproval = false
                try {
                    val chat = repository.getChat(chatId) ?: throw Exception("Chat not found")
                    val messagesToSend = chat.messages.filter { it.id != assistantMessageId }

                    Log.d(TAG, "--- REQUEST ---")
                    Log.d(TAG, "model=$model reasoning=$reasoningEffort chatPolicy=$chatPolicy useProbe=$useProbe")
                    for ((idx, msg) in messagesToSend.withIndex()) {
                        Log.d(TAG, "msg[$idx] role=${msg.role} content=${msg.content.take(500)}")
                    }

                    if (!useProbe) {
                        Log.d(TAG, "systemPrompt=${baseSystemPrompt?.take(500)}")
                        streamToAssistantMessage(
                            chatId = chatId,
                            assistantMessageId = assistantMessageId,
                            model = model,
                            messages = messagesToSend,
                            systemPrompt = baseSystemPrompt,
                            reasoningEffort = reasoningEffort,
                            searchContext = null,
                            supportsSystemRole = supportsSystemRole,
                        )
                    } else {
                        val probeSystemPrompt = (baseSystemPrompt ?: "") + "\n" + SEARCH_PROBE_INSTRUCTION
                        Log.d(TAG, "probeSystemPrompt=$probeSystemPrompt")

                        val content = streamToAssistantMessage(
                            chatId = chatId,
                            assistantMessageId = assistantMessageId,
                            model = model,
                            messages = messagesToSend,
                            systemPrompt = probeSystemPrompt,
                            reasoningEffort = reasoningEffort,
                            searchContext = null,
                            supportsSystemRole = supportsSystemRole,
                        )

                        Log.d(TAG, "--- RESPONSE ---")
                        Log.d(TAG, "fullContent=<<<$content>>>")

                        val match = SEARCH_MARKER_REGEX.find(content)
                        Log.d(TAG, "regexMatch=${match != null} matchValue=${match?.value} group1=${match?.groupValues?.getOrNull(1)}")

                        if (match != null) {
                            val query = match.groupValues[1]
                            Log.d(TAG, "searchDetected query=$query chatPolicy=$chatPolicy")
                            repository.updateMessage(chatId, assistantMessageId, "")

                            val pending = PendingSearchApproval(
                                chatId = chatId,
                                assistantMessageId = assistantMessageId,
                                query = query,
                                messagesToSend = messagesToSend,
                                model = model,
                                reasoningEffort = reasoningEffort,
                                systemPrompt = baseSystemPrompt,
                                supportsSystemRole = supportsSystemRole,
                            )

                            if (chatPolicy == ChatSearchPolicy.APPROVE_ALL) {
                                Log.d(TAG, "autoApproving (APPROVE_ALL)")
                                handledByApproval = true
                                performSearchAndResend(pending)
                            } else {
                                Log.d(TAG, "showingDialog (ASK)")
                                handledByApproval = true
                                _pendingSearchApproval.value = pending
                            }
                        } else {
                            // Fallback: model refused instead of using the tool
                            val contentLower = content.lowercase()
                            val isRefusal = REFUSAL_PATTERNS.any { it in contentLower }
                            Log.d(TAG, "noSearchMarker isRefusal=$isRefusal")

                            if (isRefusal) {
                                Log.d(TAG, "refusalDetected — using user message as search query")
                                repository.updateMessage(chatId, assistantMessageId, "")

                                val pending = PendingSearchApproval(
                                    chatId = chatId,
                                    assistantMessageId = assistantMessageId,
                                    query = text,
                                    messagesToSend = messagesToSend,
                                    model = model,
                                    reasoningEffort = reasoningEffort,
                                    systemPrompt = baseSystemPrompt,
                                    supportsSystemRole = supportsSystemRole,
                                )

                                if (chatPolicy == ChatSearchPolicy.APPROVE_ALL) {
                                    Log.d(TAG, "autoApproving refusal fallback (APPROVE_ALL)")
                                    handledByApproval = true
                                    performSearchAndResend(pending)
                                } else {
                                    Log.d(TAG, "showingDialog for refusal fallback (ASK)")
                                    handledByApproval = true
                                    _pendingSearchApproval.value = pending
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Stream cancelled by user")
                    } else {
                        Log.e(TAG, "Stream error", e)
                        var errorMessage = "Error: ${e.message ?: "Unknown error"}"
                        if (e is ApiException && e.statusCode == 401) {
                            errorMessage += "\n\nYour API key may be invalid or expired. Please update your API key in settings."
                        }
                        repository.updateMessage(chatId, assistantMessageId, errorMessage)
                    }
                } finally {
                    if (!handledByApproval) {
                        repository.setStreaming(chatId, false)
                        repository.saveAfterStreaming()
                        _isGenerating.value = false
                        streamingJob = null
                    }
                }
            }
        }
    }

    private suspend fun streamToAssistantMessage(
        chatId: String, assistantMessageId: String, model: String,
        messages: List<Message>, systemPrompt: String?,
        reasoningEffort: String, searchContext: String?,
        supportsSystemRole: Boolean = true,
    ): String {
        var accumulatedContent = ""
        var lastUpdate = 0L
        val updateThrottleMs = 100L

        repository.streamChatCompletion(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            reasoningEffort = reasoningEffort,
            searchContext = searchContext,
            supportsSystemRole = supportsSystemRole,
        ).collect { chunk ->
            accumulatedContent += chunk
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= updateThrottleMs) {
                repository.updateMessage(chatId, assistantMessageId, accumulatedContent)
                lastUpdate = now
            }
        }

        Log.i(TAG, "Stream completed, content length: ${accumulatedContent.length}")
        repository.updateMessage(chatId, assistantMessageId, accumulatedContent)
        return accumulatedContent
    }

    private fun performSearchAndResend(pending: PendingSearchApproval) {
        Log.d(TAG, "performSearchAndResend: query=${pending.query} model=${pending.model}")
        streamingJob = viewModelScope.launch {
            try {
                repository.updateMessage(pending.chatId, pending.assistantMessageId, "Searching the web from your phone...")
                var searchContext: String? = null
                try {
                    val searchClient = StartpageSearchClient()
                    val results = searchClient.search(pending.query)
                    if (results.isNotEmpty()) {
                        val topPageContent = searchClient.fetchPageContent(results[0].url)
                        searchContext = buildString {
                            appendLine("[Web Search Results]")
                            results.forEachIndexed { i, r ->
                                appendLine("${i + 1}. ${r.title}")
                                appendLine("   ${r.snippet}")
                                appendLine("   Source: ${r.url}")
                            }
                            if (topPageContent != null) {
                                appendLine()
                                appendLine("[Full content of top result: ${results[0].title}]")
                                appendLine(topPageContent)
                                appendLine("[End of full content]")
                            }
                            appendLine()
                            appendLine("Use these results to inform your response. Cite sources when relevant.")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Web search failed, proceeding without results", e)
                }

                repository.updateMessage(pending.chatId, pending.assistantMessageId, "")
                streamToAssistantMessage(
                    chatId = pending.chatId,
                    assistantMessageId = pending.assistantMessageId,
                    model = pending.model,
                    messages = pending.messagesToSend,
                    systemPrompt = pending.systemPrompt,
                    reasoningEffort = pending.reasoningEffort,
                    searchContext = searchContext,
                    supportsSystemRole = pending.supportsSystemRole,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Search stream cancelled by user")
                } else {
                    Log.e(TAG, "Search stream error", e)
                    repository.updateMessage(pending.chatId, pending.assistantMessageId, "Error: ${e.message ?: "Unknown error"}")
                }
            } finally {
                repository.setStreaming(pending.chatId, false)
                repository.saveAfterStreaming()
                _isGenerating.value = false
                streamingJob = null
            }
        }
    }

    private fun resendWithoutSearch(pending: PendingSearchApproval) {
        streamingJob = viewModelScope.launch {
            try {
                repository.updateMessage(pending.chatId, pending.assistantMessageId, "")
                streamToAssistantMessage(
                    chatId = pending.chatId,
                    assistantMessageId = pending.assistantMessageId,
                    model = pending.model,
                    messages = pending.messagesToSend,
                    systemPrompt = pending.systemPrompt,
                    reasoningEffort = pending.reasoningEffort,
                    searchContext = null,
                    supportsSystemRole = pending.supportsSystemRole,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Resend stream cancelled by user")
                } else {
                    Log.e(TAG, "Resend stream error", e)
                    repository.updateMessage(pending.chatId, pending.assistantMessageId, "Error: ${e.message ?: "Unknown error"}")
                }
            } finally {
                repository.setStreaming(pending.chatId, false)
                repository.saveAfterStreaming()
                _isGenerating.value = false
                streamingJob = null
            }
        }
    }

    fun approveSearch() {
        Log.d(TAG, "approveSearch")
        val pending = _pendingSearchApproval.value ?: return
        _pendingSearchApproval.value = null
        performSearchAndResend(pending)
    }

    fun approveAllSearches() {
        Log.d(TAG, "approveAllSearches")
        val pending = _pendingSearchApproval.value ?: return
        _pendingSearchApproval.value = null
        chatSearchPolicies[pending.chatId] = ChatSearchPolicy.APPROVE_ALL
        _searchApprovedForChat.value = true
        performSearchAndResend(pending)
    }

    fun answerWithoutSearch() {
        Log.d(TAG, "answerWithoutSearch")
        val pending = _pendingSearchApproval.value ?: return
        _pendingSearchApproval.value = null
        resendWithoutSearch(pending)
    }

    fun neverSearchThisChat() {
        Log.d(TAG, "neverSearchThisChat")
        val pending = _pendingSearchApproval.value ?: return
        _pendingSearchApproval.value = null
        chatSearchPolicies[pending.chatId] = ChatSearchPolicy.NEVER
        resendWithoutSearch(pending)
    }

    fun stopGeneration() {
        streamingJob?.cancel()
        streamingJob = null
        val pending = _pendingSearchApproval.value
        if (pending != null) {
            _pendingSearchApproval.value = null
            _isGenerating.value = false
            viewModelScope.launch {
                repository.setStreaming(pending.chatId, false)
                repository.saveAfterStreaming()
            }
        }
    }

    fun getWordCount(): Int {
        return currentChat.value?.wordCount ?: 0
    }

    fun getMaxWords(): Int {
        val model = _selectedModel.value ?: return 60000
        return MODEL_CONFIG[model]?.maxWords ?: 60000
    }

    fun supportsFileUploads(): Boolean {
        val model = _selectedModel.value ?: return true
        val config = MODEL_CONFIG[model] ?: return false
        return config.supportsFileUploads || config.supportsImageInput
    }

    fun supportsImageInput(): Boolean {
        val model = _selectedModel.value ?: return false
        return MODEL_CONFIG[model]?.supportsImageInput ?: false
    }

    fun supportsExtendedThinking(): Boolean {
        val model = _selectedModel.value ?: return false
        return MODEL_CONFIG[model]?.supportsExtendedThinking ?: false
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    class Factory(
        private val repository: ChatRepository,
        private val whisperManager: WhisperManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository, whisperManager) as T
        }
    }
}
