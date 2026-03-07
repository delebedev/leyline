package leyline.frontdoor.wire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId

class DraftWireBuilderTest :
    FunSpec({

        val session = DraftSession(
            id = DraftSessionId("test-id"),
            playerId = PlayerId("test-player"),
            eventName = "QuickDraft_ECL_20260223",
            status = DraftStatus.PickNext,
            packNumber = 0,
            pickNumber = 0,
            draftPack = listOf(98353, 98519, 98532),
            packs = emptyList(),
            pickedCards = emptyList(),
        )

        test("buildDraftResponse wraps payload in Course-style double-encoded JSON") {
            val json = DraftWireBuilder.buildDraftResponse(session)
            val outer = Json.parseToJsonElement(json).jsonObject

            outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"

            val payloadStr = outer["Payload"]?.jsonPrimitive?.content ?: error("no Payload")
            val inner = Json.parseToJsonElement(payloadStr).jsonObject

            inner["Result"]?.jsonPrimitive?.content shouldBe "Success"
            inner["EventName"]?.jsonPrimitive?.content shouldBe "QuickDraft_ECL_20260223"
            inner["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
        }

        test("DraftPack contains string grpIds") {
            val json = DraftWireBuilder.buildDraftResponse(session)
            val outer = Json.parseToJsonElement(json).jsonObject
            val payloadStr = outer["Payload"]!!.jsonPrimitive.content
            payloadStr shouldContain "\"98353\""
            payloadStr shouldContain "\"98519\""
        }

        test("completed draft has DeckSelect module and empty pack") {
            val completed = session.copy(
                status = DraftStatus.Completed,
                draftPack = emptyList(),
                pickedCards = listOf(98353, 98519),
            )
            val json = DraftWireBuilder.buildDraftResponse(completed)
            val outer = Json.parseToJsonElement(json).jsonObject

            outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"

            val payloadStr = outer["Payload"]!!.jsonPrimitive.content
            val inner = Json.parseToJsonElement(payloadStr).jsonObject
            inner["DraftStatus"]?.jsonPrimitive?.content shouldBe "Completed"
        }
    })
