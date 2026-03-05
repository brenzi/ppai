package ch.brenzi.prettyprivateai

import android.app.Application
import ch.brenzi.prettyprivateai.data.local.ChatStorage
import ch.brenzi.prettyprivateai.data.local.PreferencesManager
import ch.brenzi.prettyprivateai.data.repository.ChatRepository
import ch.brenzi.prettyprivateai.proxy.ProxyManager
import ch.brenzi.prettyprivateai.tts.TtsManager
import ch.brenzi.prettyprivateai.whisper.WhisperManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

sealed class SharedContent {
    data class Text(val text: String) : SharedContent()
    data class FileUri(val uri: Uri, val mimeType: String?) : SharedContent()
}

class PrivatemodeApp : Application() {

    lateinit var preferences: PreferencesManager
        private set
    lateinit var chatStorage: ChatStorage
        private set
    lateinit var proxyManager: ProxyManager
        private set
    lateinit var repository: ChatRepository
        private set
    lateinit var whisperManager: WhisperManager
        private set
    lateinit var ttsManager: TtsManager
        private set

    val pendingShareContent = MutableStateFlow<SharedContent?>(null)

    override fun onCreate() {
        super.onCreate()

        preferences = PreferencesManager(this)
        chatStorage = ChatStorage(this)
        proxyManager = ProxyManager(this)
        repository = ChatRepository(chatStorage, preferences, proxyManager)
        whisperManager = WhisperManager(this)
        ttsManager = TtsManager(this)
    }
}
