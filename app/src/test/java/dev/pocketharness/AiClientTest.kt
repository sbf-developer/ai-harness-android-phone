package dev.pocketharness

import dev.pocketharness.data.*
import dev.pocketharness.network.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var ai: AiClient
    private lateinit var settings: Settings
    @Before fun setup() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val client = Http.defaultClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        ai = AiClient(Http(client))
        settings = Settings(endpoint = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), model = "test-model", apiKey = "local-test-key")
    }
    @After fun close() { server.shutdown() }
    @Test fun parsesSseCommentsUsageAndContentWithoutExposingReasoning() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\" 世界\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"total_tokens\":4}}\n\ndata: [DONE]\n\n"))
        val result = StringBuilder()
        ai.stream(settings, listOf(AiMessage("user", "hi"))) { result.append(it) }
        assertEquals("Hello 世界", result.toString())
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer local-test-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"stream\":true"))
    }
    @Test fun rejectsTruncatedStreamButDeliversPartialText() = runBlocking {
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
        var partial = ""
        try { ai.stream(settings, listOf(AiMessage("user", "hi"))) { partial += it }; fail("Truncated stream was accepted") }
        catch (error: UserFacingException) { assertTrue(error.message!!.contains("disconnected")) }
        assertEquals("partial", partial)
    }
    @Test fun rejectsTokenLimitAndEmptyResponse() = runBlocking {
        for (event in listOf("{\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}", "[DONE]")) {
            server.enqueue(MockResponse().setBody("data: $event\n\n"))
            try { ai.stream(settings, listOf(AiMessage("user", "hi"))) {}; fail("Invalid completion accepted") }
            catch (_: UserFacingException) { }
        }
    }
    @Test fun tokenLimitPreservesTheFinalContentChunk() = runBlocking {
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"last words\"},\"finish_reason\":\"length\"}]}\n\n"))
        val output = StringBuilder()
        try { ai.stream(settings, listOf(AiMessage("user", "hello"))) { output.append(it) }; fail("Token limit accepted as complete") }
        catch (_: UserFacingException) { }
        assertEquals("last words", output.toString())
    }
    @Test fun fastDeepSeekExtensionDoesNotLeakIntoOtherProviderRequests() {
        val messages = listOf(AiMessage("user", "hello"))
        assertTrue(chatRequestBody(Settings(), messages).toString().contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertFalse(chatRequestBody(settings, messages).containsKey("thinking"))
    }
    @Test fun cancellationStopsAStalledNetworkCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { ai.stream(settings, listOf(AiMessage("user", "hi"))) {} }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
        withTimeout(2500) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
    @Test fun errorsAreRedactedAndRedirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("SECRET credential reflected by server"))
        try { ai.stream(settings, listOf(AiMessage("user", "hi"))) {}; fail("401 accepted") }
        catch (error: HttpFailure) { assertFalse(friendlyError(error).contains("SECRET")); assertEquals(401, error.code) }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/steal")))
        try { ai.stream(settings, listOf(AiMessage("user", "hi"))) {}; fail("Redirect followed") }
        catch (error: HttpFailure) { assertEquals(302, error.code) }
        assertEquals(2, server.requestCount)
    }
}
