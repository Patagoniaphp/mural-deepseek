package chat.mural.network

import kotlinx.serialization.json.*

/** Decode only a completed assistant message; never display reasoning or partial JSON. */
internal fun decodeTeachingResponse(response: JsonObject): APIResult {
    val choice = (response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        ?: throw APIClient.APIException.InvalidResponse
    val message = choice["message"] as? JsonObject ?: throw APIClient.APIException.InvalidResponse
    if (choice.text("finish_reason") == "content_filter" || !message.text("refusal").isNullOrBlank()) {
        throw APIClient.APIException.Refused
    }
    if (choice.text("finish_reason") != "stop") throw APIClient.APIException.Incomplete
    if (message.text("role") != "assistant") throw APIClient.APIException.InvalidResponse
    val text = message.text("content")?.takeIf { it.isNotBlank() } ?: throw APIClient.APIException.Incomplete
    val usage = response["usage"] as? JsonObject
    fun tokens(name: String) = ((usage?.get(name) as? JsonPrimitive)?.longOrNull ?: 0L)
        .coerceIn(0L, 1_000_000_000L).toInt()
    return APIResult(text, emptyList(), APIUsage(tokens("prompt_tokens"), tokens("completion_tokens")))
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
