package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

class CopilotProposalSerializationTest :
    FunSpec({
        tags(UnitTag)

        test("serializes ordered delivery messages as independently decodable JSON entries") {
            val pass =
                ResponseBuilder
                    .hexMessages(ResponseBuilder.build(SimDecision.PassPriority, gsId = 42, seatId = 1, respId = 7))
                    .single()
            val cancel =
                ResponseBuilder
                    .hexMessages(ResponseBuilder.build(SimDecision.CancelAction, gsId = 42, seatId = 1, respId = 7))
                    .single()
            val proposal =
                CopilotProposal(intent = "pass", promptType = "ActionsAvailableReq", seat = 1, responses = listOf(pass, cancel))

            val payload = Json.encodeToString(proposal)
            val responses = Json.parseToJsonElement(payload).jsonObject["responses"]!!.jsonArray

            val decoded =
                responses.map { response ->
                    val hex = response.jsonPrimitive.content
                    val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                    ClientToGREMessage.parseFrom(bytes)
                }
            decoded.map { it.type } shouldBe listOf(ClientMessageType.PerformActionResp_097b, ClientMessageType.CancelActionReq_097b)
        }
    })
