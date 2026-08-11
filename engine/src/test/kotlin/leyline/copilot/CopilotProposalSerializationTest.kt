package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.UnitTag

class CopilotProposalSerializationTest :
    FunSpec({
        tags(UnitTag)

        test("serializes delivery messages as an ordered JSON array") {
            val proposal =
                CopilotProposal(intent = "pass", promptType = "ActionsAvailableReq", seat = 1, responses = listOf("first", "second"))

            val payload = Json.encodeToString(proposal)
            val responses = Json.parseToJsonElement(payload).jsonObject["responses"]!!.jsonArray

            responses.map { it.jsonPrimitive.content } shouldBe listOf("first", "second")
        }
    })
