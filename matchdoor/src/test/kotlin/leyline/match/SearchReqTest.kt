package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.game.BundleBuilder
import leyline.game.mapper.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowFailToFind
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Verify SearchReq message shape with populated inner fields.
 */
class SearchReqTest :
    FunSpec({

        test("buildSearchReq populates inner SearchReq fields") {
            val msg = BundleBuilder.buildSearchReq(
                msgId = 42,
                gsId = 10,
                seatId = 1,
                sourceInstanceId = 290,
                libraryZoneId = 32,
                allLibraryIds = listOf(100, 101, 102),
                validTargetIds = listOf(100, 102),
                maxFind = 1,
                allowFailToFind = true,
            )

            msg.type shouldBe GREMessageType.SearchReq_695e
            msg.msgId shouldBe 42
            msg.gameStateId shouldBe 10
            msg.systemSeatIdsList shouldBe listOf(1)
            msg.prompt.promptId shouldBe PromptIds.SEARCH

            val sr = msg.searchReq
            sr.maxFind shouldBe 1
            sr.zonesToSearchList shouldContainExactly listOf(32)
            sr.itemsToSearchList shouldContainExactly listOf(100, 101, 102)
            sr.itemsSoughtList shouldContainExactly listOf(100, 102)
            sr.sourceId shouldBe 290
            sr.allowFailToFind shouldBe AllowFailToFind.Any
        }

        test("PendingClientInteraction.Search stores promptId") {
            val pending = PendingClientInteraction.Search("prompt-123")
            pending.promptId shouldBe "prompt-123"
        }
    })
