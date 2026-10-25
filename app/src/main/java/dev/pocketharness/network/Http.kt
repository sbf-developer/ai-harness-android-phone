package dev.pocketharness.network

import dev.pocketharness.data.AppJson
import dev.pocketharness.data.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

class HttpFailure(val code: Int) : Exception("HTTP $code")

class Http(val client: OkHttpClient = defaultClient()) {
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }

    suspend fun <T> read(request: Request, acceptRedirects: Boolean = false, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        // Cancel blocking socket reads immediately, including after headers have arrived.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful && !(acceptRedirects && response.code in setOf(301, 302, 303, 307, 308))) throw HttpFailure(response.code)
                    block(response)
                }
            }
        } catch (error: Exception) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActiveCompat()) throw CancellationException("Cancelled")
            throw error
        } finally { watcher.cancel() }
    }

    suspend fun jsonValue(request: Request): JsonElement = read(request) { response ->
        val source = response.body?.source() ?: throw UserFacingException("The server returned an empty response.")
        val limit = 3L * 1024 * 1024
        source.request(limit + 1)
        if (source.buffer.size > limit) throw UserFacingException("The server response is too large.")
        AppJson.parseToJsonElement(source.readUtf8())
    }
    suspend fun json(request: Request): JsonObject = jsonValue(request) as? JsonObject
        ?: throw UserFacingException("The server returned an invalid response.")
}

private fun kotlin.coroutines.CoroutineContext.isActiveCompat() = this[kotlinx.coroutines.Job]?.isActive != false
fun jsonBody(value: JsonElement) = value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

fun friendlyError(error: Throwable): String = when (error) {
    is UserFacingException -> error.message ?: "The request could not be completed."
    is HttpFailure -> when (error.code) {
        401 -> "Authentication failed. Check the saved key or GitHub token."
        403 -> "Access denied or rate limited. Check token permissions and API quota."
        404 -> "Not found. Check the repository, branch, endpoint, and token access."
        409 -> "The request conflicts with the current state. For GitHub, refresh the base and use a new branch name."
        422 -> "The service could not accept this request (HTTP 422). Check the model, request size, or GitHub branch and permissions."
        429 -> "Rate limit reached. Wait a moment before trying again."
        in 500..599 -> "The service is temporarily unavailable. Try again shortly."
        else -> "The service rejected the request (HTTP ${error.code}). Check your settings."
    }
    is java.net.SocketTimeoutException -> "The connection timed out. Try again."
    is java.io.IOException -> "Could not reach the service. Check your connection and try again."
    else -> "The response could not be read. Check your settings and try again."
}
