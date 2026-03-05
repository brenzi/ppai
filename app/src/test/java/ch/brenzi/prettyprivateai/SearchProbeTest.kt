package ch.brenzi.prettyprivateai

import ch.brenzi.prettyprivateai.data.model.Chat
import ch.brenzi.prettyprivateai.data.model.Message
import ch.brenzi.prettyprivateai.data.model.MessageRole
import ch.brenzi.prettyprivateai.data.repository.ChatRepository
import ch.brenzi.prettyprivateai.tts.TtsManager
import ch.brenzi.prettyprivateai.tts.TtsModelState
import ch.brenzi.prettyprivateai.ui.chat.ChatViewModel
import ch.brenzi.prettyprivateai.whisper.WhisperManager
import ch.brenzi.prettyprivateai.whisper.WhisperModelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchProbeTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Regex tests ---

    @Test
    fun `regex matches clean search marker`() {
        val match = ChatViewModel.SEARCH_MARKER_REGEX.find("""[SEARCH: "today's news"]""")
        assertNotNull(match)
        assertEquals("today's news", match!!.groupValues[1])
    }

    @Test
    fun `regex matches marker with surrounding text`() {
        val content = "I need to search for this.\n[SEARCH: \"today's headlines\"]\nSearching now."
        val match = ChatViewModel.SEARCH_MARKER_REGEX.find(content)
        assertNotNull(match)
        assertEquals("today's headlines", match!!.groupValues[1])
    }

    @Test
    fun `regex does not match normal response`() {
        assertNull(ChatViewModel.SEARCH_MARKER_REGEX.find(
            "I don't have access to real-time information. My training data has a cutoff date."
        ))
    }

    @Test
    fun `regex does not match partial marker`() {
        assertNull(ChatViewModel.SEARCH_MARKER_REGEX.find("[SEARCH: query]")) // missing quotes
        assertNull(ChatViewModel.SEARCH_MARKER_REGEX.find("SEARCH: \"query\"")) // missing brackets
    }

    @Test
    fun `regex matches marker with extra whitespace`() {
        val match = ChatViewModel.SEARCH_MARKER_REGEX.find("""  [SEARCH:  "spaced query"  ]  """)
        assertNotNull(match)
        assertEquals("spaced query", match!!.groupValues[1])
    }

    // --- ViewModel: probe instruction assembly ---

    @Test
    fun `first message sends probe in system prompt`() {
        val repo = createMockRepo(modelResponse = "Normal answer.")
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("What are today's headlines?")
        vm.sendMessage()

        coVerify {
            repo.streamChatCompletion(
                eq("openai/gpt-oss-120b"),
                match { it.size == 1 && it[0].role == MessageRole.USER },
                match { it != null && it.contains("web search tool") && it.contains("[SEARCH:") },
                any(), any(), any(),
            )
        }
    }

    @Test
    fun `system prompt still contains base prompt when probe appended`() {
        val repo = createMockRepo(modelResponse = "Answer.")
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("Test")
        vm.sendMessage()

        coVerify {
            repo.streamChatCompletion(
                any(), any(),
                match { it != null && it.contains("Privatemode AI") && it.contains("web search tool") },
                any(), any(), any(),
            )
        }
    }

    @Test
    fun `system prompt includes current date and time`() {
        val repo = createMockRepo(modelResponse = "Answer.")
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("Test")
        vm.sendMessage()

        coVerify {
            repo.streamChatCompletion(
                any(), any(),
                match { it != null && it.startsWith("Current date and time:") },
                any(), any(), any(),
            )
        }
    }

    // --- ViewModel: marker detection and dialog ---

    @Test
    fun `search marker triggers pending approval in ASK mode`() {
        val repo = createMockRepo(
            modelResponse = """[SEARCH: "latest world news"]""",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("What's happening in the world?")
        vm.sendMessage()

        val pending = vm.pendingSearchApproval.value
        assertNotNull("Pending approval should be set", pending)
        assertEquals("latest world news", pending!!.query)
        assertTrue("Should still be generating while dialog shown", vm.isGenerating.value)
    }

    @Test
    fun `marker with surrounding text still triggers approval`() {
        val repo = createMockRepo(
            modelResponse = "Let me look that up.\n[SEARCH: \"current headlines\"]\nSearching...",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("Today's headlines?")
        vm.sendMessage()

        val pending = vm.pendingSearchApproval.value
        assertNotNull("Should detect marker even with surrounding text", pending)
        assertEquals("current headlines", pending!!.query)
    }

    @Test
    fun `normal response does not trigger search dialog`() {
        val repo = createMockRepo(modelResponse = "2 + 2 = 4.")
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("What is 2+2?")
        vm.sendMessage()

        assertNull("No approval should be pending", vm.pendingSearchApproval.value)
        assertFalse("Should not be generating", vm.isGenerating.value)
    }

    @Test
    fun `model refusal triggers search dialog via fallback`() {
        val repo = createMockRepo(
            modelResponse = "I don't have access to real-time information. My training data has a cutoff.",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("What are today's headlines?")
        vm.sendMessage()

        val pending = vm.pendingSearchApproval.value
        assertNotNull("Refusal should trigger search fallback", pending)
        assertEquals("What are today's headlines?", pending!!.query)
    }

    @Test
    fun `non-refusal normal response does not trigger fallback`() {
        val repo = createMockRepo(modelResponse = "2 + 2 = 4.")
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("What is 2+2?")
        vm.sendMessage()

        assertNull("Normal answer should not trigger fallback", vm.pendingSearchApproval.value)
    }

    @Test
    fun `refusal fallback clears assistant message`() {
        val repo = createMockRepo(
            modelResponse = "I cannot access real-time data or browse the internet.",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("Latest news")
        vm.sendMessage()

        assertNotNull(vm.pendingSearchApproval.value)
        coVerify { repo.updateMessage("test-chat-1", "assistant-msg-1", "") }
    }

    @Test
    fun `marker clears assistant message content`() {
        val repo = createMockRepo(
            modelResponse = """[SEARCH: "test query"]""",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("Search test")
        vm.sendMessage()

        // After marker detection, assistant message should be cleared
        coVerify { repo.updateMessage("test-chat-1", "assistant-msg-1", "") }
    }

    // --- ViewModel: policy routing ---

    @Test
    fun `neverSearchThisChat skips probe on subsequent messages`() {
        val repo = createMockRepo(
            modelResponse = """[SEARCH: "query"]""",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        // First message: triggers approval
        vm.setMessageText("First question")
        vm.sendMessage()
        assertNotNull(vm.pendingSearchApproval.value)

        // User picks "never"
        vm.neverSearchThisChat()

        // Reset mock for second call — need a fresh response
        coEvery {
            repo.streamChatCompletion(any(), any(), any(), any(), any(), any())
        } returns flow { emit("Direct answer.") }

        // Second message: should NOT have probe instruction
        vm.setMessageText("Second question")
        vm.sendMessage()

        // Verify the last call did NOT include probe
        coVerify {
            repo.streamChatCompletion(
                any(), any(),
                match { it == null || !it.contains("web search tool") },
                any(), any(), any(),
            )
        }
    }

    @Test
    fun `stopGeneration while dialog shown clears pending state`() {
        val repo = createMockRepo(
            modelResponse = """[SEARCH: "query"]""",
        )
        val vm = ChatViewModel(repo, createMockWhisperManager(), createMockTtsManager())

        vm.setMessageText("Test")
        vm.sendMessage()
        assertNotNull(vm.pendingSearchApproval.value)

        vm.stopGeneration()

        assertNull("Pending should be cleared", vm.pendingSearchApproval.value)
        assertFalse("Should not be generating", vm.isGenerating.value)
    }

    // --- Helper ---

    private fun createMockWhisperManager(): WhisperManager {
        return mockk {
            every { modelState } returns MutableStateFlow(WhisperModelState.NotDownloaded)
            every { isReady() } returns false
        }
    }

    private fun createMockTtsManager(): TtsManager {
        return mockk {
            every { modelState } returns MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded)
            every { isReady() } returns false
        }
    }

    private fun createMockRepo(
        modelId: String = "openai/gpt-oss-120b",
        modelResponse: String = "Hello",
    ): ChatRepository {
        return mockk {
            every { chats } returns MutableStateFlow(emptyList<Chat>())
            every { currentChatId } returns MutableStateFlow<String?>(null)
            every { modelsLoaded } returns MutableStateFlow(true)
            every { availableModels } returns MutableStateFlow(emptyList())
            every { selectedModel } returns flowOf(modelId)
            every { extendedThinking } returns flowOf(false)
            every { whisperLanguage } returns flowOf("auto")

            coEvery { createChat() } returns "test-chat-1"
            every { setCurrentChatId(any()) } just Runs
            coEvery { addMessage(any(), eq(MessageRole.USER), any(), any()) } returns "user-msg-1"
            coEvery { addMessage(any(), eq(MessageRole.ASSISTANT), any(), any()) } returns "assistant-msg-1"
            every { getChat("test-chat-1") } returns Chat(
                id = "test-chat-1",
                messages = listOf(
                    Message(id = "user-msg-1", role = MessageRole.USER, content = "test"),
                    Message(id = "assistant-msg-1", role = MessageRole.ASSISTANT, content = ""),
                ),
            )
            coEvery { updateMessage(any(), any(), any()) } just Runs
            every { setStreaming(any(), any()) } just Runs
            coEvery { saveAfterStreaming() } just Runs
            coEvery {
                streamChatCompletion(any(), any(), any(), any(), any(), any())
            } returns flow { emit(modelResponse) }
        }
    }
}
