package leyline.frontdoor.wire

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.frontdoor.FdTag

/**
 * `Event_SetDeckV2` (622) and `Event_SetCourseDeck` (627) ship the deck under
 * different envelopes. Both flow through `FdRequests.parseSetDeck` and need to
 * parse cleanly into the same domain model.
 */
class FdRequestsParseSetDeckTest :
    FunSpec({

        tags(FdTag)

        test("622 envelope nests MainDeck and Sideboard under Deck") {
            val json =
                """
                {
                  "EventName": "Sealed_FDN_20260307",
                  "Summary": {"DeckId":"abc-123","Name":"Sealed Deck","DeckTileId":42},
                  "Deck": {
                    "MainDeck": [{"cardId":75452,"quantity":4},{"cardId":75556,"quantity":17}],
                    "Sideboard": [{"cardId":75450,"quantity":2}]
                  }
                }
                """.trimIndent()

            val parsed = FdRequests.parseSetDeck(json)
            parsed.shouldNotBeNull()
            assertSoftly {
                parsed.eventName shouldBe "Sealed_FDN_20260307"
                parsed.deckId shouldBe "abc-123"
                parsed.deckName shouldBe "Sealed Deck"
                parsed.tileId shouldBe 42
                parsed.mainDeck shouldHaveSize 2
                parsed.mainDeck[0].grpId shouldBe 75452
                parsed.mainDeck[0].quantity shouldBe 4
                parsed.sideboard shouldHaveSize 1
            }
        }

        test("627 envelope puts MainDeck and Sideboard at the top level") {
            val json =
                """
                {
                  "EventName": "QuickDraft_FDN_20260223",
                  "Summary": {"DeckId":"def-456","Name":"Draft Deck","DeckTileId":7},
                  "MainDeck": [{"cardId":93947,"quantity":1},{"cardId":102738,"quantity":4}],
                  "Sideboard": []
                }
                """.trimIndent()

            val parsed = FdRequests.parseSetDeck(json)
            parsed.shouldNotBeNull()
            assertSoftly {
                parsed.eventName shouldBe "QuickDraft_FDN_20260223"
                parsed.deckId shouldBe "def-456"
                parsed.deckName shouldBe "Draft Deck"
                parsed.tileId shouldBe 7
                parsed.mainDeck shouldHaveSize 2
                parsed.mainDeck[0].grpId shouldBe 93947
                parsed.sideboard shouldHaveSize 0
            }
        }

        test("missing EventName returns null") {
            FdRequests.parseSetDeck("""{"Summary":{"DeckId":"x"}}""") shouldBe null
        }
    })
