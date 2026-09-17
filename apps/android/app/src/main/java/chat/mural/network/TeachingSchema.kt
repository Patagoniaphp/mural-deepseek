package chat.mural.network

import kotlinx.serialization.json.*

/** The JSON Schema subset used by Mural assessments, checked after DeepSeek JSON mode. */
internal fun matchesTeachingSchema(value: JsonElement, schema: JsonObject): Boolean {
    (schema["enum"] as? JsonArray)?.let { if (value !in it) return false }
    return when ((schema["type"] as? JsonPrimitive)?.contentOrNull) {
        "object" -> {
            val obj = value as? JsonObject ?: return false
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (schema["required"] as? JsonArray).orEmpty()
            if (required.any { item ->
                    val key = (item as? JsonPrimitive)?.contentOrNull
                    key == null || key !in obj
                }) return false
            if (schema["additionalProperties"] == JsonPrimitive(false) && obj.keys.any { it !in properties }) return false
            obj.all { (key, child) -> (properties[key] as? JsonObject)?.let { matchesTeachingSchema(child, it) } ?: true }
        }
        "array" -> {
            val array = value as? JsonArray ?: return false
            val min = (schema["minItems"] as? JsonPrimitive)?.intOrNull ?: 0
            val max = (schema["maxItems"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
            array.size in min..max && (schema["items"] as? JsonObject)?.let { item ->
                array.all { matchesTeachingSchema(it, item) }
            } != false
        }
        "string" -> value is JsonPrimitive && value.isString
        "integer", "number" -> {
            val primitive = value as? JsonPrimitive ?: return false
            if (primitive.isString) return false
            val number = primitive.doubleOrNull ?: return false
            val integer = schema["type"] == JsonPrimitive("integer")
            number.isFinite() && (!integer || number % 1.0 == 0.0) &&
                number >= ((schema["minimum"] as? JsonPrimitive)?.doubleOrNull ?: Double.NEGATIVE_INFINITY) &&
                number <= ((schema["maximum"] as? JsonPrimitive)?.doubleOrNull ?: Double.POSITIVE_INFINITY)
        }
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value is JsonNull
        else -> false
    }
}
