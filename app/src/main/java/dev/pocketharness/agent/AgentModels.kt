package dev.pocketharness.agent

import dev.pocketharness.data.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val TERMUX_HOME = "/data/data/com.termux/files/home"
const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
const val MAX_TASK_STEPS = 24
const val MAX_TASK_TOOLS = 48
const val MAX_TOOL_TEXT = 16000
const val MAX_AGENT_CONTEXT = 120000

@Serializable enum class Autonomy { READ, WORKSPACE, FULL }
@Serializable data class ToolCall(val id: String, val name: String, val arguments: String)
@Serializable data class AgentMessage(val role: String, val content: String = "", val calls: List<ToolCall> = emptyList(), val toolCallId: String? = null)
data class AgentReply(val content: String, val calls: List<ToolCall>, val finishReason: String)

@Serializable data class ToolResult(
    val stdout: String = "", val stderr: String = "", val exitCode: Int? = null,
    val cwd: String = "", val timedOut: Boolean = false, val truncated: Boolean = false,
    val redacted: Boolean = false, val data: JsonObject? = null, val error: String? = null,
) {
    val ok get() = error == null && !timedOut && (exitCode == null || exitCode == 0)
}
@Serializable data class ToolEvent(
    val id: String = newId(), val callId: String = "", val name: String, val arguments: String,
    val status: String = "queued", val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null, val result: ToolResult? = null,
)
@Serializable data class PendingCommand(
    val id: String, val taskId: String, val eventId: String, val nonce: String,
    val workspace: String, val operation: String, val startedAt: Long = System.currentTimeMillis(),
    val state: String = "dispatched", val acknowledged: Boolean = false,
    val mayWrite: Boolean = false,
)
@Serializable data class AgentTask(
    val id: String = newId(), val question: String, val workspace: String, val autonomy: Autonomy,
    val kind: String = "agent", val status: String = "prepared", val activity: String = "Ready",
    val startedAt: Long = System.currentTimeMillis(), val finishedAt: Long? = null,
    val messages: List<AgentMessage> = emptyList(), val events: List<ToolEvent> = emptyList(),
    val answer: String = "", val error: String? = null, val continuation: String = "",
    val manualCall: ToolCall? = null,
    val authorizations: List<String> = emptyList(),
) { val active get() = status in setOf("prepared", "running", "approval", "stopping") }
@Serializable data class AgentJournal(
    val workspace: String = "$TERMUX_HOME/projects", val tasks: List<AgentTask> = emptyList(),
    val pending: List<PendingCommand> = emptyList(),
)
data class ToolApproval(val id: String = newId(), val taskId: String, val call: ToolCall, val title: String, val scope: String)
data class ApprovalDecision(val allow: Boolean, val forTask: Boolean = false)
data class AgentState(
    val loading: Boolean = true, val journal: AgentJournal = AgentJournal(), val approval: ToolApproval? = null,
    val notice: String? = null, val error: String? = null, val diagnostics: ToolResult? = null,
    val file: ToolResult? = null,
) {
    val task get() = journal.tasks.firstOrNull()
    val active get() = task?.active == true
    val unresolved get() = journal.pending.any { it.mayWrite && it.state in setOf("dispatched", "unknown", "cancel_requested") && !it.acknowledged }
}

interface AgentStore { suspend fun loadAgent(): AgentJournal; suspend fun saveAgent(journal: AgentJournal) }
interface AgentProvider { suspend fun reply(settings: dev.pocketharness.data.Settings, messages: List<AgentMessage>, tools: kotlinx.serialization.json.JsonArray, onDelta: suspend (String) -> Unit): AgentReply }
interface AgentTools { suspend fun execute(call: ToolCall): ToolResult }
