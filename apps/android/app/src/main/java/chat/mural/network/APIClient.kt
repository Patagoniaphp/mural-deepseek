package chat.mural.network

import chat.mural.core.SourceLink
import chat.mural.core.ProviderFailureKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

data class APIUsage(val input: Int = 0, val output: Int = 0, val searches: Int = 0)
data class APIResult(val text: String, val sources: List<SourceLink>, val usage: APIUsage)

class APIClient private constructor(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = API_BASE_URL,
) : TeachingClient {
    constructor(credentials: CredentialStore) : this(credentials::read)

    internal constructor(key: String?, client: OkHttpClient, baseUrl: HttpUrl) :
        this({ key }, client, baseUrl)

    internal suspend fun post(path: String, body: JsonObject): JsonObject {
        if (path != "chat/completions") {
            throw APIException.InvalidResponse
        }
        val key = readCredential() ?: throw APIException.MissingKey
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $key")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // Parse on OkHttp's worker while the continuation remains cancellable.
        // Cancellation closes a response even if the peer stalls halfway through its body.
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (it.code !in 200..299) {
                                val errorCode = runCatching {
                                    val payload = it.peekBody(16_385).string()
                                    if (payload.toByteArray(Charsets.UTF_8).size > 16_384) null else
                                        (JSON.parseToJsonElement(payload).jsonObject["error"] as? JsonObject)
                                            ?.get("code")?.jsonPrimitive?.contentOrNull
                                }.getOrNull()
                                throw APIException.Http(it.code, errorCode, it.header("x-request-id"))
                            }
                            val payload = it.readBoundedBody()
                            try { JSON.parseToJsonElement(payload).jsonObject }
                            catch (_: Exception) { throw APIException.InvalidResponse }
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult {
        if (search) throw APIException.SearchUnavailable
        return complete(instructions, buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", input) })
        }, schema)
    }

    /** Voice turns use the same text-only endpoint, with explicit conversation roles. */
    internal suspend fun converse(instructions: String, messages: kotlinx.serialization.json.JsonArray): APIResult =
        complete(instructions, messages, null)

    private suspend fun complete(
        instructions: String,
        messages: kotlinx.serialization.json.JsonArray,
        schema: JsonObject?,
    ): APIResult {
        val system = if (schema == null) instructions else
            instructions + "\nReturn only a JSON object matching this JSON schema:\n" + schema.toString()
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                messages.forEach { add(it) }
            })
            put("stream", false)
            put("max_tokens", if (schema == null) 1_400 else 2_200)
            if (schema != null) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }
        val result = decodeTeachingResponse(post("chat/completions", body))
        // JSON mode guarantees syntax, not the assessment schema used by the learning engine.
        if (schema != null) {
            val value = try { JSON.parseToJsonElement(result.text) }
                catch (_: Exception) { throw APIException.InvalidResponse }
            if (!matchesTeachingSchema(value, schema)) throw APIException.InvalidResponse
        }
        return result
    }

    private fun Response.readBoundedBody(): String {
        val responseBody = body ?: throw APIException.InvalidResponse
        if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw APIException.InvalidResponse
        val source = responseBody.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val count = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - total))
            if (count == -1L) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw APIException.InvalidResponse
        }
        return buffer.readString(Charsets.UTF_8)
    }

    sealed class APIException(message: String, cause: Throwable? = null) : IOException(message, cause) {
        data object SearchUnavailable : APIException("Web search is not available with DeepSeek.")
        data object MissingKey : APIException("Add your DeepSeek key in Settings to begin.")
        data object InvalidResponse : APIException("DeepSeek returned an incomplete response. Please try again.")
        data object Incomplete : APIException("DeepSeek returned an incomplete response. Please try again.")
        data object Refused : APIException("Mural couldn't complete that request. Try a different topic.")
        class Http(val status: Int, code: String? = null, reference: String? = null) : APIException(messageFor(status)) {
            val code = ProviderFailureKind.safeCode(code)
            val reference = ProviderFailureKind.safeReference(reference)
            val kind get() = if (status == 402) ProviderFailureKind.quota else ProviderFailureKind.classify(status, code)
        }

        companion object {
            private fun messageFor(status: Int): String = when (status) {
                401 -> "Your DeepSeek key wasn't accepted. Check it in Settings."
                403, 404 -> "This API key may not have access to the requested model. Check your DeepSeek project."
                402 -> "Your DeepSeek account has insufficient balance. Check your API billing."
                429 -> "DeepSeek's usage or rate limit was reached. Check your project billing and limits."
                else -> "DeepSeek couldn't complete the request (HTTP $status). Please try again."
            }
        }
    }

    companion object {
        private val API_BASE_URL = HttpUrl.Builder()
            .scheme("https")
            .host("api.deepseek.com")
            .addPathSegment("")
            .build()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()


    }
}
