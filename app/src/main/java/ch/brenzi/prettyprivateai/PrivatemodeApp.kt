package ch.brenzi.prettyprivateai

import android.app.Application
import ch.brenzi.prettyprivateai.data.local.ChatStorage
import ch.brenzi.prettyprivateai.data.local.PreferencesManager
import ch.brenzi.prettyprivateai.data.repository.ChatRepository
import ch.brenzi.prettyprivateai.proxy.ProxyManager
import ch.brenzi.prettyprivateai.whisper.WhisperManager

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

    override fun onCreate() {
        super.onCreate()

        preferences = PreferencesManager(this)
        chatStorage = ChatStorage(this)
        proxyManager = ProxyManager(this)
        repository = ChatRepository(chatStorage, preferences, proxyManager)
        whisperManager = WhisperManager(this)
    }
}
