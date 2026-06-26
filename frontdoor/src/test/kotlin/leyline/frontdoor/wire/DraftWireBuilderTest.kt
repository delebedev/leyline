package leyline.frontdoor.wire

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.frontdoor.FdTag
import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.PlayerId

class DraftWireBuilderTest :
    FunSpec({

        tags(FdTag)

        val session =
            DraftSession(
                id = DraftSessionId("test-id"),
                playerId = PlayerId("test-player"),
                eventName = "QuickDraft_FDN_20260223",
                status = DraftStatus.PickNext,
                packNumber = 0,
                pickNumber = 0,
                draftPack = listOf(98353, 98519, 98532),
                pickedCards = emptyList(),
            )

        test("buildDraftResponse wraps payload in Course-style double-encoded JSON") {
            val json = DraftWireBuilder.buildDraftResponse(session)
            val outer = Json.parseToJsonElement(json).jsonObject

            outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"

            val payloadStr = outer["Payload"]?.jsonPrimitive?.content ?: error("no Payload")
            val inner = Json.parseToJsonElement(payloadStr).jsonObject

            assertSoftly {
                inner["Result"]?.jsonPrimitive?.content shouldBe "Success"
                inner["EventName"]?.jsonPrimitive?.content shouldBe "QuickDraft_FDN_20260223"
                inner["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
            }
        }

        test("DraftPack contains string grpIds") {
            val json = DraftWireBuilder.buildDraftResponse(session)
            val outer = Json.parseToJsonElement(json).jsonObject
            val payloadStr = outer["Payload"]!!.jsonPrimitive.content
            payloadStr shouldContain "\"98353\""
            payloadStr shouldContain "\"98519\""
        }

        test("completed draft has DeckSelect module and empty pack") {
            val completed =
                session.copy(
                    status = DraftStatus.Completed,
                    packNumber = 2,
                    pickNumber = 12,
                    draftPack = emptyList(),
                    pickedCards = listOf(98353, 98519),
                )
            val json = DraftWireBuilder.buildDraftResponse(completed)
            val outer = Json.parseToJsonElement(json).jsonObject

            outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"

            val payloadStr = outer["Payload"]!!.jsonPrimitive.content
            val inner = Json.parseToJsonElement(payloadStr).jsonObject
            assertSoftly {
                inner["DraftStatus"]?.jsonPrimitive?.content shouldBe "Completed"
                inner["PackNumber"]?.jsonPrimitive?.int shouldBe 2
                inner["PickNumber"]?.jsonPrimitive?.int shouldBe 12
            }
        }

        test("completed draft grants card pool with Arena inventory shape") {
            val completed =
                session.copy(
                    status = DraftStatus.Completed,
                    draftPack = emptyList(),
                    pickedCards = listOf(98353, 98519),
                )

            val json = DraftWireBuilder.buildDraftResponse(completed)
            val outer = Json.parseToJsonElement(json).jsonObject
            val inventory = outer["DTO_InventoryInfo"]!!.jsonObject
            val change = inventory["Changes"]!!.jsonArray.single().jsonObject
            val grant = change["GrantedCards"]!!.jsonArray.first().jsonObject

            assertSoftly {
                inventory["wcTrackPosition"]?.jsonPrimitive?.int shouldBe 0
                inventory["Vouchers"]?.jsonObject shouldBe emptyMap()
                inventory["PrizeWallsUnlocked"]?.jsonArray?.size shouldBe 0
                inventory["Cosmetics"]!!.jsonObject.keys shouldBe setOf("ArtStyles", "Avatars", "Pets", "Sleeves", "Emotes", "Titles")
                change.keys shouldBe
                    setOf(
                        "Source",
                        "SourceId",
                        "InventoryCustomTokens",
                        "ArtStyles",
                        "Avatars",
                        "Sleeves",
                        "Pets",
                        "Emotes",
                        "Titles",
                        "Decks",
                        "DecksV2",
                        "DecksV3",
                        "DeckCards",
                        "Boosters",
                        "GrantedCards",
                        "Vouchers",
                        "NewLetters",
                        "PrizeWallsUnlocked",
                    )
                grant["GrpId"]?.jsonPrimitive?.int shouldBe 98353
                grant["CardAdded"]?.jsonPrimitive?.content shouldBe "true"
                grant["SetCode"]?.jsonPrimitive?.content shouldBe "FDN"
            }
        }
    })
