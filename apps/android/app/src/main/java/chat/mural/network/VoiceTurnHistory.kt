package chat.mural.network

import kotlinx.serialization.json.*

/** Bounded role-based text context shared by spoken and typed turns. */
internal class VoiceTurnHistory {
    private val turns = ArrayDeque<Pair<String, String>>()
    fun append(role: String, text: String) {
        require(role == "user" || role == "assistant")
        if (text.isBlank()) return
        turns.addLast(role to text.take(8_000))
        while (turns.size > 20) turns.removeFirst()
        // Avoid sending an assistant-first history after pruning.
        while (turns.firstOrNull()?.first == "assistant" && turns.size > 1) turns.removeFirst()
    }
    fun messages(): JsonArray = buildJsonArray {
        turns.forEach { (role, text) ->
            add(buildJsonObject { put("role", role); put("content", text) })
        }
    }
    fun clear() = turns.clear()
}
