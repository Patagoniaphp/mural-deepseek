package chat.mural.network

import chat.mural.core.ProviderFailureKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class APIClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: APIClient
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = APIClient("sk-fake-test-only", OkHttpClient.Builder().followRedirects(false).build(), server.url("/"))
    }
    @After fun teardown() { server.shutdown() }
    private fun response(text: String = "Hola", finish: String = "stop") = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish)
            put("message", buildJsonObject { put("role", "assistant"); put("content", text); put("reasoning_content", "private reasoning") })
        }) })
        put("usage", buildJsonObject { put("prompt_tokens", 12); put("completion_tokens", 7) })
    }.toString()
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":5}},"required":["level"],"additionalProperties":false}""").jsonObject

    @Test fun sendsChatCompletionsContractAndParsesDeepSeekUsage() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val result = api.respond("policy", "hello")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer sk-fake-test-only", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "messages", "stream", "max_tokens"), body.keys)
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertFalse(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(1400, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("policy", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("Hola", result.text)
        assertEquals(APIUsage(12, 7, 0), result.usage); assertTrue(result.sources.isEmpty())
    }
    @Test fun jsonModeIncludesSchemaInSystemMessageAndValidatesResult() = runBlocking {
        server.enqueue(MockResponse().setBody(response("""{"level":2}""")))
        assertEquals("""{"level":2}""", api.respond("Assess", "Answer", schema).text)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(2200, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains(schema.toString()))
        for (bad in listOf("not JSON", "{}", "[]", """{"level":6}""", """{"level":"2"}""", """{"level":2,"extra":true}""")) {
            server.enqueue(MockResponse().setBody(response(bad)))
            try { api.respond("Assess", "Answer", schema); fail("accepted invalid schema: $bad") }
            catch (_: APIClient.APIException.InvalidResponse) { }
        }
    }
    @Test fun voiceConversationPreservesRoleOrder() = runBlocking {
        val history = VoiceTurnHistory()
        history.append("user", "Hola"); history.append("assistant", "Hola, ¿cómo estás?"); history.append("user", "Bien")
        server.enqueue(MockResponse().setBody(response()))
        api.converse("Spanish only", history.messages())
        val messages = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }
    @Test fun unsupportedSearchAndLegacyEndpointsNeverSendRequests() = runBlocking {
        try { api.respond("p", "q", search = true); fail("accepted search") }
        catch (_: APIClient.APIException.SearchUnavailable) { }
        for (path in listOf("responses", "live/sessions", "https://example.com", "../other", "/chat/completions")) {
            try { api.post(path, buildJsonObject {}); fail("accepted path: $path") }
            catch (_: APIClient.APIException.InvalidResponse) { }
        }
        val missing = APIClient(null, OkHttpClient(), server.url("/"))
        try { missing.respond("p", "q"); fail("accepted missing key") }
        catch (_: APIClient.APIException.MissingKey) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun incompleteRefusedOrMalformedResponsesNeverBecomeReplies() = runBlocking {
        val malformed = listOf("{}", "not json", """{"choices":[]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":123}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"reasoning_content":"secret"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"user","content":"bad"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"answer","refusal":"No"}}]}""",
            response(" "), response(finish = "length"), response(finish = "content_filter"), response(finish = "tool_calls"))
        for (body in malformed) {
            server.enqueue(MockResponse().setBody(body))
            try { api.respond("p", "q"); fail("accepted incomplete response") }
            catch (_: APIClient.APIException) { }
        }
    }
    @Test fun optionalAndOutOfRangeUsageIsSafe() {
        val body = Json.parseToJsonElement(response()).jsonObject.toMutableMap()
        body.remove("usage")
        assertEquals(APIUsage(), decodeTeachingResponse(JsonObject(body)).usage)
        body["usage"] = buildJsonObject { put("prompt_tokens", -5); put("completion_tokens", 9_000_000_000L) }
        assertEquals(APIUsage(0, 1_000_000_000), decodeTeachingResponse(JsonObject(body)).usage)
    }
    @Test fun providerErrorsAreSanitizedAndInsufficientBalanceIsQuota() = runBlocking {
        for (status in listOf(401, 402, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("x-request-id", "req_support")
                .setBody("""{"error":{"code":"unknown","message":"private billing data"}}"""))
            try { api.respond("p", "q"); fail("accepted error") }
            catch (error: APIClient.APIException.Http) {
                assertEquals(status, error.status); assertEquals("req_support", error.reference)
                assertFalse(error.message.orEmpty().contains("private"))
                assertNull(error.code)
                if (status == 402) assertEquals(ProviderFailureKind.quota, error.kind)
            }
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun rejectsRedirectWithoutFollowingOrLeakingCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        try { api.respond("p", "q"); fail("accepted redirect") }
        catch (error: APIClient.APIException.Http) { assertEquals(302, error.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun cancellationStopsAResponseThatStallsMidBody() = runBlocking {
        server.enqueue(MockResponse().setBody(response()).throttleBody(1, 1, TimeUnit.SECONDS))
        val job = launch { api.respond("p", "q") }
        delay(200)
        withTimeout(1500) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
    @Test fun responsesAreBoundedWithOrWithoutContentLength() = runBlocking {
        for (chunked in listOf(false, true)) {
            val body = " ".repeat(1_048_577)
            server.enqueue(if (chunked) MockResponse().setChunkedBody(body, 8192) else MockResponse().setBody(body))
            try { api.respond("p", "q"); fail("accepted oversized response") }
            catch (_: APIClient.APIException.InvalidResponse) { }
        }
    }
}
