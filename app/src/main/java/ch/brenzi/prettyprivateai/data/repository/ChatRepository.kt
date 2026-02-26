package ch.brenzi.prettyprivateai.data.repository

import ch.brenzi.prettyprivateai.data.local.ChatStorage
import ch.brenzi.prettyprivateai.data.local.PreferencesManager
import ch.brenzi.prettyprivateai.data.model.ApiModel
import ch.brenzi.prettyprivateai.data.model.AttachedFile
import ch.brenzi.prettyprivateai.data.model.Chat
import ch.brenzi.prettyprivateai.data.model.DEFAULT_MODEL_ID
import ch.brenzi.prettyprivateai.data.model.MODEL_CONFIG
import ch.brenzi.prettyprivateai.data.model.Message
import ch.brenzi.prettyprivateai.data.model.MessageRole
import ch.brenzi.prettyprivateai.data.remote.PrivatemodeClient
import ch.brenzi.prettyprivateai.data.remote.UnstructuredElement
import ch.brenzi.prettyprivateai.proxy.ProxyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

class ChatRepository(
    val chatStorage: ChatStorage,
    val preferences: PreferencesManager,
    private val proxyManager: ProxyManager,
) {
    private val _availableModels = MutableStateFlow<List<ApiModel>>(emptyList())
    val availableModels: StateFlow<List<ApiModel>> = _availableModels.asStateFlow()

    private val _modelsLoaded = MutableStateFlow(false)
    val modelsLoaded: StateFlow<Boolean> = _modelsLoaded.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    val chats: StateFlow<List<Chat>> = chatStorage.chats
    val currentChatId: StateFlow<String?> = chatStorage.currentChatId

    val apiKey: Flow<String?> = preferences.apiKey
    val selectedModel: Flow<String?> = preferences.selectedModel
    val extendedThinking: Flow<Boolean> = preferences.extendedThinking
    val webSearch: Flow<Boolean> = preferences.webSearch
    val serverUrl: Flow<String> = preferences.serverUrl
    val whisperLanguage: Flow<String> = preferences.whisperLanguage

    private suspend fun createClient(): PrivatemodeClient? {
        val baseUrl = proxyManager.getBaseUrl()
        val key = try {
            preferences.getApiKey()
        } catch (_: Exception) {
            null
        }
        if (key == null) return null
        return PrivatemodeClient(baseUrl, key)
    }

    suspend fun initialize() {
        chatStorage.load()
    }

    suspend fun loadModels() {
        try {
            _modelsError.value = null
            val client = createClient() ?: run {
                _modelsError.value = "API key not configured"
                return
            }
            val models = client.fetchModels()
            _availableModels.value = models
            _modelsLoaded.value = true

            // Auto-select model if none selected
            val currentModel = preferences.selectedModel.first()
            if (currentModel == null) {
                val filteredModels = MODEL_CONFIG.keys
                    .mapNotNull { id -> models.find { it.id == id } }
                if (filteredModels.isNotEmpty()) {
                    val defaultModel = filteredModels.find { it.id == DEFAULT_MODEL_ID }
                        ?: filteredModels.first()
                    preferences.setSelectedModel(defaultModel.id)
                }
            }
        } catch (e: Exception) {
            _modelsError.value = e.message ?: "Failed to load models"
            _modelsLoaded.value = false
        }
    }

    fun getFilteredModels(): List<ApiModel> {
        return MODEL_CONFIG.keys
            .mapNotNull { id -> _availableModels.value.find { it.id == id } }
    }

    suspend fun createChat(): String {
        return chatStorage.createChat()
    }

    suspend fun addMessage(chatId: String, role: MessageRole, content: String, attachedFiles: List<AttachedFile>? = null): String {
        val message = Message(
            role = role,
            content = content,
            attachedFiles = attachedFiles,
        )
        return chatStorage.addMessage(chatId, message)
    }

    suspend fun updateMessage(chatId: String, messageId: String, content: String) {
        chatStorage.updateMessage(chatId, messageId, content)
    }

    fun setStreaming(chatId: String, isStreaming: Boolean) {
        chatStorage.setStreaming(chatId, isStreaming)
    }

    suspend fun streamChatCompletion(
        model: String,
        messages: List<Message>,
        systemPrompt: String?,
        reasoningEffort: String?,
        searchContext: String? = null,
        supportsSystemRole: Boolean = true,
    ): kotlinx.coroutines.flow.Flow<String> {
        val client = createClient() ?: throw IllegalStateException("API key not configured")
        return client.streamChatCompletion(model, messages, systemPrompt, reasoningEffort, searchContext, supportsSystemRole)
    }

    suspend fun uploadFile(file: File, fileName: String): List<UnstructuredElement> {
        val client = createClient() ?: throw IllegalStateException("API key not configured")
        return client.uploadFile(file, fileName)
    }

    suspend fun saveAfterStreaming() {
        chatStorage.saveAfterStreaming()
    }

    suspend fun setApiKey(key: String) {
        preferences.setApiKey(key)
    }

    suspend fun setSelectedModel(modelId: String) {
        preferences.setSelectedModel(modelId)
    }

    suspend fun setExtendedThinking(enabled: Boolean) {
        preferences.setExtendedThinking(enabled)
    }

    suspend fun setWebSearch(enabled: Boolean) {
        preferences.setWebSearch(enabled)
    }

    suspend fun setServerUrl(url: String) {
        preferences.setServerUrl(url)
    }

    suspend fun setWhisperLanguage(language: String) {
        preferences.setWhisperLanguage(language)
    }

    fun setCurrentChatId(chatId: String?) {
        chatStorage.setCurrentChatId(chatId)
    }

    suspend fun renameChat(chatId: String, newTitle: String) {
        chatStorage.renameChat(chatId, newTitle)
    }

    suspend fun deleteChat(chatId: String) {
        chatStorage.deleteChat(chatId)
    }

    suspend fun clearAllChats() {
        chatStorage.clearAllChats()
    }

    fun getChat(chatId: String): Chat? {
        return chatStorage.getChat(chatId)
    }

    fun getProxyManager(): ProxyManager = proxyManager
}
