package dev.pocketharness

import dev.pocketharness.data.*
import dev.pocketharness.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GitHubClientTest {
    private lateinit var server: MockWebServer
    private lateinit var github: GitHubClient
    private val file = fixtureFile()
    private val draft = ReviewDraft(file.snapshot, listOf(file), Proposal("Improve function", listOf(ProposedEdit(file.path, "fun improved() = 2\n"))))
    @Before fun setup() { server = MockWebServer().apply { start() }; github = GitHubClient(Http(), server.url("/").toString()) }
    @After fun close() { server.shutdown() }
    private fun json(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    private fun head(sha: String = "base-sha") = json("{\"object\":{\"sha\":\"$sha\"}}")
    @Test fun changedBaseBlocksAllWrites() = runBlocking {
        head("new-base")
        try { github.publish("test-token", draft, "harness/change", "Improve function"); fail("Stale base published") }
        catch (error: UserFacingException) { assertTrue(error.message!!.contains("base branch changed")) }
        assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
    }
    @Test fun baseMovingDuringObjectCreationNeverCreatesBranch() = runBlocking {
        head(); json("{}", 404); json("{\"sha\":\"new-tree\"}"); json("{\"sha\":\"new-commit\"}"); head("moved")
        try { github.publish("test-token", draft, "harness/change", "Improve function"); fail("Base movement ignored") }
        catch (_: UserFacingException) { }
        val requests = (1..server.requestCount).map { server.takeRequest() }
        assertEquals(5, requests.size)
        assertFalse(requests.any { it.path!!.endsWith("/git/refs") })
        assertFalse(requests.any { it.method == "PATCH" })
    }
    @Test fun publishesAtomicTreeWithOriginalFileModeAndParentToNewBranchOnly() = runBlocking {
        head(); json("{}", 404); json("{\"sha\":\"new-tree\"}"); json("{\"sha\":\"new-commit\"}"); head()
        json("{\"ref\":\"refs/heads/harness/change\"}", 201); head()
        val result = github.publish("test-token", draft, "harness/change", "Improve function")
        assertEquals("new-commit", result.commitSha); assertFalse(result.baseAdvanced)
        val requests = (1..7).map { server.takeRequest() }
        val tree = AppJson.parseToJsonElement(requests[2].body.readUtf8()).jsonObject
        assertEquals("base-tree", tree["base_tree"]!!.jsonPrimitive.content)
        assertEquals("100755", tree["tree"]!!.jsonArray.single().jsonObject["mode"]!!.jsonPrimitive.content)
        val commit = AppJson.parseToJsonElement(requests[3].body.readUtf8()).jsonObject
        assertEquals("base-sha", commit["parents"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("POST", requests[5].method)
        assertTrue(requests[5].body.readUtf8().contains("refs/heads/harness/change"))
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer test-token" })
        assertFalse(requests.any { it.method == "PATCH" || it.method == "DELETE" })
    }
    @Test fun existingBranchIsNeverOverwritten() = runBlocking {
        head(); head("existing-commit")
        try { github.publish("test-token", draft, "existing", "Change"); fail("Existing branch overwritten") }
        catch (error: UserFacingException) { assertTrue(error.message!!.contains("already exists")) }
        assertEquals(2, server.requestCount)
    }
    @Test fun branchAdvancingAfterPublicationIsReported() = runBlocking {
        head(); json("{}", 404); json("{\"sha\":\"tree\"}"); json("{\"sha\":\"commit\"}"); head(); json("{}", 201); head("advanced")
        assertTrue(github.publish("test-token", draft, "harness/change", "Change").baseAdvanced)
    }
    @Test fun fileDecodingRejectsBinaryAndInvalidUtf8() = runBlocking {
        for (bytes in listOf(byteArrayOf(0, 1, 2), byteArrayOf(0xC3.toByte(), 0x28))) {
            val content = Base64.getEncoder().encodeToString(bytes)
            json("{\"size\":${bytes.size},\"encoding\":\"base64\",\"content\":\"$content\"}")
            try { github.file("test-token", file.snapshot, TreeEntry("main.kt", file.path, file.sha, "blob", "100644", bytes.size.toLong())); fail("Binary content accepted") }
            catch (_: UserFacingException) { }
        }
    }
    @Test fun lostCreateRefResponseIsRecoveredWithoutRetryingTheWrite() = runBlocking {
        head(); json("{}", 404); json("{\"sha\":\"tree\"}"); json("{\"sha\":\"commit\"}"); head()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        head("commit"); head()
        val result = github.publish("test-token", draft, "harness/change", "Change")
        assertEquals("commit", result.commitSha)
        val requests = (1..8).map { server.takeRequest() }
        assertEquals(1, requests.count { it.method == "POST" && it.path!!.endsWith("/git/refs") })
    }
}
