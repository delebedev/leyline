package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.game.BundleBuilder
import leyline.game.mapper.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Verify SearchReq message shape — empty-proto handshake for library search.
 */
class SearchReqTest : FunSpec({

    test("buildSearchReq produces correct GRE message shape") {
        val msg = BundleBuilder.buildSearchReq(msgId = 42, gsId = 10, seatId = 1)

        msg.type shouldBe GREMessageType.SearchReq_695e
        msg.msgId shouldBe 42
        msg.gameStateId shouldBe 10
        msg.systemSeatIdsList shouldBe listOf(1)
        msg.prompt.promptId shouldBe PromptIds.SEARCH
        msg.hasSearchReq() shouldBe true
    }

    test("PendingClientInteraction.Search stores promptId") {
        val pending = PendingClientInteraction.Search("prompt-123")
        pending.promptId shouldBe "prompt-123"
    }
})
