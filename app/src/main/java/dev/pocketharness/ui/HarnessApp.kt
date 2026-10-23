@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.pocketharness.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketharness.*
import dev.pocketharness.data.*
import dev.pocketharness.review.unifiedDiff
import java.text.DateFormat
import java.util.Date

@Composable
fun HarnessApp(vm: HarnessViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var history by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    DisposableEffect(state.page) {
        if (state.page == Page.Settings) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    HarnessTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Outlined.Terminal, null, Modifier.padding(7.dp).size(19.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text("pocket harness", style = MaterialTheme.typography.titleLarge)
                } }, actions = {
                    IconButton(onClick = { history = true }, enabled = !state.loading && !state.startupError) {
                        Icon(Icons.Outlined.History, "Saved conversations")
                    }
                    IconButton(onClick = vm::newConversation, enabled = state.busy == null && !state.loading && !state.startupError) {
                        Icon(Icons.Outlined.Add, "New conversation")
                    }
                })
            },
            bottomBar = {
                if (!state.loading && !state.startupError) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = state.page == Page.Chat, onClick = { vm.page(Page.Chat) }, icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, label = { Text("Chat") })
                    NavigationBarItem(selected = state.page == Page.Repositories, onClick = { vm.page(Page.Repositories) }, icon = { Icon(Icons.Outlined.FolderOpen, null) }, label = { Text("Code") })
                    if (state.draft != null || state.published != null) NavigationBarItem(selected = state.page == Page.Review, onClick = { vm.page(Page.Review) }, icon = { Icon(Icons.Outlined.Difference, null) }, label = { Text("Review") })
                    NavigationBarItem(selected = state.page == Page.Settings, onClick = { vm.page(Page.Settings) }, icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text("Settings") })
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (state.error != null) Banner(state.error!!, true, vm::clearError)
                else if (state.notice != null) Banner(state.notice!!, false, vm::clearError)
                if (state.busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.busy!!, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        if (state.canCancel) TextButton(onClick = vm::cancel) { Text("Cancel") }
                        else Text("Please wait", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.startupError -> EmptyPanel("Local storage is locked", "Your encrypted data has been preserved.", "Retry", vm::initialize)
                    else -> when (state.page) {
                        Page.Chat -> ChatPage(state, vm)
                        Page.Repositories -> RepositoriesPage(state, vm)
                        Page.Settings -> SettingsPage(state, vm)
                        Page.Review -> ReviewPage(state, vm)
                    }
                }
            }
        }
        if (history) HistorySheet(state, onDismiss = { history = false }, onSelect = { vm.selectConversation(it); history = false }, onDelete = vm::deleteConversation)
        state.previewFile?.let { FilePreview(it, state, vm) }
    }
}

