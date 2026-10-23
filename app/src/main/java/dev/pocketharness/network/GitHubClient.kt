package dev.pocketharness.network

import dev.pocketharness.data.*
import dev.pocketharness.review.ReviewPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

class GitHubClient(private val http: Http = Http(), private val apiRoot: String = "https://api.github.com") {
    private fun request(token: String, parts: List<String>, method: String = "GET", body: JsonObject? = null, query: Map<String, String> = emptyMap()): Request {
        if (token.isBlank()) throw UserFacingException("Add your GitHub token in Settings first.")
        val url = apiRoot.toHttpUrl().newBuilder().apply {
            parts.forEach { addPathSegment(it) }; query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.let(::jsonBody)).build()
    }
    private fun path(repo: Repo, vararg tail: String) = listOf("repos", repo.owner, repo.name) + tail
    suspend fun repositories(token: String, page: Int = 1): List<Repo> =
        http.jsonValue(request(token, listOf("user", "repos"), query = mapOf("per_page" to "50", "page" to page.toString(), "sort" to "updated")))
            .jsonArray.map { element -> element.jsonObject.let {
                Repo(it.getValue("owner").jsonObject.string("login"), it.string("name"), it.string("default_branch"), it["private"]?.jsonPrimitive?.booleanOrNull ?: false)
            } }

    suspend fun head(token: String, repo: Repo, branch: String): String {
        val response = http.json(request(token, path(repo, "git", "ref", "heads") + branch.split('/')))
        return response.getValue("object").jsonObject.string("sha")
    }
    suspend fun snapshot(token: String, repo: Repo, branch: String): RepoSnapshot {
        val sha = head(token, repo, branch)
        val commit = http.json(request(token, path(repo, "git", "commits", sha)))
        return RepoSnapshot(repo, branch, sha, commit.getValue("tree").jsonObject.string("sha"))
    }
    suspend fun entries(token: String, snapshot: RepoSnapshot, directory: Directory): List<TreeEntry> {
        val tree = http.json(request(token, path(snapshot.repo, "git", "trees", directory.treeSha)))
        if (tree["truncated"]?.jsonPrimitive?.booleanOrNull == true)
            throw UserFacingException("This directory is too large for GitHub to return safely.")
        return tree.getValue("tree").jsonArray.map { it.jsonObject }.map {
            val name = it.string("path")
            TreeEntry(name, listOf(directory.path, name).filter(String::isNotEmpty).joinToString("/"), it.string("sha"), it.string("type"), it.string("mode"), it["size"]?.jsonPrimitive?.longOrNull ?: 0)
        }.sortedWith(compareBy<TreeEntry> { it.type != "tree" }.thenBy { it.name.lowercase() })
    }
    suspend fun file(token: String, snapshot: RepoSnapshot, entry: TreeEntry): ContextFile {
        if (entry.type != "blob" || entry.mode !in setOf("100644", "100755"))
            throw UserFacingException("Symlinks, submodules, and special files cannot be selected.")
        if (entry.size > ReviewPolicy.MAX_CONTEXT_BYTES) throw UserFacingException("Choose a text file smaller than 120 KiB.")
        val blob = http.json(request(token, path(snapshot.repo, "git", "blobs", entry.sha)))
        if (blob["size"]?.jsonPrimitive?.longOrNull?.let { it > ReviewPolicy.MAX_CONTEXT_BYTES } == true)
            throw UserFacingException("The file exceeds 120 KiB.")
        if (blob.string("encoding") != "base64") throw UserFacingException("Unsupported file encoding.")
        val bytes = Base64.getMimeDecoder().decode(blob.string("content"))
        if (bytes.size > ReviewPolicy.MAX_CONTEXT_BYTES) throw UserFacingException("The file exceeds 120 KiB.")
        val content = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw UserFacingException("Choose a UTF-8 text file.") }
        if (content.contains('\u0000')) throw UserFacingException("Binary files cannot be selected.")
        return ContextFile(snapshot, entry.path, entry.sha, entry.mode, content)
    }

    /** Called only from the explicit Review -> Commit action. Never updates an existing ref. */
    suspend fun publish(token: String, draft: ReviewDraft, branch: String, message: String): PublishResult = withContext(NonCancellable + Dispatchers.IO) {
        ReviewPolicy.validateContext(draft.originals)
        ReviewPolicy.parse(AppJson.encodeToString(Proposal.serializer(), draft.proposal), draft.originals)
        val snapshot = draft.snapshot
        if (draft.originals.any { it.snapshot != snapshot }) throw UserFacingException("The review base is inconsistent. Generate a new proposal.")
        ReviewPolicy.branch(branch, snapshot.branch)
        if (message.isBlank() || message.length > 500) throw UserFacingException("Enter a commit message of 1–500 characters.")
        ensureUnchanged(token, snapshot)
        // Ref existence is checked before creating objects; create-ref remains the authoritative race check.
        try {
            head(token, snapshot.repo, branch)
            throw UserFacingException("That branch already exists. Choose a new branch name.")
        } catch (error: HttpFailure) { if (error.code != 404) throw error }
        val originals = draft.originals.associateBy { it.path }
        val tree = http.json(request(token, path(snapshot.repo, "git", "trees"), "POST", buildJsonObject {
            put("base_tree", snapshot.treeSha)
            putJsonArray("tree") { draft.proposal.edits.forEach { edit -> add(buildJsonObject {
                put("path", edit.path); put("mode", originals.getValue(edit.path).mode); put("type", "blob"); put("content", edit.content)
            }) } }
        }))
        val commit = http.json(request(token, path(snapshot.repo, "git", "commits"), "POST", buildJsonObject {
            put("message", message.trim()); put("tree", tree.string("sha")); putJsonArray("parents") { add(snapshot.headSha) }
        }))
        val commitSha = commit.string("sha")
        ensureUnchanged(token, snapshot)
        try {
            http.json(request(token, path(snapshot.repo, "git", "refs"), "POST", buildJsonObject {
                put("ref", "refs/heads/$branch"); put("sha", commitSha)
            }))
        } catch (error: Exception) {
            // Recover a lost create-ref response without retrying a write or overwriting any branch.
            val recovered = try { head(token, snapshot.repo, branch) == commitSha } catch (_: Exception) { false }
            if (!recovered) {
                if (error is HttpFailure) throw error
                throw UserFacingException("Publication could not be confirmed. Check branch '$branch' on GitHub before retrying.")
            }
        }
        val advanced = try { head(token, snapshot.repo, snapshot.branch) != snapshot.headSha } catch (_: Exception) { true }
        PublishResult(branch, commitSha, "https://github.com/${snapshot.repo.fullName}/commit/$commitSha", advanced)
    }
    private suspend fun ensureUnchanged(token: String, snapshot: RepoSnapshot) {
        if (head(token, snapshot.repo, snapshot.branch) != snapshot.headSha)
            throw UserFacingException("The base branch changed during review. Refresh the repository, reselect files, and generate a new proposal. Nothing was published.")
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
