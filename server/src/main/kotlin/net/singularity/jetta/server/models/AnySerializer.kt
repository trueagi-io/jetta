package net.singularity.jetta.server.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.lang.Double.parseDouble


object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Any", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("This serializer can only be used with JSON")

        when (value) {
            is String -> jsonEncoder.encodeString(value)
            is Int -> jsonEncoder.encodeInt(value)
            is Boolean -> jsonEncoder.encodeBoolean(value)
            is Number -> jsonEncoder.encodeDouble(value.toDouble())
            is Map<*, *> -> {
                val jsonObject = JsonObject(
                    value.entries.associate { (key, v) ->
                        key.toString() to Json.encodeToJsonElement(AnySerializer, v ?: "null")
                    }
                )
                jsonEncoder.encodeJsonElement(jsonObject)
            }

            is List<*> -> {
                val jsonArray = JsonArray(value.map { Json.encodeToJsonElement(AnySerializer, it ?: "null") })
                jsonEncoder.encodeJsonElement(jsonArray)
            }

            else -> jsonEncoder.encodeString(value.toString()) // Fallback for unsupported types
        }
    }

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("This serializer can only be used with JSON")
        val element = jsonDecoder.decodeJsonElement()

        fun decodeElement(element: JsonElement): Any {
            when (element) {
                is JsonPrimitive -> {
                    when (element.content.lowercase()) {
                        "true" -> return true
                        "false" -> return false
                    }
                    try {
                        return Integer.parseInt(element.content)
                    } catch (_: NumberFormatException) {
                    }
                    return try {
                        parseDouble(element.content)
                    } catch (_: NumberFormatException) {
                        element.content
                    }
                }

                is JsonObject -> return element.entries.associate { (k, v) -> v to k }
                is JsonArray -> return element.map { decodeElement(it) }
            }
        }
        return decodeElement(element)
    }
}