package dev.pocketharness

import dev.pocketharness.agent.*
import dev.pocketharness.data.*
import dev.pocketharness.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AgentNetworkTest {
    private lateinit var server: MockWebServer
    private lateinit var http: Http
    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("public.example").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        http = Http(Http.defaultClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
    }
    @After fun close() { server.shutdown() }
    private fun localUrl(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()
    @Test fun actualStreamingRequestContainsFunctionSchemasAndReturnsCompleteCalls() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"real-id\",\"type\":\"function\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{}\"}}]}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"))
        val reply = AgentProviderClient(http).reply(Settings(endpoint = localUrl("/v1"), apiKey = "unit-test"), listOf(AgentMessage("user", "inspect")), ToolCatalog.tools(false)) {}
        assertEquals("real-id", reply.calls.single().id)
        val request = server.takeRequest()
        val body = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("auto", body.text("tool_choice")); assertTrue(body["tools"]!!.jsonArray.size >= 8)
        assertEquals("Bearer unit-test", request.getHeader("Authorization")); assertFalse(body.containsKey("thinking"))
    }
    @Test fun unsupportedModelsAndIncompleteTransportFailWithoutToolResults() = runBlocking {
        val client = AgentProviderClient(http)
        val settings = Settings(endpoint = localUrl("/"), apiKey = "unit-test")
        server.enqueue(MockResponse().setResponseCode(422).setBody("sensitive server message"))
        try { client.reply(settings, emptyList(), ToolCatalog.tools(false)) {}; fail() } catch (error: UserFacingException) {
            assertTrue(error.message!!.contains("streamed function tools")); assertFalse(error.message!!.contains("sensitive"))
        }
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
        try { client.reply(settings, emptyList(), ToolCatalog.tools(false)) {}; fail() } catch (_: UserFacingException) { }
    }
    @Test fun cancelledAgentStreamClosesBlockedSocket() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { AgentProviderClient(http).reply(Settings(endpoint = localUrl("/"), apiKey = "unit-test"), emptyList(), ToolCatalog.tools(false)) {} }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withTimeout(3000) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
    }
    @Test fun realSearchRequestUsesSeparateBraveHeaderAndReturnsSourceUrls() = runBlocking {
        val web = WebResearch(http, http, localUrl("/search"))
        assertFalse(web.search("topic", "").ok); assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"web":{"results":[{"title":"<b>Evidence</b>","url":"https://public.example/article","description":"Actual snippet"}]}}"""))
        val result = web.search("topic words", "test-search-key")
        val request = server.takeRequest()
        assertEquals("test-search-key", request.getHeader("X-Subscription-Token")); assertNull(request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("q=topic%20words")); assertTrue(result.data.toString().contains("https://public.example/article"))
    }
    @Test fun fetchExtractsRealTextAndBlocksPrivateRedirect() = runBlocking {
        // Only this test client rewrites the validated public URL to the loopback TLS fixture.
        val localTransport = Http(http.client.newBuilder().dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().port(server.port).build()).build()) }.build())
        val web = WebResearch(localTransport, http)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<title>Source title</title><nav>ignore</nav><main>Actual evidence<script>bad()</script></main>"))
        val result = web.fetch("https://public.example/page")
        assertTrue(result.ok); assertTrue(result.stdout.contains("Actual evidence")); assertFalse(result.stdout.contains("bad()")); assertFalse(result.stdout.contains("ignore"))
        assertEquals("https://public.example/page", result.data?.text("url"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://127.0.0.1/private"))
        try { web.fetch("https://public.example/redirect"); fail() } catch (_: UserFacingException) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun publicWebRejectsCredentialsPrivateIpsAndLocalTargets() {
        for (url in listOf("http://example.com", "https://localhost/a", "https://127.0.0.1/", "https://192.168.1.2", "https://[::1]/", "https://user:pass@example.com", "https://example.com:8080"))
            assertThrows(UserFacingException::class.java) { PublicWeb.url(url) }
        assertFalse(PublicWeb.allowed(InetAddress.getByName("100.64.0.1")))
        assertFalse(PublicWeb.allowed(InetAddress.getByName("fc00::1")))
        assertTrue(PublicWeb.allowed(InetAddress.getByName("1.1.1.1")))
    }
}
