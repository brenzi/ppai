package ch.brenzi.prettyprivateai.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "privatemode_preferences"
)

class PreferencesManager(private val context: Context) {

    companion object {
        private val API_KEY = stringPreferencesKey("api_key")
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val EXTENDED_THINKING = booleanPreferencesKey("extended_thinking")
        private val WEB_SEARCH = booleanPreferencesKey("web_search")
        private val STT_ENABLED = booleanPreferencesKey("stt_enabled")
        private val STT_PROMPT_SHOWN = booleanPreferencesKey("stt_prompt_shown")
        private val STT_MODEL_SIZE = stringPreferencesKey("stt_model_size")
        private val WHISPER_LANGUAGE = stringPreferencesKey("whisper_language")

        const val DEFAULT_SERVER_URL = "https://api.privatemode.ai"
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[API_KEY]
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val selectedModel: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_MODEL]
    }

    val extendedThinking: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[EXTENDED_THINKING] ?: false
    }

    val webSearch: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WEB_SEARCH] ?: false
    }

    suspend fun getApiKey(): String? {
        return context.dataStore.data.first()[API_KEY]
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.data.first()
        context.dataStore.edit { preferences ->
            preferences[API_KEY] = key
        }
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { preferences ->
            preferences.remove(API_KEY)
        }
    }

    suspend fun getServerUrl(): String {
        return context.dataStore.data.first()[SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL] = url
        }
    }

    suspend fun setSelectedModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_MODEL] = modelId
        }
    }

    suspend fun setExtendedThinking(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[EXTENDED_THINKING] = enabled
        }
    }

    suspend fun setWebSearch(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEB_SEARCH] = enabled
        }
    }

    val sttEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STT_ENABLED] ?: false
    }

    val sttPromptShown: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STT_PROMPT_SHOWN] ?: false
    }

    suspend fun setSttEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STT_ENABLED] = enabled
        }
    }

    suspend fun setSttPromptShown(shown: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STT_PROMPT_SHOWN] = shown
        }
    }

    val sttModelSize: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[STT_MODEL_SIZE] ?: "SMALL"
    }

    suspend fun setSttModelSize(size: String) {
        context.dataStore.edit { preferences ->
            preferences[STT_MODEL_SIZE] = size
        }
    }

    val whisperLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[WHISPER_LANGUAGE] ?: "auto"
    }

    suspend fun setWhisperLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[WHISPER_LANGUAGE] = language
        }
    }
}
