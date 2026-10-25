package dev.pocketharness.agent

import android.content.Context
import android.content.Intent
import dev.pocketharness.HarnessApplication
import dev.pocketharness.data.*
import dev.pocketharness.network.AgentProviderClient
import dev.pocketharness.network.friendlyError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/** Application-wide journal/dispatch owner; a foreground Service owns each active task's Job. */
class AgentCoordinator(private val context: Context, private val vault: Vault) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AgentState())
    val state = mutable.asStateFlow()
    val bridge = TermuxBridge(context)
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var loadFailed = false
    private var running: Job? = null
    private var preparing = false
    private var approvalWait: CompletableDeferred<ApprovalDecision>? = null
    private val waits = ConcurrentHashMap<String, CompletableDeferred<ToolResult>>()
    private val privateResults = ConcurrentHashMap.newKeySet<String>()
    private var settings = Settings()
    private var guard = SecretGuard(settings)
    private val grants = mutableSetOf<String>()
    private val web = WebResearch()

    init {
        scope.launch {
            try { mutable.update { it.copy(loading = false, journal = AgentRecovery.recover(vault.loadAgent())) } }
            catch (_: Exception) { loadFailed = true; mutable.update { it.copy(loading = false, error = "Encrypted agent history could not be opened. No task or command was restarted.") } }
            finally { loaded.complete(Unit) }
        }
    }
    private suspend fun ready() { loaded.await(); if (loadFailed) throw UserFacingException("Agent storage is unavailable. Basic chat remains separate; inspect Android app storage before resetting anything.") }
    private suspend fun journal(change: (AgentJournal) -> AgentJournal) {
        ready()
        mutex.withLock {
            val next = change(state.value.journal)
            vault.saveAgent(next) // Save-before-publish: dispatch cannot happen if its durable record fails.
            mutable.update { it.copy(journal = next) }
        }
    }
    private suspend fun task(id: String, change: (AgentTask) -> AgentTask) = journal { it.copy(tasks = it.tasks.map { item -> if (item.id == id) change(item) else item }) }
    fun clearNotice() { mutable.update { it.copy(error = null, notice = null) } }
    fun closeFile() { mutable.update { it.copy(file = null) } }

    fun start(question: String, workspace: String, mode: Autonomy, fullAccepted: Boolean, acknowledgeUnknown: Boolean, continueId: String? = null) {
        prepare(question, workspace, mode, fullAccepted, acknowledgeUnknown, continueId, null)
    }
    fun manual(call: ToolCall, workspace: String, acknowledgeUnknown: Boolean = false) {
        prepare(AgentPolicy.label(call), workspace, Autonomy.FULL, true, acknowledgeUnknown, null, call)
    }
    fun diagnostics(workspace: String) = manual(ToolCall(newId(), "_diagnostics", "{}"), workspace)
    private fun prepare(question: String, workspace: String, mode: Autonomy, fullAccepted: Boolean, acknowledgeUnknown: Boolean, continueId: String?, manual: ToolCall?) {
        scope.launch {
            if (preparing) { mutable.update { it.copy(error = "A task is already being prepared.") }; return@launch }
            preparing = true
            try {
                ready()
                if (state.value.active || running?.isActive == true) throw UserFacingException("Stop or finish the current task first.")
                val path = AgentPolicy.workspace(workspace)
                settings = vault.loadSettings(); guard = SecretGuard(settings)
                if (question.isBlank() || question.length > 32000) throw UserFacingException("Describe a task in 1–32000 characters.")
                guard.requireSafe(question); manual?.let { guard.requireSafe(it.arguments) }
                if (manual == null && settings.apiKey.isBlank()) throw UserFacingException("Add your AI provider key in Settings. GitHub and search keys are optional.")
                if (mode == Autonomy.FULL && !fullAccepted) throw UserFacingException("Explicitly acknowledge full terminal/destructive/network access for this task.")
                val readOnlyManual = manual?.name in setOf("_diagnostics", "read_file", "list_files", "search_files", "job_status")
                if (state.value.unresolved && !acknowledgeUnknown && !readOnlyManual)
                    throw UserFacingException("A prior command's outcome is unknown. Check Termux, then acknowledge it before starting another task. Commands will not be replayed.")
                if (manual == null && mode != Autonomy.READ && !bridge.setup().available) throw UserFacingException(bridge.setup().message)
                val continuation = continueId?.let { id -> state.value.journal.tasks.firstOrNull { it.id == id } }?.let { old ->
                    buildJsonObject {
                        put("previous_task", old.question.take(6000)); put("state", old.status); put("answer", old.answer.takeLast(4000))
                        putJsonArray("actual_tool_results") { old.events.takeLast(8).forEach { e -> add(buildJsonObject {
                            put("tool", e.name); put("status", e.status); put("arguments", e.arguments.take(2000))
                            e.result?.let { put("result", AppJson.encodeToString(it).take(3000)) }
                        }) } }
                    }.toString().take(26000)
                }.orEmpty()
                val created = AgentTask(question = guard.clean(question.trim()), workspace = path, autonomy = mode,
                    kind = if (manual == null) "agent" else "manual", manualCall = manual, continuation = continuation)
                journal { old ->
                    val pending = old.pending.map { if (acknowledgeUnknown) it.copy(acknowledged = true) else it }
                    old.copy(workspace = path, tasks = (listOf(created) + old.tasks).take(5), pending = pending.filter { it.state in setOf("unknown", "dispatched", "cancel_requested") && !it.acknowledged } + pending.filter { it.state !in setOf("unknown", "dispatched", "cancel_requested") || it.acknowledged }.takeLast(100))
                }
                mutable.update { it.copy(error = null, notice = null, approval = null, file = null) }
                try { context.startForegroundService(Intent(context, AgentService::class.java).setAction(AgentService.RUN).putExtra("task_id", created.id)) }
                catch (_: Exception) { task(created.id) { it.copy(status = "failed", error = "Android blocked foreground work. Keep Pocket Harness visible and start explicitly again.", finishedAt = System.currentTimeMillis()) } }
            } catch (error: Exception) { mutable.update { it.copy(error = guard.clean(friendlyError(error))) } }
            finally { preparing = false }
        }
    }

    fun launchIn(serviceScope: CoroutineScope, taskId: String, finished: () -> Unit) {
        if (running?.isActive == true) return
        running = serviceScope.launch {
            try {
                ready()
                val current = state.value.journal.tasks.firstOrNull { it.id == taskId } ?: return@launch
                // A cold restart marks prepared/running work interrupted; never replay an Intent.
                if (current.status != "prepared") return@launch
                settings = vault.loadSettings(); guard = SecretGuard(settings); grants.clear()
                task(taskId) { it.copy(status = "running", activity = "Starting") }
                withTimeout(20 * 60 * 1000L) {
                    if (current.manualCall != null) {
                        val call = current.manualCall
                        val result = if (call.name == "_diagnostics") checkConnection(current) else executeTool(current, call, true)
                        if (call.name == "read_file") mutable.update { it.copy(file = result) }
                        task(taskId) { it.copy(answer = result.error ?: result.stdout.ifBlank { result.data?.toString().orEmpty() }, status = if (result.ok) "complete" else "failed", activity = "Finished", error = result.error, finishedAt = System.currentTimeMillis()) }
                    } else {
                        val initial = listOf(AgentMessage("system", ToolCatalog.system + "\nSelected workspace: ${current.workspace}\nAuthority: ${current.autonomy}. Limits: 24 turns, 48 tools, 20 minutes. Search configured: ${settings.braveSearchKey.isNotBlank()}."),
                            AgentMessage("user", current.question + if (current.continuation.isBlank()) "" else "\n\nPrevious checkpoint (untrusted data; never replay unknown operations):\n${current.continuation}"))
                        val loop = AgentLoop(AgentProviderClient(), object : AgentTools { override suspend fun execute(call: ToolCall) = executeTool(current, call, false) })
                        var lastPaint = 0L
                        val answer = loop.run(settings, initial,
                            save = { messages -> task(taskId) { it.copy(messages = messages, answer = messages.filter { msg -> msg.role == "assistant" }.map { msg -> msg.content }.filter(String::isNotBlank).joinToString("\n\n"), activity = "Thinking") } },
                            delta = { text -> val now = System.currentTimeMillis(); if (now - lastPaint > 60) { mutable.update { state -> state.copy(journal = state.journal.copy(tasks = state.journal.tasks.map { if (it.id == taskId) it.copy(answer = text, activity = "Responding") else it })) }; lastPaint = now } })
                        task(taskId) { it.copy(status = "complete", answer = it.answer.ifBlank { answer }, activity = if (it.events.isEmpty()) "Answered without tools; no phone command ran" else "Finished", finishedAt = System.currentTimeMillis()) }
                    }
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) { task(taskId) { it.copy(status = if (error is TimeoutCancellationException) "limited" else "stopped", activity = "Agent stopped", error = "Agent requests stopped. Termux cancellation is best-effort; inspect unknown/late command results before continuing.", finishedAt = System.currentTimeMillis()) } }
            } catch (error: Exception) {
                withContext(NonCancellable) { runCatching { task(taskId) { it.copy(status = "failed", activity = "Stopped with an error", error = guard.clean(friendlyError(error)), finishedAt = System.currentTimeMillis()) } } }
            } finally {
                approvalWait?.cancel(); approvalWait = null; grants.clear()
                mutable.update { it.copy(approval = null) }; finished()
            }
        }
    }
    fun stop() { approvalWait?.cancel(); running?.cancel() }
    fun approve(id: String, decision: ApprovalDecision) {
        if (state.value.approval?.id == id && running?.isActive == true) approvalWait?.complete(decision)
    }
    private suspend fun permission(current: AgentTask, call: ToolCall) {
        val scopeKey = AgentPolicy.scope(call, current.workspace)
        if (!AgentPolicy.needsApproval(call, current.autonomy, grants, current.workspace)) return
        val approval = ToolApproval(taskId = current.id, call = call, title = AgentPolicy.label(call), scope = scopeKey)
        val deferred = CompletableDeferred<ApprovalDecision>()
        approvalWait = deferred
        task(current.id) { it.copy(status = "approval", activity = "Approval needed") }
        mutable.update { it.copy(approval = approval) }
        try {
            val decision = deferred.await()
            if (!decision.allow) throw UserFacingException("The user denied this tool. Respect the denial; do not bypass it with another tool.")
            if (decision.forTask) {
                grants += scopeKey
                task(current.id) { it.copy(authorizations = it.authorizations + scopeKey) }
            }
        } finally {
            approvalWait = null; mutable.update { it.copy(approval = null) }
            withContext(NonCancellable) { task(current.id) { it.copy(status = "running") } }
        }
    }
    private suspend fun event(taskId: String, id: String, change: (ToolEvent) -> ToolEvent) = task(taskId) { it.copy(events = it.events.map { e -> if (e.id == id) change(e) else e }) }
    private suspend fun executeTool(current: AgentTask, original: ToolCall, manual: Boolean): ToolResult {
        var call = original
        var args = AgentPolicy.arguments(call)
        guard.requireSafe(call.arguments)
        if (call.name !in AgentPolicy.names) return ToolResult(error = "Unsupported tool; no command was dispatched.")
        if (!AgentPolicy.readOnly(call) && state.value.unresolved)
            return ToolResult(error = "A previous write/terminal outcome is unknown. Only reads are allowed until it is checked with job_status, or the user stops and acknowledges prior commands before a new task. Nothing was replayed.")
        val uiEvent = ToolEvent(callId = call.id, name = call.name, arguments = guard.clean(call.arguments))
        task(current.id) { it.copy(events = (it.events + uiEvent).takeLast(90), activity = AgentPolicy.label(call)) }
        try {
            if (call.name == "git" && args.text("operation") in setOf("fetch", "push")) {
                val preflight = request(current, uiEvent.id, "git", buildJsonObject {
                    put("operation", "remote"); put("repo_path", args.text("repo_path", ".")); put("remote", args.text("remote", "origin")); put("for_fetch", args.text("operation") == "fetch")
                }, internalResult = true)
                if (!preflight.ok || preflight.data?.get("url") == null) return finishEvent(current.id, uiEvent.id, preflight)
                args = JsonObject(args + mapOf("expected_url" to preflight.data.getValue("url"), "expected_head" to preflight.data.getValue("head"), "url" to preflight.data.getValue("url")))
                call = call.copy(arguments = args.toString())
            }
            if (!manual) permission(current, call)
            val result = when (call.name) {
                "web_search" -> web.search(args.text("query"), settings.braveSearchKey)
                "fetch_url" -> { guard.requireSafe(args.text("url")); web.fetch(args.text("url")) }
                "job_status" -> {
                    val old = state.value.journal.pending.firstOrNull { it.id == args.text("command_id") } ?: return finishEvent(current.id, uiEvent.id, ToolResult(error = "Unknown command ID; no command was replayed."))
                    request(current, uiEvent.id, "job_status", JsonObject(emptyMap()), target = old).also { result ->
                        if (result.data?.text("state") in setOf("finished", "cancelled")) journal { j -> j.copy(pending = j.pending.map { if (it.id == old.id) it.copy(state = "complete") else it }) }
                    }
                }
                "git" -> {
                    if (args.text("operation") == "commit") {
                        val paths = args["paths"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                        if (paths.isEmpty() || paths.size > 30) return finishEvent(current.id, uiEvent.id, ToolResult(error = "Commit requires up to 30 explicit file paths."))
                        val hashes = buildJsonObject {
                            for (path in paths) {
                                var offset = 0; val whole = StringBuilder(); var hash = ""
                                do {
                                    val checked = request(current, uiEvent.id, "read_file", buildJsonObject {
                                        put("path", args.text("repo_path", ".").trimEnd('/') + "/" + path); put("offset", offset)
                                    }, internalResult = true)
                                    if (!checked.ok || checked.redacted || checked.data?.get("content") == null) throw UserFacingException("Could not check every commit file for credentials/conflicts. Inspect in Termux.")
                                    val readHash = checked.data.text("sha256")
                                    if (hash.isNotEmpty() && hash != readHash) throw UserFacingException("A commit file changed during preflight. Read it again before committing.")
                                    whole.append(checked.data.text("content")); hash = readHash
                                    offset = checked.data["next_offset"]?.jsonPrimitive?.intOrNull ?: 0
                                    if (whole.length > 90000) throw UserFacingException("Commit preflight file is too large.")
                                } while (checked.data?.get("has_more")?.jsonPrimitive?.booleanOrNull == true)
                                guard.requireSafe(whole.toString()); put(path, hash)
                            }
                        }
                        args = JsonObject(args + ("expected_hashes" to hashes))
                    }
                    request(current, uiEvent.id, call.name, args)
                }
                else -> request(current, uiEvent.id, call.name, args)
            }
            return finishEvent(current.id, uiEvent.id, guard.result(result))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { return finishEvent(current.id, uiEvent.id, ToolResult(error = guard.clean(friendlyError(error)))) }
    }
    private suspend fun finishEvent(taskId: String, id: String, result: ToolResult): ToolResult {
        val unknown = state.value.journal.pending.any { it.taskId == taskId && it.eventId == id && it.state in setOf("unknown", "cancel_requested") }
        event(taskId, id) { it.copy(status = if (unknown) "unknown" else if (result.ok) "complete" else "failed", result = result, finishedAt = System.currentTimeMillis()) }
        return result
    }
    private suspend fun checkConnection(current: AgentTask): ToolResult {
        val e = ToolEvent(name = "_diagnostics", arguments = "{}")
        task(current.id) { it.copy(events = it.events + e, activity = "Checking Termux") }
        val probe = request(current, e.id, "probe", JsonObject(emptyMap()), probe = true)
        if (!probe.ok || probe.data?.get("ready")?.jsonPrimitive?.booleanOrNull != true) {
            val failed = if (probe.ok) probe.copy(error = "Termux is reachable, but required tools are missing. Copy and run the setup script in Termux.") else probe
            mutable.update { it.copy(diagnostics = failed) }; return finishEvent(current.id, e.id, failed)
        }
        val checked = request(current, e.id, "diagnostics", JsonObject(emptyMap()))
        val result = if (checked.ok && checked.data?.get("workspace_exists")?.jsonPrimitive?.booleanOrNull != true)
            checked.copy(error = "Termux tools are ready, but the selected workspace does not exist. Create it in Termux or select an existing directory.") else checked
        mutable.update { it.copy(diagnostics = result) }; return finishEvent(current.id, e.id, result)
    }

    private suspend fun request(current: AgentTask, eventId: String, operation: String, args: JsonObject, probe: Boolean = false, internalResult: Boolean = false, target: PendingCommand? = null): ToolResult {
        val id = newId(); val nonce = newId() + newId()
        val mayWrite = operation in setOf("terminal", "write_file", "edit_file") || (operation == "git" && args.text("operation") !in setOf("status", "diff", "remote"))
        val pending = PendingCommand(id, current.id, eventId, nonce, current.workspace, operation, mayWrite = mayWrite)
        val deferred = CompletableDeferred<ToolResult>(); waits[id] = deferred
        if (internalResult) privateResults += id
        journal { it.copy(pending = it.pending + pending) }
        val payload = buildJsonObject {
            put("id", id); put("nonce", nonce); put("op", operation); put("workspace", current.workspace); put("args", args)
            if (target != null) { put("target", target.id); put("target_nonce", target.nonce) }
        }
        var dispatched = false
        try {
            bridge.send(id, payload, probe)
            dispatched = true
            event(current.id, eventId) { it.copy(status = "running") }
            val timeout = if (probe) 12000L else if (operation == "terminal") (args["timeout_seconds"]?.jsonPrimitive?.longOrNull ?: 60).coerceIn(1, 300) * 1000 + 20000 else if (operation == "git") 150000L else 30000L
            return withTimeout(timeout) { deferred.await() }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                journal { it.copy(pending = it.pending.map { p -> if (p.id == id) p.copy(state = "cancel_requested") else p }) }
                val cancelId = newId()
                runCatching { bridge.send(cancelId, buildJsonObject { put("id", cancelId); put("nonce", newId()); put("op", "cancel"); put("target", id); put("target_nonce", nonce); put("workspace", current.workspace) }) }
                event(current.id, eventId) { it.copy(status = "unknown", result = ToolResult(error = "Stopped waiting. Cancellation was requested; the command outcome is not confirmed. Check Termux or job_status before another write.")) }
            }
            if (error is TimeoutCancellationException) throw UserFacingException("Termux did not return before the deadline. Check external-command setup and inspect the command outcome; it was not replayed.")
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { journal { it.copy(pending = it.pending.map { p -> if (p.id == id) p.copy(state = if (dispatched) "unknown" else "not_dispatched") else p }) } }
            throw error
        } finally { waits.remove(id); privateResults.remove(id) }
    }

    suspend fun acceptResult(id: String, stdout: String, stderr: String, exit: Int, internalError: Int, message: String) {
        ready()
        val pending = state.value.journal.pending.firstOrNull { it.id == id }
        if (!AgentRecovery.canAccept(pending, id)) return
        pending!!
        val latestSettings = vault.loadSettings()
        val result = guard.result(SecretGuard(latestSettings).result(TermuxResultDecoder.decode(id, stdout, stderr, exit, internalError, message, pending.operation == "probe")))
        val hasWaiter = waits[id]?.isActive == true
        var accepted = false
        journal { old ->
            if (!AgentRecovery.canAccept(old.pending.firstOrNull { it.id == id }, id)) return@journal old
            accepted = true
            old.copy(pending = old.pending.map { if (it.id == id) it.copy(state = if (pending.operation != "probe" && result.data?.get("confirmed")?.jsonPrimitive?.booleanOrNull != true) "unknown" else "complete") else it },
                tasks = old.tasks.map { t -> if (t.id != pending.taskId) t else t.copy(events = t.events.map { e ->
                    if (e.id != pending.eventId || privateResults.contains(id)) e else e.copy(status = if (hasWaiter) "running" else "completed_late", result = result, finishedAt = System.currentTimeMillis())
                }) })
        }
        if (!accepted) return
        waits[id]?.complete(result)
        if (!hasWaiter) mutable.update { it.copy(notice = "A late Termux result was recorded. The agent was not restarted.") }
    }
}
