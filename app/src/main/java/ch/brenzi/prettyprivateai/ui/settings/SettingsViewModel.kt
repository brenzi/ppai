package ch.brenzi.prettyprivateai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ch.brenzi.prettyprivateai.data.repository.ChatRepository
import ch.brenzi.prettyprivateai.tts.TtsManager
import ch.brenzi.prettyprivateai.tts.TtsModelState
import ch.brenzi.prettyprivateai.tts.TtsVoice
import ch.brenzi.prettyprivateai.whisper.WhisperManager
import ch.brenzi.prettyprivateai.whisper.WhisperModelSize
import ch.brenzi.prettyprivateai.whisper.WhisperModelState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: ChatRepository,
    private val whisperManager: WhisperManager,
    private val ttsManager: TtsManager,
) : ViewModel() {

    val apiKey: StateFlow<String?> = repository.apiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val serverUrl: StateFlow<String> = repository.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val sttEnabled: StateFlow<Boolean> = repository.preferences.sttEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val whisperModelState: StateFlow<WhisperModelState> = whisperManager.modelState

    val sttModelSize: StateFlow<WhisperModelSize> = repository.preferences.sttModelSize
        .map { WhisperModelSize.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), whisperManager.modelSize)

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            repository.setApiKey(key)
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            repository.setServerUrl(url)
        }
    }

    fun clearAllChats() {
        viewModelScope.launch {
            repository.clearAllChats()
        }
    }

    fun setSttEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferences.setSttEnabled(enabled)
            if (!enabled) {
                whisperManager.deleteModel()
            }
            // When enabling, MainActivity's LaunchedEffect handles init/download
        }
    }

    fun setSttModelSize(size: WhisperModelSize) {
        viewModelScope.launch {
            repository.preferences.setSttModelSize(size.name)
            whisperManager.setModelSize(size)
            whisperManager.initialize()
            if (whisperManager.modelState.value is WhisperModelState.NotDownloaded) {
                whisperManager.downloadModel()
            }
        }
    }

    fun downloadWhisperModel() {
        viewModelScope.launch {
            whisperManager.downloadModel()
        }
    }

    // TTS

    val ttsEnabled: StateFlow<Boolean> = repository.preferences.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val ttsModelState: StateFlow<TtsModelState> = ttsManager.modelState

    val ttsVoice: StateFlow<TtsVoice> = repository.preferences.ttsVoice
        .map { TtsVoice.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ttsManager.voice)

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferences.setTtsEnabled(enabled)
            if (!enabled) {
                ttsManager.deleteVoice()
            }
        }
    }

    fun setTtsVoice(voice: TtsVoice) {
        viewModelScope.launch {
            repository.preferences.setTtsVoice(voice.name)
            ttsManager.setVoice(voice)
            ttsManager.initialize()
            if (ttsManager.modelState.value is TtsModelState.NotDownloaded) {
                ttsManager.downloadVoice()
            }
        }
    }

    fun downloadTtsModel() {
        viewModelScope.launch {
            ttsManager.downloadVoice()
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val whisperManager: WhisperManager,
        private val ttsManager: TtsManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(repository, whisperManager, ttsManager) as T
        }
    }
}
