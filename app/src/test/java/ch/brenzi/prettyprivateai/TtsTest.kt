package ch.brenzi.prettyprivateai

import ch.brenzi.prettyprivateai.data.model.Chat
import ch.brenzi.prettyprivateai.data.model.Message
import ch.brenzi.prettyprivateai.data.model.MessageRole
import ch.brenzi.prettyprivateai.data.repository.ChatRepository
import ch.brenzi.prettyprivateai.tts.AudioPlayer
import ch.brenzi.prettyprivateai.tts.TtsManager
import ch.brenzi.prettyprivateai.tts.TtsModelState
import ch.brenzi.prettyprivateai.ui.chat.ChatViewModel
import ch.brenzi.prettyprivateai.whisper.WhisperManager
import ch.brenzi.prettyprivateai.whisper.WhisperModelState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // TtsModelState tests
    // -----------------------------------------------------------------------

    @Test
    fun `TtsModelState NotDownloaded is initial state`() {
        val state: TtsModelState = TtsModelState.NotDownloaded
        assertTrue(state is TtsModelState.NotDownloaded)
    }

    @Test
    fun `TtsModelState Downloading holds progress`() {
        val state = TtsModelState.Downloading(0.42f)
        assertEquals(0.42f, state.progress)
    }

    @Test
    fun `TtsModelState Ready is distinct`() {
        assertTrue(TtsModelState.Ready is TtsModelState.Ready)
    }

    @Test
    fun `TtsModelState Error holds message`() {
        val state = TtsModelState.Error("network error")
        assertEquals("network error", state.message)
    }

    // -----------------------------------------------------------------------
    // TtsManager mock state tests
    // -----------------------------------------------------------------------

    @Test
    fun `tts model state starts as NotDownloaded`() {
        val ttsManager = createMockTtsManager(ready = false)
        assertTrue(ttsManager.modelState.value is TtsModelState.NotDownloaded)
    }

    @Test
    fun `isReady returns false when model not downloaded`() {
        val ttsManager = createMockTtsManager(ready = false)
        assertFalse(ttsManager.isReady())
    }

    @Test
    fun `isReady returns true when model is Ready`() {
        val ttsManager = createMockTtsManager(ready = true)
        assertTrue(ttsManager.isReady())
    }

    // -----------------------------------------------------------------------
    // ChatViewModel + TTS interaction
    // -----------------------------------------------------------------------

    @Test
    fun `speakMessage is no-op when TTS not ready`() {
        val ttsManager = createMockTtsManager(ready = false)
        val vm = ChatViewModel(createMockRepo(), createMockWhisperManager(), ttsManager)

        vm.speakMessage("msg-1", "Hello world")

        assertFalse(vm.isSpeaking.value)
        assertNull(vm.speakingMessageId.value)
    }

    @Test
    fun `speakMessage is no-op when already speaking`() {
        val ttsManager = createMockTtsManager(ready = true)
        val vm = ChatViewModel(createMockRepo(), createMockWhisperManager(), ttsManager)

        // Start speaking (will block on synthesis mock returning null)
        vm.speakMessage("msg-1", "First")
        assertTrue(vm.isSpeaking.value)

        // Second call should be ignored
        vm.speakMessage("msg-2", "Second")
        assertEquals("msg-1", vm.speakingMessageId.value)
    }

    @Test
    fun `stopSpeaking resets state when not speaking`() {
        val ttsManager = createMockTtsManager(ready = true)
        val vm = ChatViewModel(createMockRepo(), createMockWhisperManager(), ttsManager)

        // Should not throw
        vm.stopSpeaking()
        assertFalse(vm.isSpeaking.value)
        assertNull(vm.speakingMessageId.value)
    }

    @Test
    fun `ViewModel exposes tts model state from manager`() {
        val stateFlow = MutableStateFlow<TtsModelState>(TtsModelState.Downloading(0.75f))
        val ttsManager = mockk<TtsManager> {
            every { modelState } returns stateFlow
            every { isReady() } returns false
        }
        val vm = ChatViewModel(createMockRepo(), createMockWhisperManager(), ttsManager)

        val state = vm.ttsModelState.value
        assertTrue(state is TtsModelState.Downloading)
        assertEquals(0.75f, (state as TtsModelState.Downloading).progress)
    }

    // -----------------------------------------------------------------------
    // AudioPlayer tests
    // -----------------------------------------------------------------------

    @Test
    fun `AudioPlayer isPlaying returns false initially`() {
        val player = AudioPlayer()
        assertFalse(player.isPlaying())
    }

    @Test
    fun `AudioPlayer stop when not playing is safe`() {
        val player = AudioPlayer()
        // Should not throw
        player.stop()
        assertFalse(player.isPlaying())
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun createMockTtsManager(ready: Boolean): TtsManager {
        val state = if (ready) TtsModelState.Ready else TtsModelState.NotDownloaded
        return mockk {
            every { modelState } returns MutableStateFlow(state)
            every { isReady() } returns ready
            every { synthesize(any(), any(), any()) } returns null
        }
    }

    private fun createMockWhisperManager(): WhisperManager {
        return mockk {
            every { modelState } returns MutableStateFlow<WhisperModelState>(WhisperModelState.NotDownloaded)
            every { isReady() } returns false
            every { transcribe(any(), any()) } returns ""
            every { abortTranscription() } just Runs
        }
    }

    private fun createMockRepo(): ChatRepository {
        return mockk {
            every { chats } returns MutableStateFlow(emptyList<Chat>())
            every { currentChatId } returns MutableStateFlow<String?>(null)
            every { modelsLoaded } returns MutableStateFlow(true)
            every { availableModels } returns MutableStateFlow(emptyList())
            every { selectedModel } returns flowOf("openai/gpt-oss-120b")
            every { extendedThinking } returns flowOf(false)
            every { whisperLanguage } returns flowOf("auto")

            coEvery { createChat() } returns "test-chat-1"
            every { setCurrentChatId(any()) } just Runs
            coEvery { addMessage(any(), any(), any(), any()) } returns "msg-1"
            every { getChat(any()) } returns Chat(
                id = "test-chat-1",
                messages = listOf(
                    Message(id = "msg-1", role = MessageRole.USER, content = "test"),
                ),
            )
            coEvery { updateMessage(any(), any(), any()) } just Runs
            every { setStreaming(any(), any()) } just Runs
            coEvery { saveAfterStreaming() } just Runs
            coEvery {
                streamChatCompletion(any(), any(), any(), any(), any(), any())
            } returns flow { emit("response") }
        }
    }
}
