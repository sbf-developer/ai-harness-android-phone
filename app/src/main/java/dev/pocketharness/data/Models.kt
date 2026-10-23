package dev.pocketharness.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun newId(): String = UUID.randomUUID().toString()

@Serializable
data class Settings(
    val endpoint: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val apiKey: String = "",
    val githubToken: String = "",
)

@Serializable
data class Repo(val owner: String, val name: String, val defaultBranch: String, val private: Boolean = false) {
    val fullName get() = "$owner/$name"
}

@Serializable
data class RepoSnapshot(val repo: Repo, val branch: String, val headSha: String, val treeSha: String)

@Serializable
data class ContextFile(val snapshot: RepoSnapshot, val path: String, val sha: String, val mode: String, val content: String)

@Serializable
data class Message(
    val id: String = newId(),
    val role: String,
    val content: String,
    val files: List<ContextFile> = emptyList(),
    val status: String = "complete",
)

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "New conversation",
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
)

data class TreeEntry(val name: String, val path: String, val sha: String, val type: String, val mode: String, val size: Long)
data class Directory(val path: String, val treeSha: String)

@Serializable
data class ProposedEdit(val path: String, val content: String)
@Serializable
data class Proposal(val summary: String, val edits: List<ProposedEdit>)

data class ReviewDraft(val snapshot: RepoSnapshot, val originals: List<ContextFile>, val proposal: Proposal)
data class PublishResult(val branch: String, val commitSha: String, val url: String, val baseAdvanced: Boolean)

class UserFacingException(message: String) : Exception(message)
