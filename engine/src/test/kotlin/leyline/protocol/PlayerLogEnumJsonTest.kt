package leyline.protocol

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import leyline.UnitTag
import leyline.game.bundle.PROMPT_GRE_TYPES
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo

class PlayerLogEnumJsonTest :
    FunSpec({
        tags(UnitTag)

        test("round trips every prompt discriminator") {
            val printer = JsonFormat.printer().preservingProtoFieldNames()
            val parser = JsonFormat.parser()

            for (type in PROMPT_GRE_TYPES) {
                val message =
                    GREToClientMessage
                        .newBuilder()
                        .setType(type)
                        .setMsgId(7)
                        .setGameStateId(5)
                        .build()
                val generated = Json.parseToJsonElement(printer.print(message))
                val canonical = PlayerLogEnumJson.toCanonical(generated, GREToClientMessage.getDescriptor())
                val canonicalType = (canonical as JsonObject).getValue("type") as JsonPrimitive
                canonicalType.content shouldStartWith "GREMessageType_"

                val restored = PlayerLogEnumJson.toGenerated(canonical, GREToClientMessage.getDescriptor())
                val parsed = GREToClientMessage.newBuilder()
                parser.merge(restored.toString(), parsed)
                parsed.build() shouldBe message
            }
        }

        test("finds a payload descriptor for every prompt") {
            promptPayloadDescriptors() shouldHaveSize PROMPT_GRE_TYPES.size
        }

        test("round trips every enum field reachable from prompt payloads") {
            val fields = promptPayloadDescriptors().flatMap(::reachableEnumFields).distinctBy { it.fullName }
            fields.shouldNotBeEmpty()

            for (field in fields) {
                val value = field.enumType.values.firstOrNull { it.number != 0 } ?: field.enumType.values.first()
                val generatedValue: JsonElement = JsonPrimitive(value.name)
                val generated =
                    JsonObject(
                        mapOf(
                            field.name to
                                if (field.isRepeated) JsonArray(listOf(generatedValue)) else generatedValue,
                        ),
                    )
                val canonical = PlayerLogEnumJson.toCanonical(generated, field.containingType)
                PlayerLogEnumJson.toGenerated(canonical, field.containingType) shouldBe generated
            }
        }

        test("uses canonical spellings for irregular enum values") {
            val actionField =
                promptPayloadDescriptors()
                    .flatMap(::reachableEnumFields)
                    .first { it.enumType.name == "ActionType" }
            val generated = JsonObject(mapOf(actionField.name to JsonPrimitive("ActivateMana")))

            assertSoftly {
                PlayerLogEnumJson.toCanonical(generated, actionField.containingType) shouldBe
                    JsonObject(mapOf(actionField.name to JsonPrimitive("ActionType_Activate_Mana")))

                PlayerLogEnumJson.toCanonical(
                    JsonObject(mapOf("type" to JsonPrimitive("Int32"))),
                    KeyValuePairInfo.getDescriptor(),
                ) shouldBe JsonObject(mapOf("type" to JsonPrimitive("KeyValuePairValueType_int32")))

                PlayerLogEnumJson.toCanonical(
                    JsonObject(mapOf("type" to JsonPrimitive("SelectNreq"))),
                    GREToClientMessage.getDescriptor(),
                ) shouldBe JsonObject(mapOf("type" to JsonPrimitive("GREMessageType_SelectNReq")))

                PlayerLogEnumJson.toCanonical(
                    JsonObject(mapOf("type" to JsonPrimitive("SelectNresp"))),
                    ClientToGREMessage.getDescriptor(),
                ) shouldBe JsonObject(mapOf("type" to JsonPrimitive("ClientMessageType_SelectNResp")))
            }
        }
    })

private fun promptPayloadDescriptors(): List<Descriptor> {
    val gre = GREToClientMessage.getDescriptor()
    return PROMPT_GRE_TYPES.mapNotNull { type ->
        val wireName = type.name.replace(Regex("_[a-f0-9]{4}$"), "")
        val payloadName = if (wireName == "PromptReq") "Prompt" else wireName
        gre.fields
            .firstOrNull { field ->
                field.javaType == FieldDescriptor.JavaType.MESSAGE && field.messageType.name.equals(payloadName, ignoreCase = true)
            }?.messageType
    }
}

private fun reachableEnumFields(root: Descriptor): List<FieldDescriptor> {
    val visited = mutableSetOf<String>()
    val result = mutableListOf<FieldDescriptor>()

    fun visit(descriptor: Descriptor) {
        if (!visited.add(descriptor.fullName)) return
        for (field in descriptor.fields) {
            when (field.javaType) {
                FieldDescriptor.JavaType.ENUM -> result += field
                FieldDescriptor.JavaType.MESSAGE -> visit(field.messageType)
                FieldDescriptor.JavaType.INT,
                FieldDescriptor.JavaType.LONG,
                FieldDescriptor.JavaType.FLOAT,
                FieldDescriptor.JavaType.DOUBLE,
                FieldDescriptor.JavaType.BOOLEAN,
                FieldDescriptor.JavaType.STRING,
                FieldDescriptor.JavaType.BYTE_STRING,
                -> Unit
            }
        }
    }

    visit(root)
    return result
}
