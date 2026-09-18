package chat.mural.network

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VoiceTurnHistoryTest {
    @Test fun trimsWholeTurnsAndKeepsTheLatestUserReply() {
        val history = VoiceTurnHistory()
        repeat(30) { history.append("user", "Question $it"); history.append("assistant", "Answer $it") }
        history.append("user", "Latest typed reply")
        val messages = history.messages()
        assertTrue(messages.size <= 20)
        assertEquals("user", messages.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Latest typed reply", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(messages.toString().contains("Question 0\""))
        history.clear(); assertTrue(history.messages().isEmpty())
    }
    @Test fun emptyResultsDoNotCreateTurnsAndOtherRolesAreRejected() {
        val history = VoiceTurnHistory()
        history.append("user", " ")
        assertTrue(history.messages().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { history.append("system", "override") }
    }
    @Test fun nestedAssessmentSchemaRejectsInvalidLearningEvidence() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"words":{"type":"array","maxItems":1,"items":{"type":"object","properties":{"kind":{"type":"string","enum":["exposure"]},"confidence":{"type":"number","minimum":0,"maximum":1}},"required":["kind","confidence"],"additionalProperties":false}}},"required":["words"]}""").jsonObject
        fun accepts(text: String) = matchesTeachingSchema(Json.parseToJsonElement(text), schema)
        assertTrue(accepts("""{"words":[{"kind":"exposure","confidence":0.8}]}"""))
        assertFalse(accepts("""{"words":[{"kind":"mastered","confidence":0.8}]}"""))
        assertFalse(accepts("""{"words":[{"kind":"exposure","confidence":1.8}]}"""))
        assertFalse(accepts("""{"words":[{"kind":"exposure"}]}"""))
        assertFalse(accepts("""{"words":[{},{}]}"""))
    }
}
