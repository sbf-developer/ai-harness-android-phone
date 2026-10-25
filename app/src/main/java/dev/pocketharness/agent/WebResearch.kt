package dev.pocketharness.agent

import dev.pocketharness.data.UserFacingException
import dev.pocketharness.network.Http
import dev.pocketharness.network.HttpFailure
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.UnknownHostException

object PublicWeb {
    fun url(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw UserFacingException("Enter a valid public HTTPS URL.")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443)
            throw UserFacingException("Web research supports public HTTPS URLs on port 443 without credentials.")
        if (url.host.lowercase() in setOf("localhost", "localhost.localdomain") || url.host.endsWith(".local")) throw UserFacingException("Local/private network addresses are not web research targets.")
        if ((url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) && !allowed(InetAddress.getByName(url.host)))
            throw UserFacingException("Private/reserved network targets are disabled for web tools.")
        return url
    }
    fun allowed(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return if (bytes.size == 4) !(bytes[0] == 0 || bytes[0] == 127 || bytes[0] >= 224 || (bytes[0] == 100 && bytes[1] in 64..127) || (bytes[0] == 192 && bytes[1] == 0))
        else !(bytes[0] and 0xfe == 0xfc || bytes.all { it == 0 })
    }
    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !allowed(it) }) throw UnknownHostException("Private/reserved network targets are disabled for web tools")
        }
    }
}

class WebResearch(
    private val fetchHttp: Http = Http(Http.defaultClient().newBuilder().dns(PublicWeb.dns).build()),
    private val searchHttp: Http = Http(),
    private val searchRoot: String = "https://api.search.brave.com/res/v1/web/search",
) {
    suspend fun search(query: String, key: String): ToolResult {
        if (key.isBlank()) return ToolResult(error = "General search is not configured. Add a separate Brave Search API key in Settings. URL fetching is available without it; do not claim to have searched.")
        if (query.length !in 1..300) return ToolResult(error = "Search query must have 1–300 characters.")
        val url = searchRoot.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("q", query).addQueryParameter("count", "5").build()
        val response = searchHttp.json(Request.Builder().url(url).header("X-Subscription-Token", key).header("Accept", "application/json").build())
        val results = response["web"]?.jsonObject?.get("results")?.jsonArray.orEmpty().take(5).map { item ->
            val obj = item.jsonObject
            buildJsonObject {
                put("title", Jsoup.parse(obj.text("title")).text().take(300)); put("url", obj.text("url").take(2000))
                put("snippet", Jsoup.parse(obj.text("description")).text().take(1500))
            }
        }
        return ToolResult(stdout = if (results.isEmpty()) "No results returned by Brave Search." else "Brave Search returned ${results.size} results.", exitCode = 0,
            data = buildJsonObject { put("backend", "Brave Search API"); put("query", query); put("results", JsonArray(results)) })
    }
    suspend fun fetch(value: String): ToolResult {
        var url = PublicWeb.url(value)
        repeat(6) {
            var next: HttpUrl? = null
            val outcome = fetchHttp.read(Request.Builder().url(url).header("User-Agent", "PocketHarness/0.2 research").header("Accept", "text/html,text/plain,application/json,application/xhtml+xml").build(), acceptRedirects = true) { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: throw UserFacingException("Redirect had no destination.")
                    next = PublicWeb.url(url.resolve(location)?.toString() ?: throw UserFacingException("Invalid redirect URL."))
                    return@read null
                }
                val type = response.body?.contentType()?.toString().orEmpty()
                if (!(type.startsWith("text/") || type.startsWith("application/json") || type.startsWith("application/xhtml+xml")))
                    throw UserFacingException("This URL is not a supported text/webpage response. PDFs, media, and JavaScript-only pages need another reader.")
                val source = response.body?.source() ?: throw UserFacingException("The webpage returned no content.")
                val limit = 1024L * 1024
                source.request(limit + 1)
                if (source.buffer.size > limit) throw UserFacingException("Webpage exceeded the 1 MiB download limit.")
                val text = source.readString(response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                val doc = Jsoup.parse(text, url.toString())
                doc.select("script,style,noscript,svg,nav,footer,header,form").remove()
                val content = if (type.contains("html")) (doc.selectFirst("main,article") ?: doc.body()).wholeText() else text
                val clean = content.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
                ToolResult(stdout = clean.take(MAX_TOOL_TEXT), exitCode = 0, truncated = clean.length > MAX_TOOL_TEXT,
                    data = buildJsonObject { put("url", url.toString()); put("title", doc.title().take(300)); put("source", "Fetched URL, not search"); put("characters", clean.length) })
            }
            if (outcome != null) return outcome
            url = next ?: throw UserFacingException("Invalid redirect.")
        }
        throw UserFacingException("Too many redirects.")
    }
}