@Composable
private fun Banner(text: String, error: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (error) colors.errorContainer else colors.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 5.dp, bottom = 5.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss message", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun ChatPage(state: HarnessState, vm: HarnessViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    val messages = state.active?.messages.orEmpty()
    val acceptedMessage = messages.lastOrNull { it.role == "user" }
    LaunchedEffect(acceptedMessage?.id) {
        if (acceptedMessage != null && acceptedMessage.content == input.trim()) input = ""
    }
    val list = rememberLazyListState()
    val last = messages.lastOrNull()
    LaunchedEffect(last?.id, last?.content?.length) {
        if (messages.isNotEmpty() && (!list.canScrollForward || list.lastScrolledForward || list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == messages.lastIndex)) list.animateScrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 24.dp), verticalArrangement = Arrangement.Center) {
                Text("YOUR POCKET WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text("A little space.\nFor big ideas.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(14.dp))
                Text("Your models. Your repositories.\nOne focused conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(30.dp))
                SuggestionChip(onClick = { input = "Help me understand this code and suggest a focused improvement." }, label = { Text("Understand a codebase") }, icon = { Icon(Icons.Outlined.Code, null, Modifier.size(18.dp)) })
                if (state.settings.apiKey.isBlank()) {
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = { vm.page(Page.Settings) }, contentPadding = PaddingValues(0.dp)) { Text("Connect your AI provider  →") }
                }
            }
        } else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(messages, key = { it.id }) { MessageCard(it) }
        }
        if (state.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.page(Page.Repositories) }, Modifier.weight(1f)) {
                    Text("${state.selected.size} file${if (state.selected.size == 1) "" else "s"} · ${state.selected.first().snapshot.repo.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = vm::clearContext, enabled = state.busy == null) { Icon(Icons.Outlined.Close, "Clear selected context", Modifier.size(18.dp)) }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, enabled = state.busy == null,
                placeholder = { Text("Ask anything, or describe a change…") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                shape = RoundedCornerShape(22.dp), maxLines = 6, trailingIcon = {
                    IconButton(onClick = { vm.send(input) },
                        enabled = input.isNotBlank() && state.busy == null) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Send message", tint = MaterialTheme.colorScheme.primary)
                    }
                })
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.settings.model, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.propose(input) }, enabled = input.isNotBlank() && state.selected.isNotEmpty() && state.busy == null) {
                    Icon(Icons.Outlined.Difference, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Propose edits")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: Message) {
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).background(if (user) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
            Text(if (user) "YOU" else "HARNESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.status == "streaming") Text("· thinking", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(10.dp))
        if (message.content.isNotEmpty()) SelectionContainer {
            Text(message.content, style = MaterialTheme.typography.bodyLarge,
                modifier = if (user) Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(15.dp) else Modifier.fillMaxWidth())
        }
        if (message.files.isNotEmpty()) Text(message.files.joinToString(" · ") { it.path }, Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (message.status in setOf("cancelled", "interrupted", "error")) Text(
            when (message.status) { "cancelled" -> "Stopped · partial response kept"; "interrupted" -> "Interrupted when the app closed"; else -> "Response incomplete · see the error above" },
            Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RepositoriesPage(state: HarnessState, vm: HarnessViewModel) {
    val snapshot = state.snapshot
    var filter by rememberSaveable { mutableStateOf("") }
    var branch by remember(snapshot) { mutableStateOf(snapshot?.branch.orEmpty()) }
    val ready = state.busy == null
    BackHandler(enabled = snapshot != null && ready) { if (state.directories.size > 1) vm.upDirectory() else vm.repositoryList() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(if (snapshot == null) "Your repositories" else snapshot.repo.name, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(if (snapshot == null) "Bring just the files you need into the conversation." else "${snapshot.repo.fullName} · ${snapshot.headSha.take(8)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (snapshot == null) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), placeholder = { Text("Filter loaded repositories") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp))
                IconButton(onClick = { vm.loadRepositories() }, enabled = ready) { Icon(Icons.Outlined.Refresh, "Refresh repositories") }
            }
            if (state.repos.isEmpty()) {
                EmptyPanel(if (state.reposLoaded) "No repositories found" else "Connect to your code", if (state.settings.githubToken.isBlank()) "Add a GitHub token with access to the repositories you want to use." else "Load the repositories your GitHub token can access.",
                    if (state.settings.githubToken.isBlank()) "Open settings" else "Load repositories", { if (state.settings.githubToken.isBlank()) vm.page(Page.Settings) else vm.loadRepositories() }, ready)
            } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val filtered = state.repos.filter { it.fullName.contains(filter, true) }
                if (filtered.isEmpty()) item { Text("No loaded repositories match this filter.", Modifier.padding(16.dp)) }
                items(filtered, key = { it.fullName }) { repo ->
                    ListItem(headlineContent = { Text(repo.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text("${repo.owner} · ${repo.defaultBranch}") }, leadingContent = { Icon(if (repo.private) Icons.Outlined.Lock else Icons.Outlined.Source, null) }, trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }, modifier = Modifier.clickable(enabled = ready) { vm.openRepository(repo) })
                }
                if (state.moreRepos) item { TextButton(onClick = { vm.loadRepositories(true) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Load more repositories") } }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::repositoryList, enabled = ready) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "All repositories") }
                OutlinedTextField(branch, { branch = it }, Modifier.weight(1f), label = { Text("Base branch") }, singleLine = true, enabled = ready, textStyle = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { vm.openRepository(snapshot.repo, branch) }, enabled = ready) { Icon(Icons.Outlined.Refresh, "Load branch and refresh base") }
            }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.directories.lastOrNull()?.path?.ifEmpty { "/" } ?: "/", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.directories.size > 1) TextButton(onClick = vm::upDirectory, enabled = ready) { Text("Up a folder") }
            }
            HorizontalDivider(Modifier.padding(horizontal = 22.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                if (state.entries.isEmpty()) item { Text("This directory is empty.", Modifier.padding(20.dp)) }
                items(state.entries, key = { it.path }) { entry ->
                    val selected = state.selected.any { it.path == entry.path && it.snapshot == snapshot }
                    val supported = entry.type == "tree" || (entry.type == "blob" && entry.mode in setOf("100644", "100755"))
                    ListItem(headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }, supportingContent = {
                        if (entry.type != "tree") Text(if (supported) "${entry.size} bytes" else "Special file · unavailable", style = MaterialTheme.typography.bodySmall)
                    }, leadingContent = { Icon(if (entry.type == "tree") Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { if (selected) Icon(Icons.Outlined.CheckCircle, "Selected for context", tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(enabled = ready && supported) { vm.openEntry(entry) })
                }
            }
            if (state.selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.selected.size} files selected\n${state.selected.sumOf { it.content.toByteArray().size } / 1024} / 120 KiB", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.page(Page.Chat) }) { Text("Use in chat") }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(state: HarnessState, vm: HarnessViewModel) {
    var endpoint by remember(state.settings) { mutableStateOf(state.settings.endpoint) }
    var model by remember(state.settings) { mutableStateOf(state.settings.model) }
    var apiKey by remember(state.settings) { mutableStateOf(state.settings.apiKey) }
    var token by remember(state.settings) { mutableStateOf(state.settings.githubToken) }
    var clear by remember { mutableStateOf(false) }
    val ready = state.busy == null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Make it yours.", style = MaterialTheme.typography.headlineSmall)
        Text("Connect an OpenAI-compatible Chat Completions provider and your GitHub repositories.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("AI PROVIDER")
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("API base URL") }, supportingText = { Text("HTTPS only. The app appends /chat/completions.") }, singleLine = true, enabled = ready, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") }, singleLine = true, enabled = ready)
        SecretField(apiKey, { apiKey = it }, "AI API key", ready)
        Text("Your messages and selected file contents are sent to this endpoint. Use a provider you trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionLabel("GITHUB")
        SecretField(token, { token = it }, "Fine-grained personal access token", ready)
        Text("Choose specific repositories. Contents: read for browsing, read and write for commits. Metadata access is also required. Organization approval may be needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Key, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Keys and conversations are encrypted on this device with Android Keystore. They are excluded from backups. No analytics or credential logging.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { vm.saveSettings(Settings(endpoint, model, apiKey, token)) }, enabled = ready, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save settings") }
        TextButton(onClick = { clear = true }, enabled = ready && (state.settings.apiKey.isNotEmpty() || state.settings.githubToken.isNotEmpty()), modifier = Modifier.fillMaxWidth()) { Text("Remove saved credentials") }
        Text("POCKET HARNESS  /  0.1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Remove credentials?") }, text = { Text("You will need to enter your AI key and GitHub token again. Conversations stay on this device.") }, confirmButton = { TextButton(onClick = { vm.clearCredentials(); clear = false }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
}

@Composable
private fun ReviewPage(state: HarnessState, vm: HarnessViewModel) {
    val result = state.published
    val draft = state.draft
    val uri = LocalUriHandler.current
    if (result != null) {
        Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text("A considered change.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(14.dp))
            Text("Committed to ${result.branch}", style = MaterialTheme.typography.bodyLarge)
            Text(result.commitSha.take(12), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 12.dp))
            if (result.baseAdvanced) Text("The base may have advanced during publication. Compare the branch with the current base before merging.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { uri.openUri(result.url) }, modifier = Modifier.padding(top = 16.dp)) { Text("View commit on GitHub") }
        }
        return
    }
    if (draft == null) { EmptyPanel("Nothing to review yet", "Select repository files, describe a change in Chat, then choose Propose edits.", "Go to chat", { vm.page(Page.Chat) }); return }
    var branch by remember(draft) { mutableStateOf("harness/change-${System.currentTimeMillis().toString(36)}") }
    var message by remember(draft) { mutableStateOf(draft.proposal.summary.lineSequence().first().take(100)) }
    var approved by remember(draft) { mutableStateOf(false) }
    val ready = state.busy == null
    BackHandler(enabled = ready) { vm.page(Page.Chat) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Review every change.", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(draft.proposal.summary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Text("${draft.snapshot.repo.fullName}\n${draft.snapshot.branch} @ ${draft.snapshot.headSha.take(12)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(draft.proposal.edits, key = { it.path }) { edit ->
            val original = draft.originals.first { it.path == edit.path }
            EditPreview(edit.path, original.content, edit.content)
        }
        item {
            SectionLabel("PUBLISH A NEW BRANCH")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(branch, { branch = it; approved = false }, Modifier.fillMaxWidth(), label = { Text("New branch name") }, enabled = ready, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(message, { message = it; approved = false }, Modifier.fillMaxWidth(), label = { Text("Commit message") }, enabled = ready, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(approved, enabled = ready, role = Role.Checkbox, onValueChange = { approved = it })) {
                Checkbox(checked = approved, onCheckedChange = null, enabled = ready)
                Text("I reviewed all ${draft.proposal.edits.size} file changes and approve this commit to ${draft.snapshot.repo.fullName}.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
            Text("The base is checked again before publication. If it changed, refresh and generate a new proposal. Existing branches are never overwritten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.publish(branch, message) }, enabled = ready && approved && branch.isNotBlank() && message.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.AccountTree, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Commit to new branch")
            }
            TextButton(onClick = vm::discardDraft, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Discard proposal") }
        }
    }
}

@Composable
private fun EditPreview(path: String, before: String, after: String) {
    var tab by remember { mutableIntStateOf(0) }
    val diff = remember(before, after) { unifiedDiff(path, before, after) }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(path, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Diff", "Before", "After").forEachIndexed { index, label ->
                FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) })
            } }
            CodeBlock(when (tab) { 1 -> before; 2 -> after; else -> diff }, Modifier.fillMaxWidth().heightIn(max = 420.dp), diff = tab == 0)
        }
    }
}

@Composable
private fun CodeBlock(text: String, modifier: Modifier = Modifier, diff: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val display = remember(text, diff, colors) {
        buildAnnotatedString {
            if (diff) text.lineSequence().forEach { line ->
                val color = when { line.startsWith("+") -> colors.primary; line.startsWith("-") -> colors.error; else -> colors.onSurfaceVariant }
                withStyle(SpanStyle(color = color)) { append(line); append('\n') }
            } else append(text.ifEmpty { "(empty file)" })
        }
    }
    SelectionContainer {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(display, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, softWrap = false)
        }
    }
}

@Composable
private fun FilePreview(file: ContextFile, state: HarnessState, vm: HarnessViewModel) {
    val selected = state.selected.any { it.path == file.path && it.snapshot == file.snapshot }
    Dialog(onDismissRequest = vm::closePreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.path, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = vm::closePreview) { Icon(Icons.Outlined.Close, "Close file preview") }
                }
                Text("${file.snapshot.repo.fullName} @ ${file.snapshot.headSha.take(8)}", style = MaterialTheme.typography.bodySmall)
                CodeBlock(file.content, Modifier.weight(1f).fillMaxWidth())
                Text("Selected files are sent to your AI provider with your next message. Up to 6 files / 120 KiB total.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Button(onClick = { vm.toggleFile(file) }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text(if (selected) "Remove from context" else "Add to context") }
                TextButton(onClick = vm::closePreview, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun HistorySheet(state: HarnessState, onDismiss: () -> Unit, onSelect: (String) -> Unit, onDelete: (String) -> Unit) {
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Saved conversations", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 550.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            if (state.conversations.isEmpty()) item { Text("Your conversations will appear here.", Modifier.padding(24.dp)) }
            items(state.conversations, key = { it.id }) { conversation ->
                ListItem(headlineContent = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${conversation.messages.count { it.role == "user" }} messages · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(conversation.updatedAt))}") },
                    trailingContent = { IconButton(onClick = { deleting = conversation }, enabled = state.busy == null) { Icon(Icons.Outlined.DeleteOutline, "Delete conversation") } },
                    modifier = Modifier.clickable(enabled = state.busy == null) { onSelect(conversation.id) })
            }
        }
    }
    deleting?.let { conversation -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete conversation?") }, text = { Text("This removes '${conversation.title}' and its saved file context from this device.") }, confirmButton = { TextButton(onClick = { onDelete(conversation.id); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }

@Composable
fun EmptyPanel(title: String, description: String, action: String, onClick: () -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp)); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp)); Button(onClick = onClick, enabled = enabled) { Text(action) }
    }
}
