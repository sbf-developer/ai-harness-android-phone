package dev.pocketharness.agent

import dev.pocketharness.data.Settings
import dev.pocketharness.data.UserFacingException
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Base64

class SecretGuard(settings: Settings) {
    private val secrets = listOf(settings.apiKey, settings.githubToken, settings.braveSearchKey).filter { it.isNotBlank() }
        .flatMap { listOf(it, URLEncoder.encode(it, "UTF-8"), Base64.getEncoder().encodeToString(it.toByteArray()), JsonPrimitive(it).toString().drop(1).dropLast(1)) }.distinct().sortedByDescending { it.length }
    private val patterns = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}"),
        Regex("-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z ]+)?PRIVATE KEY-----|$)"),
        Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@"),
        Regex("(?i)(authorization[\\s\"':=]+(?:bearer|basic)\\s+)[A-Za-z0-9_./+=-]{8,}"),
    )
    fun clean(value: String): String {
        var clean = value
        secrets.forEach { clean = clean.replace(it, "[credential redacted]") }
        patterns.forEach { clean = it.replace(clean, "[credential redacted]") }
        return clean
    }
    fun requireSafe(value: String) {
        if (clean(value) != value) throw UserFacingException("This contains a saved or recognizable credential. Configure credentials privately in Termux; do not include them in agent instructions, commands, or files.")
    }
    fun result(result: ToolResult): ToolResult {
        // Inspect actual string values, including credentials containing JSON escape characters.
        val cleaned = result.copy(stdout = clean(result.stdout), stderr = clean(result.stderr), cwd = clean(result.cwd),
            error = result.error?.let(::clean), data = result.data?.let { redactJson(it).jsonObject })
        return if (cleaned == result) result else cleaned.copy(redacted = true)
    }
    private fun redactJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { clean(it.key) to redactJson(it.value) })
        is JsonArray -> JsonArray(value.map(::redactJson))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(clean(value.content)) else value
        else -> value
    }
}

object AgentPolicy {
    private val reads = setOf("list_files", "read_file", "search_files", "web_search", "fetch_url", "job_status")
    val names = reads + setOf("write_file", "edit_file", "terminal", "git")
    fun readOnly(call: ToolCall): Boolean = call.name in reads || (call.name == "git" && arguments(call).text("operation") in setOf("status", "diff", "remote"))
    fun arguments(call: ToolCall): JsonObject {
        if (call.arguments.length > 100000) throw UserFacingException("Tool arguments exceed 100 KB.")
        return try { dev.pocketharness.data.AppJson.parseToJsonElement(call.arguments).jsonObject }
        catch (_: Exception) { throw UserFacingException("The model supplied invalid tool arguments.") }
    }
    fun workspace(value: String): String {
        val path = value.trim().let { if (it == "~") TERMUX_HOME else if (it.startsWith("~/")) TERMUX_HOME + it.drop(1) else it }.trimEnd('/')
        if (!path.startsWith('/') || path.length !in 2..500 || path.any { it == '\u0000' || it == '\n' || it == '\r' })
            throw UserFacingException("Choose an absolute Termux workspace, such as ~/projects. The root directory is not a workspace.")
        return path
    }
    fun scope(call: ToolCall, workspace: String): String {
        val args = arguments(call)
        return when (call.name) {
            "git" -> "git:${args.text("operation")}:$workspace:${args.text("repo_path", ".")}:${args.text("remote", "origin")}:${args.text("branch")}:${args.text("url")}"
            "write_file", "edit_file" -> "write_file:$workspace"
            "terminal" -> "terminal:$workspace"
            else -> "${call.name}:$workspace"
        }
    }
    fun needsApproval(call: ToolCall, mode: Autonomy, granted: Set<String>, workspace: String): Boolean {
        if (call.name !in names) throw UserFacingException("Unsupported tool: ${call.name.take(80)}")
        if (call.name in reads) return false
        val args = arguments(call)
        if (call.name == "git" && args.text("operation") in setOf("status", "diff", "remote")) return false
        if (mode == Autonomy.FULL || "terminal:$workspace" in granted || scope(call, workspace) in granted) return false
        if (mode == Autonomy.WORKSPACE && (call.name in setOf("write_file", "edit_file") || (call.name == "git" && args.text("operation") in setOf("init", "commit")))) return false
        return true
    }
    fun label(call: ToolCall): String {
        val args = runCatching { arguments(call) }.getOrDefault(JsonObject(emptyMap()))
        return when (call.name) {
            "terminal" -> "Run terminal command"
            "git" -> "Git ${args.text("operation")} · ${args.text("repo_path", ".")}"
            "write_file", "edit_file" -> "Edit ${args.text("path")}"
            "read_file" -> "Read ${args.text("path")}"
            "list_files" -> "List ${args.text("path", ".")}"
            "search_files" -> "Search workspace"
            "fetch_url" -> "Read webpage"
            "web_search" -> "Search the web"
            "job_status" -> "Check previous command"
            else -> call.name
        }.take(180)
    }
}
fun JsonObject.text(key: String, default: String = "") = this[key]?.jsonPrimitive?.contentOrNull ?: default

object AgentRecovery {
    fun recover(journal: AgentJournal): AgentJournal = journal.copy(
        tasks = journal.tasks.map { task -> if (!task.active) task else task.copy(status = "interrupted", activity = "Interrupted; nothing was replayed", error = "Android stopped the process. Inspect unknown command outcomes before continuing.", events = task.events.map { if (it.status in setOf("queued", "running")) it.copy(status = "unknown") else it }) },
        pending = journal.pending.map { if (it.state in setOf("dispatched", "cancel_requested")) it.copy(state = "unknown") else it },
    )
    fun canAccept(pending: PendingCommand?, callbackId: String) = pending != null && pending.id == callbackId && pending.state in setOf("dispatched", "unknown", "cancel_requested")
}
