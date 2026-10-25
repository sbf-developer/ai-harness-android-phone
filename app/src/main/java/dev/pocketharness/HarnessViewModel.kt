package dev.pocketharness

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketharness.data.*
import dev.pocketharness.network.*
import dev.pocketharness.review.ReviewPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Page { Chat, Repositories, Agent, Settings, Review }

data class HarnessState(
    val loading: Boolean = true,
    val startupError: Boolean = false,
    val settings: Settings = Settings(),
    val conversations: List<Conversation> = emptyList(),
    val activeId: String? = null,
    val page: Page = Page.Chat,
    val busy: String? = null,
    val canCancel: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val repos: List<Repo> = emptyList(),
    val reposLoaded: Boolean = false,
    val repoPage: Int = 0,
    val moreRepos: Boolean = true,
    val snapshot: RepoSnapshot? = null,
    val directories: List<Directory> = emptyList(),
    val entries: List<TreeEntry> = emptyList(),
    val selected: List<ContextFile> = emptyList(),
    val previewFile: ContextFile? = null,
    val draft: ReviewDraft? = null,
    val published: PublishResult? = null,
) {
    val active get() = conversations.firstOrNull { it.id == activeId }
}

class HarnessViewModel internal constructor(
    application: Application,
    private val vault: LocalStore,
    private val ai: ChatService,
    private val github: GitHubClient,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as? HarnessApplication)?.vault ?: Vault(application), AiClient(), GitHubClient())
    private val mutable = MutableStateFlow(HarnessState())
    val state = mutable.asStateFlow()
    private var work: Job? = null

    init { initialize() }
    fun initialize() {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val settings = vault.loadSettings()
                val conversations = vault.loadConversations().map { conversation ->
                    conversation.copy(messages = conversation.messages.map {
                        if (it.status == "streaming") it.copy(status = "interrupted") else it
                    })
                }.sortedByDescending { it.updatedAt }
                mutable.update { it.copy(loading = false, startupError = false, settings = settings,
                    conversations = conversations, activeId = conversations.firstOrNull()?.id) }
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, startupError = true,
                    error = "Encrypted local data could not be opened. Retry, or clear this app's storage in Android Settings to start over.") }
            }
        }
    }
    fun page(page: Page) { mutable.update { it.copy(page = page) } }
    fun clearError() { mutable.update { it.copy(error = null, notice = null) } }
    fun closePreview() { mutable.update { it.copy(previewFile = null) } }
    fun cancel() { if (state.value.canCancel) work?.cancel() }

    private fun task(label: String, cancellable: Boolean = true, block: suspend () -> Unit) {
        if (state.value.loading || state.value.startupError || work?.isActive == true) return
        work = viewModelScope.launch {
            mutable.update { it.copy(busy = label, canCancel = cancellable, error = null, notice = null) }
            try { block() }
            catch (error: CancellationException) { mutable.update { it.copy(notice = "Cancelled.") }; throw error }
            catch (error: Exception) { mutable.update { it.copy(error = friendlyError(error)) } }
            finally { mutable.update { it.copy(busy = null, canCancel = true) } }
        }
    }

    private suspend fun saveChats() {
        try { vault.saveConversations(state.value.conversations) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw UserFacingException("Could not save this conversation on the device. Check available storage.") }
    }
    fun newConversation() = task("Starting conversation", false) {
        val conversation = Conversation()
        mutable.update { it.copy(conversations = listOf(conversation) + it.conversations, activeId = conversation.id, page = Page.Chat) }
        saveChats()
    }
    fun selectConversation(id: String) {
        if (state.value.busy == null) mutable.update { it.copy(activeId = id, page = Page.Chat, error = null) }
    }
    fun deleteConversation(id: String) = task("Deleting conversation", false) {
        mutable.update { state ->
            val remaining = state.conversations.filterNot { it.id == id }
            state.copy(conversations = remaining, activeId = if (state.activeId == id) remaining.firstOrNull()?.id else state.activeId)
        }
        saveChats()
    }
    fun saveSettings(settings: Settings) = task("Saving settings", false) {
        val checked = ProviderValidation.settings(settings)
        vault.saveSettings(checked)
        mutable.update {
            val sameToken = checked.githubToken == it.settings.githubToken
            it.copy(settings = checked, notice = "Settings saved securely on this device.",
                repos = if (sameToken) it.repos else emptyList(), reposLoaded = sameToken && it.reposLoaded,
                snapshot = if (sameToken) it.snapshot else null, directories = if (sameToken) it.directories else emptyList(),
                entries = if (sameToken) it.entries else emptyList(), selected = if (sameToken) it.selected else emptyList(),
                draft = if (sameToken) it.draft else null, published = if (sameToken) it.published else null,
                previewFile = if (sameToken) it.previewFile else null)
        }
    }
    fun clearCredentials() = saveSettings(state.value.settings.copy(apiKey = "", githubToken = "", braveSearchKey = ""))

    fun send(text: String) = task("Streaming response") {
        if (text.isBlank()) throw UserFacingException("Write a message first.")
        if (text.length > 32_000) throw UserFacingException("Keep each message under 32,000 characters.")
        val settings = state.value.settings
        if (settings.apiKey.isBlank()) throw UserFacingException("Add your AI API key in Settings first.")
        val files = state.value.selected.toList()
        if (files.isNotEmpty()) ReviewPolicy.validateContext(files)
        val current = state.value.active ?: Conversation()
        val user = Message(role = "user", content = text.trim(), files = files)
        val history = current.messages + user
        val prompt = Prompts.chat(history)
        val reply = Message(role = "assistant", content = "", status = "streaming")
        val conversation = current.copy(title = if (current.messages.isEmpty()) text.trim().take(48) else current.title,
            updatedAt = System.currentTimeMillis(), messages = history + reply)
        replace(conversation)
        streamReply(conversation.id, reply.id, settings, prompt)
    }
    fun retryLastReply() = task("Retrying response") {
        val current = state.value.active ?: throw UserFacingException("There is no response to retry.")
        val failed = current.messages.lastOrNull()
        if (failed?.role != "assistant" || failed.status !in setOf("error", "cancelled", "interrupted"))
            throw UserFacingException("Only an incomplete last response can be retried.")
        val settings = state.value.settings
        if (settings.apiKey.isBlank()) throw UserFacingException("Add your AI API key in Settings first.")
        val history = current.messages.dropLast(1)
        if (history.lastOrNull()?.role != "user") throw UserFacingException("The original message could not be found.")
        val prompt = Prompts.chat(history)
        val reply = failed.copy(content = "", status = "streaming")
        replace(current.copy(messages = history + reply, updatedAt = System.currentTimeMillis()))
        streamReply(current.id, reply.id, settings, prompt)
    }
    private suspend fun streamReply(conversationId: String, replyId: String, settings: Settings, prompt: List<AiMessage>) {
        val output = StringBuilder()
        var lastPaint = 0L
        var lastSave = System.currentTimeMillis()
        try {
            saveChats()
            ai.stream(settings, prompt) { delta ->
                output.append(delta)
                val now = System.currentTimeMillis()
                if (now - lastPaint >= 40) { updateReply(conversationId, replyId, output.toString(), "streaming"); lastPaint = now }
                if (now - lastSave >= 2000) { saveChats(); lastSave = now }
            }
            updateReply(conversationId, replyId, output.toString(), "complete")
        } catch (error: CancellationException) {
            updateReply(conversationId, replyId, output.toString(), "cancelled"); throw error
        } catch (error: Exception) {
            updateReply(conversationId, replyId, output.toString(), "error"); throw error
        } finally { withContext(NonCancellable) { saveChats() } }
    }
    private fun replace(conversation: Conversation) {
        mutable.update { it.copy(conversations = (it.conversations.filterNot { item -> item.id == conversation.id } + conversation).sortedByDescending { item -> item.updatedAt }, activeId = conversation.id) }
    }
    private fun updateReply(conversationId: String, messageId: String, content: String, status: String) {
        mutable.update { state -> state.copy(conversations = state.conversations.map { conversation ->
            if (conversation.id != conversationId) conversation else conversation.copy(messages = conversation.messages.map {
                if (it.id != messageId) it else it.copy(content = content, status = status)
            })
        }) }
    }

    fun loadRepositories(more: Boolean = false) = task("Loading repositories") {
        val page = if (more) state.value.repoPage + 1 else 1
        val repos = github.repositories(state.value.settings.githubToken, page)
        mutable.update { it.copy(repos = ((if (more) it.repos else emptyList()) + repos).distinctBy { repo -> repo.fullName },
            repoPage = page, reposLoaded = true, moreRepos = repos.size == 50) }
    }
    fun openRepository(repo: Repo, branch: String = repo.defaultBranch) = task("Opening repository") {
        if (branch.isBlank()) throw UserFacingException("Enter a branch name.")
        val token = state.value.settings.githubToken
        val snapshot = github.snapshot(token, repo, branch.trim())
        val root = Directory("", snapshot.treeSha)
        val entries = github.entries(token, snapshot, root)
        mutable.update { it.copy(snapshot = snapshot, directories = listOf(root), entries = entries,
            selected = emptyList(), previewFile = null, draft = null, published = null) }
    }
    fun repositoryList() {
        if (state.value.busy == null) mutable.update { it.copy(snapshot = null, directories = emptyList(), entries = emptyList()) }
    }
    fun openEntry(entry: TreeEntry) = task(if (entry.type == "tree") "Opening folder" else "Reading file") {
        val snapshot = state.value.snapshot ?: return@task
        val token = state.value.settings.githubToken
        if (entry.type == "tree") {
            val directory = Directory(entry.path, entry.sha)
            val entries = github.entries(token, snapshot, directory)
            mutable.update { it.copy(directories = it.directories + directory, entries = entries) }
        } else {
            val file = github.file(token, snapshot, entry)
            mutable.update { it.copy(previewFile = file) }
        }
    }
    fun upDirectory() = task("Opening parent folder") {
        if (state.value.directories.size < 2) return@task
        val directories = state.value.directories.dropLast(1)
        val entries = github.entries(state.value.settings.githubToken, state.value.snapshot!!, directories.last())
        mutable.update { it.copy(directories = directories, entries = entries) }
    }
    fun toggleFile(file: ContextFile) {
        if (state.value.busy != null) return
        try {
            val exists = state.value.selected.any { it.path == file.path && it.snapshot == file.snapshot }
            val files = if (exists) state.value.selected.filterNot { it.path == file.path && it.snapshot == file.snapshot } else state.value.selected + file
            if (files.isNotEmpty()) ReviewPolicy.validateContext(files)
            mutable.update { it.copy(selected = files, error = null) }
        } catch (error: Exception) { mutable.update { it.copy(error = friendlyError(error)) } }
    }
    fun clearContext() { if (state.value.busy == null) mutable.update { it.copy(selected = emptyList()) } }

    fun propose(instruction: String) = task("Generating edit proposal") {
        if (instruction.isBlank()) throw UserFacingException("Describe the changes in the message box first.")
        if (instruction.length > 32_000) throw UserFacingException("Keep the edit request under 32,000 characters.")
        val files = state.value.selected.toList()
        ReviewPolicy.validateContext(files)
        val snapshot = files.first().snapshot
        if (github.head(state.value.settings.githubToken, snapshot.repo, snapshot.branch) != snapshot.headSha)
            throw UserFacingException("The base branch changed. Refresh the repository and reselect files before proposing edits.")
        val output = StringBuilder()
        ai.stream(state.value.settings, Prompts.proposal(instruction.trim(), files)) { output.append(it) }
        val proposal = ReviewPolicy.parse(output.toString(), files)
        mutable.update { it.copy(draft = ReviewDraft(snapshot, files, proposal), published = null, page = Page.Review) }
    }
    fun publish(branch: String, message: String) = task("Publishing reviewed commit", false) {
        val draft = state.value.draft ?: throw UserFacingException("Generate and review a proposal first.")
        val result = github.publish(state.value.settings.githubToken, draft, branch.trim(), message)
        mutable.update { it.copy(published = result, draft = null,
            notice = if (result.baseAdvanced) "Branch created. The base may have advanced during publication; compare it on GitHub before merging." else "Reviewed changes committed to a new branch.") }
    }
    fun discardDraft() { if (state.value.busy == null) mutable.update { it.copy(draft = null, page = Page.Chat) } }
}
