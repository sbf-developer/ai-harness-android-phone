@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.pocketharness

import android.app.Application
import dev.pocketharness.data.*
import dev.pocketharness.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HarnessViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun vm(store: FakeStore, chat: FakeChat) = HarnessViewModel(Application(), store, chat, GitHubClient())

    @Test fun ordinaryChatWorksWithoutGithubAndPersistsCompletedTurn() = runTest {
        val store = FakeStore(); val chat = FakeChat(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.send("Hello"); advanceUntilIdle()
        assertEquals("", vm.state.value.settings.githubToken)
        assertEquals(1, chat.requests.size)
        val conversation = vm.state.value.active!!
        assertEquals(listOf("user", "assistant"), conversation.messages.map { it.role })
        assertEquals("complete", conversation.messages.last().status)
        assertEquals(conversation, store.conversations.single())
        assertNull(vm.state.value.error)
    }
    @Test fun missingKeyKeepsConversationEmptyAndMakesNoRequest() = runTest {
        val store = FakeStore().apply { settings = Settings() }; val chat = FakeChat(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.send("Hello"); advanceUntilIdle()
        assertTrue(vm.state.value.conversations.isEmpty()); assertTrue(chat.requests.isEmpty())
        assertTrue(vm.state.value.error!!.contains("API key"))
    }
    @Test fun retryUsesOriginalPinnedFilesAndDoesNotDuplicateUserMessage() = runTest {
        val user = Message(role = "user", content = "Explain this", files = listOf(fixtureFile()))
        val failed = Message(role = "assistant", content = "partial", status = "error")
        val store = FakeStore().apply { conversations = listOf(Conversation(messages = listOf(user, failed))) }
        val chat = FakeChat(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.clearContext(); vm.retryLastReply(); advanceUntilIdle()
        assertEquals(2, vm.state.value.active!!.messages.size)
        assertEquals(user, vm.state.value.active!!.messages.first())
        assertTrue(chat.requests.single().last().content.contains("base-sha"))
        assertEquals("complete", vm.state.value.active!!.messages.last().status)
        vm.retryLastReply(); advanceUntilIdle()
        assertEquals("Completed replies must not be retried", 1, chat.requests.size)
    }
    @Test fun cancellationDuringInitialSaveDoesNotBecomeStorageErrorOrStuckStream() = runTest {
        val store = FakeStore().apply { onSave = { if (saveCalls == 1) awaitCancellation() } }
        val chat = FakeChat(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.send("Hello"); runCurrent(); vm.cancel(); advanceUntilIdle()
        assertNull(vm.state.value.busy); assertNull(vm.state.value.error)
        assertEquals("cancelled", vm.state.value.active!!.messages.last().status)
        assertEquals("cancelled", store.conversations.single().messages.last().status)
        assertTrue(chat.requests.isEmpty())
    }
    @Test fun initialSaveFailureMarksReplyFailedAndCanBeRetried() = runTest {
        val store = FakeStore().apply { onSave = { if (saveCalls == 1) throw IOException("disk unavailable") } }
        val chat = FakeChat(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.send("Hello"); advanceUntilIdle()
        assertEquals("error", vm.state.value.active!!.messages.last().status)
        assertNull(vm.state.value.busy); assertTrue(vm.state.value.error!!.contains("save"))
        assertTrue(chat.requests.isEmpty())
        vm.retryLastReply(); advanceUntilIdle()
        assertEquals(2, vm.state.value.active!!.messages.size)
        assertEquals("complete", vm.state.value.active!!.messages.last().status)
    }
    @Test fun cancellingStreamKeepsPartialTextAndFinalCheckpoint() = runTest {
        val chat = FakeChat().apply { response = { _, delta -> delta("partial text"); awaitCancellation() } }
        val store = FakeStore(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.send("Hello"); runCurrent(); vm.cancel(); advanceUntilIdle()
        val reply = store.conversations.single().messages.last()
        assertEquals("partial text", reply.content); assertEquals("cancelled", reply.status)
        assertNull(vm.state.value.error)
    }
    @Test fun interruptedConversationCanBeRetriedAndStorageErrorsNeverOverwriteData() = runTest {
        val store = FakeStore().apply {
            conversations = listOf(Conversation(messages = listOf(Message(role = "user", content = "Hello"), Message(role = "assistant", content = "partial", status = "streaming"))))
            failLoad = true
        }
        val chat = FakeChat(); val vm = vm(store, chat)
        advanceUntilIdle(); vm.send("Ignored while storage is locked"); advanceUntilIdle()
        assertTrue(vm.state.value.startupError); assertEquals(0, store.saveCalls)
        store.failLoad = false; vm.initialize(); advanceUntilIdle()
        assertEquals("interrupted", vm.state.value.active!!.messages.last().status)
        vm.retryLastReply(); advanceUntilIdle()
        assertEquals("complete", store.conversations.single().messages.last().status)
    }
}

private class FakeStore : LocalStore {
    var settings = Settings(apiKey = "test-only-key")
    var conversations = emptyList<Conversation>()
    var saveCalls = 0
    var failLoad = false
    var onSave: suspend () -> Unit = {}
    override suspend fun loadSettings(): Settings { if (failLoad) throw IOException("locked"); return settings }
    override suspend fun saveSettings(settings: Settings) { this.settings = settings }
    override suspend fun loadConversations() = conversations
    override suspend fun saveConversations(items: List<Conversation>) { saveCalls++; onSave(); conversations = items }
}
private class FakeChat : ChatService {
    val requests = mutableListOf<List<AiMessage>>()
    var response: suspend (List<AiMessage>, suspend (String) -> Unit) -> Unit = { _, delta -> delta("Answer") }
    override suspend fun stream(settings: Settings, messages: List<AiMessage>, onDelta: suspend (String) -> Unit) {
        requests += messages
        response(messages, onDelta)
    }
}
