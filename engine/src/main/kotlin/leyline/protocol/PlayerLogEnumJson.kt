package leyline.protocol

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.EnumDescriptor
import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Descriptor-guided enum translation between generated proto JSON and GRE JSON. */
object PlayerLogEnumJson {
    private val enumTagSuffix = Regex("_[a-f0-9]{4}$")

    fun toCanonical(
        element: JsonElement,
        descriptor: Descriptor,
    ): JsonElement = transformMessage(element, descriptor, Direction.ToCanonical)

    fun toGenerated(
        element: JsonElement,
        descriptor: Descriptor,
    ): JsonElement = transformMessage(element, descriptor, Direction.ToGenerated)

    private fun transformMessage(
        element: JsonElement,
        descriptor: Descriptor,
        direction: Direction,
    ): JsonElement {
        if (element !is JsonObject) return element
        return JsonObject(
            element.mapValues { (key, value) ->
                val field = descriptor.fields.firstOrNull { it.jsonName == key || it.name == key } ?: return@mapValues value
                transformField(value, field, direction)
            },
        )
    }

    private fun transformField(
        element: JsonElement,
        field: FieldDescriptor,
        direction: Direction,
    ): JsonElement {
        if (field.isRepeated) {
            return if (element is JsonArray) {
                JsonArray(element.map { transformSingle(it, field, direction) })
            } else {
                element
            }
        }
        return transformSingle(element, field, direction)
    }

    private fun transformSingle(
        element: JsonElement,
        field: FieldDescriptor,
        direction: Direction,
    ): JsonElement =
        when (field.javaType) {
            FieldDescriptor.JavaType.ENUM -> transformEnum(element, field.enumType, direction)
            FieldDescriptor.JavaType.MESSAGE -> transformMessage(element, field.messageType, direction)
            FieldDescriptor.JavaType.INT,
            FieldDescriptor.JavaType.LONG,
            FieldDescriptor.JavaType.FLOAT,
            FieldDescriptor.JavaType.DOUBLE,
            FieldDescriptor.JavaType.BOOLEAN,
            FieldDescriptor.JavaType.STRING,
            FieldDescriptor.JavaType.BYTE_STRING,
            -> element
        }

    private fun transformEnum(
        element: JsonElement,
        descriptor: EnumDescriptor,
        direction: Direction,
    ): JsonElement {
        if (element !is JsonPrimitive || !element.isString) return element
        val input = element.contentOrNull ?: return element
        val value = descriptor.values.firstOrNull { it.matches(input, descriptor) } ?: return element
        return JsonPrimitive(
            when (direction) {
                Direction.ToCanonical -> value.canonicalName(descriptor)
                Direction.ToGenerated -> value.name
            },
        )
    }

    private fun EnumValueDescriptor.matches(
        input: String,
        descriptor: EnumDescriptor,
    ): Boolean = sequenceOf(name, strippedName(), canonicalName(descriptor)).any { candidate -> candidate.equals(input, ignoreCase = true) }

    private fun EnumValueDescriptor.canonicalName(descriptor: EnumDescriptor): String =
        "${descriptor.name}_${canonicalValueName(descriptor, strippedName())}"

    private fun EnumValueDescriptor.strippedName(): String = name.replace(enumTagSuffix, "")

    private fun canonicalValueName(
        descriptor: EnumDescriptor,
        value: String,
    ): String =
        when {
            descriptor.name == "ActionType" && value == "ActivateMana" -> "Activate_Mana"
            descriptor.name == "KeyValuePairValueType" -> value.replaceFirstChar { it.lowercase() }
            descriptor.name == "GREMessageType" && value == "SelectNreq" -> "SelectNReq"
            descriptor.name == "ClientMessageType" && value == "SelectNresp" -> "SelectNResp"
            else -> value
        }

    private enum class Direction { ToCanonical, ToGenerated }
}
