package dev.pocketharness.network

import dev.pocketharness.agent.*
import dev.pocketharness.data.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Builds complete function calls from independently interleaved SSE argument fragments. */
class ToolStreamAccumulator {
    private data class Parts(var id: String = "", var name: String = "", var arguments: String = "")
    private val parts = sortedMapOf<Int, Parts>()
    val content = StringBuilder()
    var finishReason: String? = null
        private set
    fun accept(payload: JsonObject): String {
        if (payload["error"] != null) throw UserFacingException("The provider reported an agent stream error. No partial tool call was executed.")
        val choice = payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
        val delta = choice["delta"]?.jsonObject
        val text = delta?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        content.append(text)
        if (content.length > 100000) throw UserFacingException("The agent reply exceeded its text limit.")
        delta?.get("tool_calls")?.jsonArray?.forEach { entry ->
            val call = entry.jsonObject
            val index = call["index"]?.jsonPrimitive?.intOrNull ?: throw UserFacingException("Tool call index is missing.")
            if (index !in 0..7) throw UserFacingException("Too many parallel tool calls.")
            val p = parts.getOrPut(index) { Parts() }
            call["id"]?.jsonPrimitive?.contentOrNull?.let { p.id += it }
            call["type"]?.jsonPrimitive?.contentOrNull?.let { if (it != "function") throw UserFacingException("Only function tools are supported.") }
            call["function"]?.jsonObject?.let { function ->
                p.name += function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                p.arguments += function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            if (p.arguments.length > 100000 || p.id.length > 200 || p.name.length > 80) throw UserFacingException("The tool call exceeded its size limit.")
        }
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
        return text
    }
    fun finish(): AgentReply {
        val reason = finishReason ?: throw UserFacingException("The provider stream ended without confirming a complete reply. Partial tools were not executed.")
        if (reason !in setOf("stop", "tool_calls")) throw UserFacingException("The agent response ended early. No partial tool call was executed. Try a smaller task.")
        val calls = parts.values.map { p ->
            if (p.id.isBlank() || p.name.isBlank() || p.arguments.isBlank()) throw UserFacingException("The provider returned an incomplete tool call.")
            if (!p.name.matches(Regex("[A-Za-z0-9_]{1,64}"))) throw UserFacingException("Invalid function name.")
            ToolCall(p.id, p.name, p.arguments).also { AgentPolicy.arguments(it) }
        }
        if (calls.map { it.id }.distinct().size != calls.size || (calls.isNotEmpty() && reason != "tool_calls") || (reason == "tool_calls" && calls.isEmpty()))
            throw UserFacingException("The provider returned inconsistent tool-call metadata.")
        if (calls.isEmpty() && content.isBlank()) throw UserFacingException("The model returned no text or tools. Choose a model supporting Chat Completions function tools.")
        return AgentReply(content.toString(), calls, reason)
    }
}

fun agentWireMessage(message: AgentMessage): JsonObject = buildJsonObject {
    put("role", message.role); put("content", message.content)
    message.toolCallId?.let { put("tool_call_id", it) }
    if (message.calls.isNotEmpty()) putJsonArray("tool_calls") { message.calls.forEach { call -> add(buildJsonObject {
        put("id", call.id); put("type", "function"); putJsonObject("function") { put("name", call.name); put("arguments", call.arguments) }
    }) } }
}

class AgentProviderClient(private val http: Http = Http()) : AgentProvider {
    override suspend fun reply(settings: Settings, messages: List<AgentMessage>, tools: JsonArray, onDelta: suspend (String) -> Unit): AgentReply {
        val endpoint = ProviderValidation.endpoint(settings.endpoint)
        if (settings.apiKey.isBlank()) throw UserFacingException("Add your AI provider key in Settings first.")
        val body = buildJsonObject {
            put("model", settings.model); put("stream", true); put("max_tokens", 8192)
            if (endpoint.toHttpUrl().host == "api.deepseek.com") putJsonObject("thinking") { put("type", "disabled") }
            put("messages", JsonArray(messages.map(::agentWireMessage))); put("tools", tools); put("tool_choice", "auto")
        }
        val request = Request.Builder().url("$endpoint/chat/completions").header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "text/event-stream").post(jsonBody(body)).build()
        try {
            return http.read(request) { response ->
                val source = response.body?.source() ?: throw UserFacingException("No agent response received.")
                val accumulated = ToolStreamAccumulator(); val event = StringBuilder(); var done = false
                suspend fun dispatch() {
                    if (event.isEmpty()) return
                    val data = event.toString().trimEnd('\n'); event.clear()
                    if (data == "[DONE]") { done = true; return }
                    val delta = accumulated.accept(AppJson.parseToJsonElement(data).jsonObject)
                    if (delta.isNotEmpty()) onDelta(delta)
                }
                while (!done && !source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = try { source.readUtf8LineStrict(512L * 1024) } catch (_: java.io.EOFException) {
                        if (source.buffer.size > 512 * 1024) throw UserFacingException("Agent stream line is too large.")
                        source.readUtf8()
                    }
                    if (line.isEmpty()) dispatch() else if (line.startsWith("data:")) {
                        event.append(line.substring(5).removePrefix(" ")).append('\n')
                        if (event.length > 512 * 1024) throw UserFacingException("Agent stream event is too large.")
                    }
                }
                dispatch(); accumulated.finish()
            }
        } catch (error: HttpFailure) {
            if (error.code in setOf(400, 404, 422)) throw UserFacingException("This endpoint/model rejected the agent request. Use a Chat Completions model supporting streamed function tools (for example the configured DeepSeek model). Basic chat remains available.")
            throw error
        }
    }
}
