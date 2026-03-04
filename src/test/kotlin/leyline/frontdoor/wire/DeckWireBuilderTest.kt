package leyline.frontdoor.wire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId

class DeckWireBuilderTest :
    FunSpec({
        tags(FdTag)

        val deck = Deck(
            id = DeckId("d1"), playerId = PlayerId("p1"), name = "Test Deck",
            format = Format.Standard, tileId = 12345,
            mainDeck = listOf(DeckCard(100, 4), DeckCard(200, 56)),
            sideboard = emptyList(), commandZone = emptyList(), companions = emptyList(),
        )

        test("V2 summary has Attributes as array of name/value pairs") {
            val obj = DeckWireBuilder.toV2Summary(deck)
            obj["DeckId"]?.jsonPrimitive?.content shouldBe "d1"
            obj["Name"]?.jsonPrimitive?.content shouldBe "Test Deck"
            val attrs = obj["Attributes"]?.jsonArray
            attrs shouldNotBe null
            val first = attrs!![0].jsonObject
            first["name"]?.jsonPrimitive?.content shouldBe "Version"
        }

        test("V3 summary has Attributes as flat dict") {
            val obj = DeckWireBuilder.toV3Summary(deck)
            val attrs = obj["Attributes"]?.jsonObject
            attrs shouldNotBe null
            attrs!!["Format"]?.jsonPrimitive?.content shouldBe "Standard"
            attrs["TileID"]?.jsonPrimitive?.content shouldBe "12345"
        }

        test("V2 and V3 both have FormatLegalities") {
            DeckWireBuilder.toV2Summary(deck)["FormatLegalities"]?.jsonObject shouldNotBe null
            DeckWireBuilder.toV3Summary(deck)["FormatLegalities"]?.jsonObject shouldNotBe null
        }

        test("60-card deck is Standard legal") {
            val obj = DeckWireBuilder.toV3Summary(deck)
            obj["FormatLegalities"]!!.jsonObject["Standard"]?.jsonPrimitive?.boolean shouldBe true
        }

        test("under 60 cards is not Standard legal") {
            val small = deck.copy(mainDeck = listOf(DeckCard(100, 10)))
            val obj = DeckWireBuilder.toV3Summary(small)
            obj["FormatLegalities"]!!.jsonObject["Standard"]?.jsonPrimitive?.boolean shouldBe false
        }

        test("parseDeckUpdate returns Deck from 406 JSON") {
            val json =
                """{"DeckId":"d1","Name":"My Deck","DeckTileId":99,"Deck":{"MainDeck":[{"cardId":100,"quantity":4}],"Sideboard":[],"CommandZone":[],"Companions":[],"CardSkins":[]}}"""
            val parsed = DeckWireBuilder.parseDeckUpdate(json, PlayerId("p1"))
            parsed shouldNotBe null
            parsed!!.name shouldBe "My Deck"
            parsed.tileId shouldBe 99
            parsed.mainDeck.size shouldBe 1
        }

        test("toStartHookEntry includes card lists") {
            val entry = DeckWireBuilder.toStartHookEntry(deck)
            entry["MainDeck"]?.jsonArray shouldNotBe null
            entry["MainDeck"]!!.jsonArray.size shouldBe 2
            entry["Sideboard"]?.jsonArray shouldNotBe null
        }
    })
