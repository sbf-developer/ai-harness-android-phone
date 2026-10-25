package dev.pocketharness

import dev.pocketharness.agent.*
import dev.pocketharness.data.*
import dev.pocketharness.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AgentTest {
    private fun objectOf(text: String) = AppJson.parseToJsonElement(text).jsonObject
    private fun call(name: String, args: String = "{}") = ToolCall("call-1", name, args)
    private fun fragment(index: Int, id: String? = null, name: String? = null, args: String = "") = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { putJsonArray("tool_calls") {
            add(buildJsonObject { put("index", index); id?.let { put("id", it) }; putJsonObject("function") { name?.let { put("name", it) }; put("arguments", args) } })
        } } }) }
    }
    private fun finish(reason: String) = objectOf("""{"choices":[{"delta":{},"finish_reason":"$reason"}]}""")

    @Test fun independentlyAssemblesInterleavedToolFragments() {
        val stream = ToolStreamAccumulator()
        stream.accept(fragment(1, "second", "read_", "{\"path\":"))
        stream.accept(fragment(0, "first", "list_files", "{}"))
        stream.accept(fragment(1, name = "file", args = "\"src/main.kt\"}"))
        stream.accept(finish("tool_calls"))
        val reply = stream.finish()
        assertEquals(listOf("first", "second"), reply.calls.map { it.id })
        assertEquals("read_file", reply.calls[1].name)
        assertEquals("src/main.kt", AgentPolicy.arguments(reply.calls[1]).text("path"))
    }
    @Test fun refusesUnfinishedMalformedOrInconsistentToolCalls() {
        for (reason in listOf(null, "length", "stop", "tool_calls")) {
            val stream = ToolStreamAccumulator()
            stream.accept(fragment(0, "id", "terminal", "{\"command\":\"partial"))
            if (reason != null) stream.accept(finish(reason))
            assertThrows(Exception::class.java) { stream.finish() }
        }
        val duplicate = ToolStreamAccumulator()
        duplicate.accept(fragment(0, "same", "list_files", "{}")); duplicate.accept(fragment(1, "same", "list_files", "{}"))
        duplicate.accept(finish("tool_calls")); assertThrows(UserFacingException::class.java) { duplicate.finish() }
    }
    @Test fun actualToolResultIsReturnedToModelBeforeNextRound() = runBlocking {
        val requests = mutableListOf<List<AgentMessage>>()
        val checkpoints = mutableListOf<List<AgentMessage>>()
        var executions = 0
        val provider = object : AgentProvider {
            override suspend fun reply(settings: Settings, messages: List<AgentMessage>, tools: JsonArray, onDelta: suspend (String) -> Unit): AgentReply {
                requests += messages
                return if (requests.size == 1) AgentReply("Inspecting", listOf(call("terminal", """{"command":"pwd"}""")), "tool_calls")
                else AgentReply("Confirmed the directory.", emptyList(), "stop")
            }
        }
        val tools = object : AgentTools { override suspend fun execute(call: ToolCall): ToolResult {
            assertTrue(checkpoints.last().last().calls.isNotEmpty()) // durable calls precede side effects
            executions++; return ToolResult(stdout = "/actual/project", exitCode = 0)
        } }
        val answer = AgentLoop(provider, tools).run(Settings(), listOf(AgentMessage("user", "inspect")), { checkpoints += it }, {})
        assertEquals(1, executions); assertEquals("Confirmed the directory.", answer)
        val result = requests[1].last()
        assertEquals("tool", result.role); assertEquals("call-1", result.toolCallId); assertTrue(result.content.contains("/actual/project"))
        val wire = agentWireMessage(result)
        assertEquals("call-1", wire.text("tool_call_id"))
        assertTrue(agentWireMessage(requests[1][1])["tool_calls"] != null)
    }
    @Test fun cancellationDoesNotTurnIntoToolSuccessOrAnotherRound() = runBlocking {
        var rounds = 0
        val provider = object : AgentProvider { override suspend fun reply(settings: Settings, messages: List<AgentMessage>, tools: JsonArray, onDelta: suspend (String) -> Unit): AgentReply {
            rounds++; return AgentReply("", listOf(call("list_files")), "tool_calls")
        } }
        val tools = object : AgentTools { override suspend fun execute(call: ToolCall): ToolResult = throw CancellationException("stop") }
        try { AgentLoop(provider, tools).run(Settings(), emptyList(), {}, {}); fail() } catch (_: CancellationException) { }
        assertEquals(1, rounds)
    }
    @Test fun boundedLoopStopsAndOversizedContextNeverReachesProvider() = runBlocking {
        var rounds = 0; var count = 0
        val provider = object : AgentProvider { override suspend fun reply(settings: Settings, messages: List<AgentMessage>, tools: JsonArray, onDelta: suspend (String) -> Unit): AgentReply {
            rounds++; return AgentReply("", listOf(call("list_files")), "tool_calls")
        } }
        val tools = object : AgentTools { override suspend fun execute(call: ToolCall): ToolResult { count++; return ToolResult(exitCode = 0) } }
        try { AgentLoop(provider, tools).run(Settings(), listOf(AgentMessage("user", "x".repeat(MAX_AGENT_CONTEXT + 1))), {}, {}); fail() } catch (_: UserFacingException) { }
        assertEquals(0, rounds)
        try { AgentLoop(provider, tools).run(Settings(), emptyList(), {}, {}); fail() } catch (_: UserFacingException) { }
        assertEquals(MAX_TASK_STEPS, rounds); assertEquals(MAX_TASK_STEPS, count)
    }
    @Test fun grantsExpireByTaskAndGitGrantBindsDestination() {
        val terminal = call("terminal", """{"command":"ls"}""")
        val write = call("write_file", """{"path":"a"}""")
        val push = call("git", """{"operation":"push","repo_path":"p","branch":"main","url":"https://example.com/repo"}""")
        assertTrue(AgentPolicy.needsApproval(terminal, Autonomy.WORKSPACE, emptySet(), "/p"))
        assertFalse(AgentPolicy.needsApproval(write, Autonomy.WORKSPACE, emptySet(), "/p"))
        assertTrue(AgentPolicy.needsApproval(write, Autonomy.READ, emptySet(), "/p"))
        assertFalse(AgentPolicy.needsApproval(push, Autonomy.FULL, emptySet(), "/p"))
        val grant = setOf(AgentPolicy.scope(push, "/p"))
        assertFalse(AgentPolicy.needsApproval(push, Autonomy.READ, grant, "/p"))
        assertTrue(AgentPolicy.needsApproval(push.copy(arguments = push.arguments.replace("main", "other")), Autonomy.READ, grant, "/p"))
        assertTrue(AgentPolicy.needsApproval(push, Autonomy.READ, grant, "/another"))
        assertFalse(AgentPolicy.needsApproval(push, Autonomy.READ, setOf("terminal:/p"), "/p"))
        assertTrue(AgentPolicy.needsApproval(terminal, Autonomy.READ, emptySet(), "/p"))
    }
    @Test fun credentialRedactionPreservesJsonAndCoversThreeKeysAndEncodings() {
        val settings = Settings(apiKey = "a-key-\"with-quotes", githubToken = "test-github-value", braveSearchKey = "test-brave-value")
        val guard = SecretGuard(settings)
        for (key in listOf(settings.apiKey, settings.githubToken, settings.braveSearchKey)) {
            assertThrows(UserFacingException::class.java) { guard.requireSafe("print $key") }
            assertFalse(guard.clean(Base64.getEncoder().encodeToString(key.toByteArray())).contains(Base64.getEncoder().encodeToString(key.toByteArray())))
        }
        val redacted = guard.result(ToolResult(data = buildJsonObject { put("content", settings.apiKey + "\nkeep"); put("sha", "abc") }))
        assertThrows(UserFacingException::class.java) { guard.requireSafe(buildJsonObject { put("command", "echo ${settings.apiKey}") }.toString()) }
        assertTrue(redacted.redacted); assertEquals("abc", redacted.data?.text("sha"))
        assertEquals("[credential redacted]\nkeep", redacted.data?.text("content"))
        assertFalse(guard.clean("ghp_" + "a".repeat(30)).contains("ghp_"))
    }
    @Test fun recoveryNeverQueuesAnInterruptedOperationAndAcceptsOnlyKnownOutstandingCallback() {
        val task = AgentTask(question = "work", workspace = "/p", autonomy = Autonomy.FULL, status = "running", events = listOf(ToolEvent(name = "terminal", arguments = "{}", status = "running")))
        val pending = PendingCommand("id", task.id, task.events[0].id, "nonce", "/p", "terminal", mayWrite = true)
        val recovered = AgentRecovery.recover(AgentJournal(tasks = listOf(task), pending = listOf(pending)))
        assertEquals("interrupted", recovered.tasks[0].status); assertFalse(recovered.tasks[0].active)
        assertEquals("unknown", recovered.tasks[0].events[0].status); assertEquals("unknown", recovered.pending[0].state)
        assertTrue(AgentState(journal = recovered).unresolved)
        assertTrue(AgentRecovery.canAccept(recovered.pending[0], "id"))
        assertFalse(AgentRecovery.canAccept(null, "id")); assertFalse(AgentRecovery.canAccept(pending, "different"))
        assertFalse(AgentRecovery.canAccept(pending.copy(state = "complete"), "id"))
        assertFalse(AgentState(journal = recovered.copy(pending = listOf(pending.copy(acknowledged = true)))).unresolved)
    }
    @Test fun resultDecoderRequiresMatchingEnvelopeAndKeepsExitTimeoutAndTruncation() {
        val good = TermuxResultDecoder.decode("id", """{"command_id":"id","stdout":"actual","stderr":"bad","exit_code":7,"timed_out":true,"truncated":true}""", "", 0, -1, "")
        assertEquals(7, good.exitCode); assertFalse(good.ok); assertTrue(good.timedOut); assertTrue(good.truncated)
        assertEquals("true", good.data?.text("confirmed"))
        assertFalse(TermuxResultDecoder.decode("other", """{"command_id":"id"}""", "", 0, -1, "").ok)
        assertFalse(TermuxResultDecoder.decode("id", "broken", "", 0, -1, "").ok)
        assertFalse(TermuxResultDecoder.decode("id", "", "", 0, 2, "disabled").ok)
        assertTrue(TermuxContract.supported("0.118.3")); assertFalse(TermuxContract.supported("0.108"))
    }
    @Test fun noSearchToolExistsWithoutSearchKeyAndOldSettingsRemainCompatible() {
        fun names(enabled: Boolean) = ToolCatalog.tools(enabled).map { it.jsonObject["function"]!!.jsonObject.text("name") }
        assertFalse("web_search" in names(false)); assertTrue("fetch_url" in names(false)); assertTrue("web_search" in names(true))
        assertEquals("", AppJson.decodeFromString<Settings>("""{"apiKey":"old-key"}""").braveSearchKey)
        assertEquals("$TERMUX_HOME/projects", AgentPolicy.workspace("~/projects/"))
        assertThrows(UserFacingException::class.java) { AgentPolicy.workspace("/") }
    }
}
