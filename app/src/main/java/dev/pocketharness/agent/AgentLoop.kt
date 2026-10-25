package dev.pocketharness.agent

import dev.pocketharness.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString

class AgentLoop(private val provider: AgentProvider, private val tools: AgentTools) {
    suspend fun run(
        settings: Settings, initial: List<AgentMessage>,
        save: suspend (List<AgentMessage>) -> Unit,
        delta: suspend (String) -> Unit,
    ): String {
        val messages = initial.toMutableList()
        var toolCount = 0
        val guard = SecretGuard(settings)
        repeat(MAX_TASK_STEPS) {
            currentCoroutineContext().ensureActive()
            if (messages.sumOf { it.content.length + it.calls.sumOf { call -> call.arguments.length } } > MAX_AGENT_CONTEXT)
                throw UserFacingException("Agent context limit reached. Continue from the checkpoint as a new task; nothing will be replayed automatically.")
            val previous = messages.filter { it.role == "assistant" }.map { it.content }.filter { it.isNotBlank() }.joinToString("\n\n")
            val partial = StringBuilder()
            val reply = provider.reply(settings, messages.toList(), ToolCatalog.tools(settings.braveSearchKey.isNotBlank())) {
                partial.append(it)
                delta(guard.clean(listOf(previous, partial.toString()).filter(String::isNotEmpty).joinToString("\n\n")))
            }
            // Never persist or dispatch a provider-generated command containing an app credential.
            reply.calls.forEach { guard.requireSafe(it.arguments); guard.requireSafe(it.name); guard.requireSafe(it.id); AgentPolicy.arguments(it) }
            val cleanContent = guard.clean(reply.content)
            messages += AgentMessage("assistant", cleanContent, reply.calls)
            save(messages.toList()) // Persist complete calls BEFORE any side effects.
            if (reply.calls.isEmpty()) return cleanContent
            for (call in reply.calls) {
                if (++toolCount > MAX_TASK_TOOLS) throw UserFacingException("Tool-call limit reached. Inspect the checkpoint and continue as a new task.")
                currentCoroutineContext().ensureActive()
                val result = try { guard.result(tools.execute(call)) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { ToolResult(error = guard.clean(dev.pocketharness.network.friendlyError(error))) }
                messages += AgentMessage("tool", AppJson.encodeToString(result), toolCallId = call.id)
                save(messages.toList())
            }
        }
        throw UserFacingException("Agent step limit reached. Continue from the checkpoint as a new task.")
    }
}
