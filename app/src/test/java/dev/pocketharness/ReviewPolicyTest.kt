package dev.pocketharness

import dev.pocketharness.data.*
import dev.pocketharness.network.ProviderValidation
import dev.pocketharness.network.Prompts
import dev.pocketharness.review.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

fun fixtureFile(path: String = "src/main.kt", content: String = "fun old() = 1\n", mode: String = "100755") = ContextFile(
    RepoSnapshot(Repo("owner", "repo", "main"), "main", "base-sha", "base-tree"), path, "blob-sha", mode, content)

class ReviewPolicyTest {
    @Test fun acceptsExactSelectedPathAndPreservesReplacementBytes() {
        val file = fixtureFile(content = "old\r\n")
        val edit = ProposedEdit(file.path, "new\r\n")
        val proposal = ReviewPolicy.parse(AppJson.encodeToString(Proposal("Change one line", listOf(edit))), listOf(file))
        assertEquals("new\r\n", proposal.edits.single().content)
    }
    @Test fun rejectsUnselectedPathsDuplicateEditsAndNoOps() {
        val file = fixtureFile()
        listOf(
            listOf(ProposedEdit("../secret", "bad")),
            listOf(ProposedEdit(file.path, "new"), ProposedEdit(file.path, "other")),
            listOf(ProposedEdit(file.path, file.content)),
            emptyList(),
            listOf(ProposedEdit(file.path, "binary\u0000")),
        ).forEach { edits -> assertThrows(UserFacingException::class.java) {
            ReviewPolicy.parse(AppJson.encodeToString(Proposal("Change", edits)), listOf(file))
        } }
    }
    @Test fun rejectsMixedSnapshotsAndOversizedContext() {
        val file = fixtureFile()
        assertThrows(UserFacingException::class.java) { ReviewPolicy.validateContext(listOf(file, fixtureFile("other").copy(snapshot = file.snapshot.copy(headSha = "different")))) }
        assertThrows(UserFacingException::class.java) { ReviewPolicy.validateContext(listOf(fixtureFile(content = "é".repeat(70_000)))) }
        assertThrows(UserFacingException::class.java) { ReviewPolicy.validateContext(listOf(fixtureFile(mode = "120000"))) }
    }
    @Test fun validatesBranchNamesBeforeAnyWrites() {
        listOf("main", "HEAD", "-branch", "/branch", "bad..name", "bad//name", "bad.lock", "folder/.hidden", "name ", "branch/", "name.", "a@{b").forEach {
            assertThrows("Invalid branch accepted: $it", UserFacingException::class.java) { ReviewPolicy.branch(it, "main") }
        }
        ReviewPolicy.branch("harness/fix-123", "main")
    }
    @Test fun endpointCannotContainCredentialsOrDowngradeTransport() {
        listOf("http://localhost", "https://key@example.com", "https://example.com?key=x", "https://example.com/#x").forEach {
            assertThrows(UserFacingException::class.java) { ProviderValidation.endpoint(it) }
        }
        assertEquals("https://example.com/v1", ProviderValidation.endpoint("https://example.com/v1/"))
    }
    @Test fun failedAssistantRepliesAreExcludedButUserContextSurvives() {
        val prompt = Prompts.chat(listOf(Message(role = "user", content = "Read this", files = listOf(fixtureFile())), Message(role = "assistant", content = "partial", status = "error")))
        assertEquals(2, prompt.size)
        assertTrue(prompt.last().content.contains("base-sha"))
        assertFalse(prompt.any { it.content == "partial" })
    }
    @Test fun diffShowsChangedLinesAndRetainsNearbyContext() {
        val diff = unifiedDiff("file.txt", "one\ntwo\nthree\n", "one\nchanged\nthree\n")
        assertTrue(diff.contains("-two\n+changed\n")); assertTrue(diff.contains(" one\n")); assertTrue(diff.contains(" three\n"))
    }
}
