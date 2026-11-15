package dev.app8.engine.runtime

import dev.app8.engine.App8HttpRequest
import dev.app8.engine.core.vars.Values
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Sends one HTTP request (the `request` action). Tests swap it for a fake. */
internal fun interface HttpTransport {
    /** @throws Exception on a network failure (the action reports status 0). */
    suspend fun send(request: App8HttpRequest): HttpResponse
}

/** A raw response: [headers] have lower-case names (repeated headers joined with `, `). */
internal data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    /** The DSL `response` object: `{ status, body: <parsed JSON, else the string, null when empty>, headers }`. */
    fun toValue(): Map<String, Any?> = linkedMapOf(
        "status" to status.toLong(),
        "body" to parseBody(body),
        "headers" to LinkedHashMap<String, Any?>(headers),
    )

    companion object {
        private val json = Json

        /** A request that never got a response (network failure, rejected by the host): status 0. */
        fun failure(): Map<String, Any?> = linkedMapOf("status" to 0L, "body" to null, "headers" to LinkedHashMap<String, Any?>())

        fun parseBody(text: String): Any? {
            if (text.isBlank()) return null
            return try {
                Values.fromJson(json.parseToJsonElement(text)).takeUnless { it === Values.Null }
            } catch (_: Exception) {
                text
            }
        }
    }
}

/** OkHttp (already on the classpath through the image loader), 30 s call timeout. */
internal class OkHttpTransport : HttpTransport {
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

    override suspend fun send(request: App8HttpRequest): HttpResponse = runInterruptible(Dispatchers.IO) {
        val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/json"
        // Bytes, so OkHttp does not append a charset to the host's Content-Type.
        val body = request.body?.encodeToByteArray()?.toRequestBody(contentType.toMediaTypeOrNull())
        val builder = Request.Builder().url(request.url).method(request.method, body)
        for ((name, value) in request.headers) builder.header(name, value)
        client.newCall(builder.build()).execute().use { response ->
            val headers = LinkedHashMap<String, String>()
            for (name in response.headers.names()) {
                headers[name.lowercase(Locale.ROOT)] = response.headers.values(name).joinToString(", ")
            }
            HttpResponse(response.code, headers, response.body?.string().orEmpty())
        }
    }
}
