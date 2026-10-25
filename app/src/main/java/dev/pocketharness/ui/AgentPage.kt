@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.pocketharness.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketharness.agent.*
import dev.pocketharness.data.Settings
import dev.pocketharness.data.newId
import kotlinx.serialization.json.*

private fun call(name: String, args: JsonObject) = ToolCall(newId(), name, args.toString())

@Composable
fun AgentPage(agent: AgentCoordinator, settings: Settings) {
    val state by agent.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var setup by remember { mutableStateOf(agent.bridge.setup()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) setup = agent.bridge.setup() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup = agent.bridge.setup() }
    // A denied notification permission still permits user-started foreground work in Android's task manager.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun noticePermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    var workspace by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.loading) { if (!state.loading && workspace.isEmpty()) workspace = state.journal.workspace }
    var input by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(Autonomy.WORKSPACE) }
    var full by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(!setup.available) }
    var showTerminal by rememberSaveable { mutableStateOf(false) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }
    var terminalAccepted by remember { mutableStateOf(false) }
    var directory by rememberSaveable { mutableStateOf(".") }
    var newPath by rememberSaveable { mutableStateOf("") }
    var newContent by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var continueId by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = !state.loading && !state.active
    val selected = state.journal.tasks.firstOrNull { it.id == selectedId } ?: state.task
    LaunchedEffect(state.task?.id) { selectedId = state.task?.id; acknowledged = false; full = false; terminalAccepted = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Work on your phone.", style = MaterialTheme.typography.headlineSmall)
        Text("Read, edit, test, use Git, and research with real tools. Your AI provider needs streamed function-tool support.", style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = agent::clearNotice) { Text("Dismiss") } }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(workspace, { workspace = it }, Modifier.fillMaxWidth(), enabled = ready, label = { Text("Termux workspace") }, supportingText = { Text("Existing absolute directory or ~/projects. Structured files stay here; terminal commands have Termux's full access.") }, singleLine = true)
        TextButton(onClick = { showSetup = !showSetup }) { Text(if (showSetup) "Hide Termux setup" else "Termux setup & connection") }
        if (showSetup) AgentPanel {
            Text("One-time Termux setup", style = MaterialTheme.typography.titleMedium)
            Text(setup.message)
            TextButton(onClick = { uri.openUri("https://f-droid.org/en/packages/com.termux/") }) { Text("Install official Termux") }
            Text("Open Termux once. Copy the script below, paste it into Termux, and run it. It installs Python, Git, SSH and ripgrep, creates ~/projects, and enables external commands.")
            Button(onClick = {
                clipboard.setText(AnnotatedString(context.assets.open("setup_termux.sh").bufferedReader().use { it.readText() }))
            }) { Text("Copy setup script") }
            TextButton(onClick = { context.packageManager.getLaunchIntentForPackage(TermuxContract.PACKAGE)?.let(context::startActivity) }) { Text("Open Termux") }
            Button(onClick = { grant.launch(TermuxContract.PERMISSION) }, enabled = setup.installed && !setup.permitted) { Text("Grant command permission") }
            TextButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("App permissions in Android settings") }
            Text("If the permission prompt is unavailable, look under Permissions → Additional permissions. Termux Git authentication stays in Termux; your Code-tab GitHub token is never passed to the shell.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { noticePermission(); agent.diagnostics(workspace) }, enabled = ready && setup.available) { Text("Run connection check") }
            state.diagnostics?.let { ToolOutput(it) }
        }
        Text(if (settings.braveSearchKey.isBlank()) "Web: public URL reading ready · general search needs a Brave key in Settings." else "Web: Brave Search configured · public URL reading ready.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.unresolved) AgentPanel {
            Text("A command outcome is unknown", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            Text("Stopping cannot undo a write. Inspect Termux/files/Git before continuing. Unknown commands are never replayed.")
            state.journal.pending.filter { it.mayWrite && !it.acknowledged && it.state in setOf("unknown", "cancel_requested", "dispatched") }.takeLast(6).forEach { pending ->
                Text("${pending.operation} · ${pending.id}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { noticePermission(); agent.manual(call("job_status", buildJsonObject { put("command_id", pending.id) }), workspace) }, enabled = ready) { Text("Check execution metadata") }
            }
            CheckRow(acknowledged, { acknowledged = it }, "I inspected prior commands and accept their possible side effects before starting fresh.", ready)
        }
        Text("Task authority", style = MaterialTheme.typography.titleMedium)
        Autonomy.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable(enabled = ready) { mode = option; full = false }) {
                RadioButton(selected = mode == option, onClick = { mode = option; full = false }, enabled = ready)
                Column(Modifier.padding(top = 10.dp).weight(1f)) {
                    Text(when (option) { Autonomy.READ -> "Ask before edits & terminal"; Autonomy.WORKSPACE -> "Edit workspace automatically"; Autonomy.FULL -> "Full terminal authority for this task" })
                    Text(when (option) {
                        Autonomy.READ -> "Automatic file reads, Git status/diff, web tools. Approve other operations."
                        Autonomy.WORKSPACE -> "Also allows file edits, Git init and local commits. Terminal, clone, fetch and push require approval."
                        Autonomy.FULL -> "Automatic commands, destructive actions and network/Git writes using Termux's access."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (mode == Autonomy.FULL) CheckRow(full, { full = it }, "I grant all terminal-accessible file, destructive and network actions for this one task. This is not a sandbox.", ready)
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 240.dp), enabled = ready, label = { Text("Task") }, placeholder = { Text("Inspect this project, make a focused change, run its tests, and report the results…") }, maxLines = 8)
        if (continueId != null) Row {
            Text("Starting from a prior checkpoint with fresh permissions.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { continueId = null }) { Text("Clear") }
        }
        Button(onClick = {
            noticePermission(); agent.start(input, workspace, mode, full, acknowledged, continueId); continueId = null
        }, enabled = ready && input.isNotBlank() && (mode != Autonomy.FULL || full) && (!state.unresolved || acknowledged), modifier = Modifier.fillMaxWidth()) { Text("Start agent task") }
        if (state.active) AgentPanel {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.task?.activity.orEmpty(), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = agent::stop) { Text("Stop agent & request command cancellation") }
        }
        HorizontalDivider()
        Row {
            TextButton(onClick = { showFiles = !showFiles }) { Text(if (showFiles) "Hide files" else "Files & editor") }
            TextButton(onClick = { showTerminal = !showTerminal }) { Text(if (showTerminal) "Hide terminal" else "Terminal") }
        }
        if (showFiles) AgentPanel {
            Text("Termux files", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(directory, { directory = it }, Modifier.fillMaxWidth(), label = { Text("Workspace-relative directory") }, enabled = ready, singleLine = true)
            OutlinedButton(onClick = { noticePermission(); agent.manual(call("list_files", buildJsonObject { put("path", directory) }), workspace) }, enabled = ready && setup.available) { Text("List directory") }
            val listing = state.task?.takeIf { it.manualCall?.name == "list_files" }?.events?.lastOrNull()?.result?.data
            listing?.get("entries")?.jsonArray?.forEach { entry ->
                val item = entry.jsonObject
                val folder = item["directory"]?.jsonPrimitive?.booleanOrNull == true
                TextButton(onClick = {
                    if (folder) directory = item.text("path")
                    else { noticePermission(); agent.manual(call("read_file", buildJsonObject { put("path", item.text("path")) }), workspace) }
                }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text((if (folder) "[folder] " else "") + item.text("name")) }
            }
            if (listing?.get("limited")?.jsonPrimitive?.booleanOrNull == true) Text("Listing limited to 150 entries; select a narrower directory.")
            OutlinedTextField(newPath, { newPath = it }, Modifier.fillMaxWidth(), label = { Text("New file path") }, singleLine = true, enabled = ready)
            OutlinedTextField(newContent, { newContent = it }, Modifier.fillMaxWidth().heightIn(max = 200.dp), label = { Text("New file content") }, enabled = ready, maxLines = 8)
            Button(onClick = { noticePermission(); agent.manual(call("write_file", buildJsonObject { put("path", newPath); put("content", newContent); put("expected_sha", "") }), workspace, acknowledged) }, enabled = ready && newPath.isNotBlank() && (!state.unresolved || acknowledged)) { Text("Create new file") }
        }
        if (showTerminal) AgentPanel {
            Text("Real Termux terminal", style = MaterialTheme.typography.titleMedium)
            Text("Non-interactive Bash, 60-second limit here (agent: up to 300s). Programs have Termux's file/network access. Use Termux itself for interactive login, SSH setup and long-running work.")
            OutlinedTextField(command, { command = it; terminalAccepted = false }, Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp), label = { Text("Command") }, enabled = ready, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), maxLines = 8)
            CheckRow(terminalAccepted, { terminalAccepted = it }, "Run this exact command with Termux's full access; it may change or delete files and use the network.", ready)
            Button(onClick = { noticePermission(); agent.manual(call("terminal", buildJsonObject { put("command", command); put("timeout_seconds", 60) }), workspace, acknowledged) }, enabled = ready && setup.available && command.isNotBlank() && terminalAccepted && (!state.unresolved || acknowledged)) { Text("Run command") }
        }
        if (selected != null) {
            Text("Task history", style = MaterialTheme.typography.titleMedium)
            state.journal.tasks.forEach { old -> TextButton(onClick = { selectedId = old.id }) { Text("${old.status} · ${old.question.take(70)}") } }
            AgentPanel {
                Text(selected.question, style = MaterialTheme.typography.titleMedium)
                Text("${selected.status} · ${selected.events.size} tool request(s) · ${selected.autonomy}", style = MaterialTheme.typography.labelMedium)
                Text(selected.workspace, style = MaterialTheme.typography.bodySmall)
                selected.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (selected.answer.isNotBlank() && selected.kind == "agent") SelectionContainer { Text(selected.answer) }
                if (selected.authorizations.isNotEmpty()) Text("Task grants: ${selected.authorizations.joinToString()}", style = MaterialTheme.typography.bodySmall)
                if (!state.active && selected.kind == "agent") OutlinedButton(onClick = { input = selected.question; continueId = selected.id; mode = selected.autonomy; full = false }) { Text("Use checkpoint for a new task") }
                selected.events.forEach { ToolEventCard(it) }
            }
        }
        Text("Android can stop foreground work. Task limit: 20 minutes, 24 model turns, 48 tools. Stop requests cancellation; escaped background processes can survive. Check unknown outcomes before another write.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(15.dp))
    }
    state.file?.let { result ->
        FileEditor(result, ready, onClose = agent::closeFile, onSave = { path, content, sha ->
            noticePermission(); agent.closeFile(); agent.manual(call("write_file", buildJsonObject { put("path", path); put("content", content); put("expected_sha", sha) }), workspace, acknowledged)
        }, canWrite = !state.unresolved || acknowledged)
    }
}

@Composable
private fun AgentPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}
@Composable
private fun CheckRow(checked: Boolean, change: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { change(!checked) }) {
        Checkbox(checked, change, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(top = 11.dp), style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun ToolEventCard(event: ToolEvent) {
    var open by remember(event.id) { mutableStateOf(false) }
    val args = runCatching { AgentPolicy.arguments(ToolCall(event.callId, event.name, event.arguments)) }.getOrNull()
    HorizontalDivider()
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) { Text("${event.status} · ${AgentPolicy.label(ToolCall(event.callId, event.name, event.arguments))}") }
    if (open) {
        args?.let { SelectionContainer { Text(it.entries.joinToString("\n") { (key, value) -> "$key: ${if (value is JsonPrimitive) value.content else value}" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        event.result?.let { ToolOutput(it) } ?: Text("No confirmed result yet.")
    }
}
@Composable
private fun ToolOutput(result: ToolResult) {
    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Exit: ${result.exitCode ?: "—"}${if (result.cwd.isNotBlank()) " · ${result.cwd}" else ""}", style = MaterialTheme.typography.labelSmall)
    if (result.timedOut) Text("Command timed out", color = MaterialTheme.colorScheme.error)
    if (result.truncated) Text("Output is truncated", color = MaterialTheme.colorScheme.primary)
    if (result.redacted) Text("Credential-like output was redacted. Do not use this as an exact file replacement.", color = MaterialTheme.colorScheme.error)
    if (result.stdout.isNotBlank()) SelectionContainer { Text(result.stdout, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    if (result.stderr.isNotBlank()) SelectionContainer { Text("stderr:\n${result.stderr}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    result.data?.let { data ->
        val visible = data.filterKeys { it !in setOf("content", "entries", "confirmed") }
        SelectionContainer { Text(visible.entries.joinToString("\n") { (key, value) -> "$key: ${if (value is JsonPrimitive) value.content else value}" }, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
private fun FileEditor(result: ToolResult, ready: Boolean, onClose: () -> Unit, onSave: (String, String, String) -> Unit, canWrite: Boolean) {
    val data = result.data
    val path = data?.text("path").orEmpty()
    var content by remember(result) { mutableStateOf(data?.text("content").orEmpty()) }
    val complete = data?.get("complete")?.jsonPrimitive?.booleanOrNull == true && !result.redacted && result.ok
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(path.ifBlank { "File preview" }, style = MaterialTheme.typography.titleMedium)
                result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!complete) Text("Partial or redacted preview: manual replacement is disabled. The agent can read further slices and use a SHA-checked partial edit.")
                OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().weight(1f), readOnly = !complete, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                Text("${data?.text("bytes")} bytes · SHA ${data?.text("sha256")?.take(12)}", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onClose) { Text("Close") }
                    Button(onClick = { onSave(path, content, data?.text("sha256").orEmpty()) }, enabled = ready && complete && canWrite) { Text("Save with conflict check") }
                }
            }
        }
    }
}

@Composable
fun AgentApproval(agent: AgentCoordinator, approval: ToolApproval?) {
    if (approval == null) return
    var forTask by remember(approval.id) { mutableStateOf(false) }
    val args = runCatching { AgentPolicy.arguments(approval.call) }.getOrNull()
    AlertDialog(onDismissRequest = { agent.approve(approval.id, ApprovalDecision(false)) }, title = { Text(approval.title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The agent requests this operation:")
            SelectionContainer { Text(args?.entries?.joinToString("\n") { (key, value) -> "$key: ${if (value is JsonPrimitive) value.content else value}" }.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            Text(if (approval.call.name == "terminal") "Terminal programs can access all files and network available to Termux, including destructive actions. The workspace is not a sandbox." else "Review the exact paths and Git destination. This may write files or use the network.")
            CheckRow(forTask, { forTask = it }, when (approval.call.name) {
                "terminal" -> "Allow ALL terminal, file, destructive and network/Git actions for the rest of this task."
                "write_file", "edit_file" -> "Allow further structured file edits in this workspace for this task."
                else -> "Allow this operation for this repository, remote URL and branch for this task."
            })
        }
    }, confirmButton = { TextButton(onClick = { agent.approve(approval.id, ApprovalDecision(true, forTask)) }) { Text(if (forTask) "Grant for task" else "Allow once") } }, dismissButton = { TextButton(onClick = { agent.approve(approval.id, ApprovalDecision(false)) }) { Text("Deny") } })
}
