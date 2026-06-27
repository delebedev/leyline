package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.PromptIds
import leyline.match.PendingClientInteraction
import wotc.mtgo.gre.external.messaging.Messages.AllowFailToFind
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Verify SearchReq message shape with populated inner fields.
 */
class SearchReqTest :
    FunSpec({

        tags(UnitTag)

        test("buildSearchReq populates inner SearchReq fields") {
            val msg =
                RequestBuilder.buildSearchReq(
                    msgId = 42,
                    gsId = 10,
                    systemSeatId = 1,
                    sourceInstanceId = 290,
                    hostCardInstanceId = 290,
                    searchingSeat = 1,
                    libraryZoneId = 32,
                    allLibraryIds = listOf(100, 101, 102),
                    validTargetIds = listOf(100, 102),
                    maxFind = 1,
                    allowFailToFind = true,
                )

            assertSoftly {
                msg.type shouldBe GREMessageType.SearchReq_695e
                msg.msgId shouldBe 42
                msg.gameStateId shouldBe 10
                msg.systemSeatIdsList shouldBe listOf(1)
                msg.prompt.promptId shouldBe PromptIds.SEARCH
                msg.prompt.parametersList.size shouldBe 2
                msg.prompt.parametersList[0].numberValue shouldBe 290
                msg.prompt.parametersList[1].numberValue shouldBe 1
                msg.allowCancel shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowCancel.No_a526
            }

            val sr = msg.searchReq
            assertSoftly {
                sr.maxFind shouldBe 1
                sr.zonesToSearchList shouldContainExactly listOf(32)
                sr.itemsToSearchList shouldContainExactly listOf(100, 101, 102)
                sr.itemsSoughtList shouldContainExactly listOf(100, 102)
                sr.sourceId shouldBe 290
                sr.allowFailToFind shouldBe AllowFailToFind.Any
            }
        }

        test("buildSearchReq with typecycling promptId emits AB-distinct sourceId + host-card-anchored panel") {
            val msg =
                RequestBuilder.buildSearchReq(
                    msgId = 22,
                    gsId = 12,
                    systemSeatId = 1,
                    sourceInstanceId = 296, // AB instance iid
                    hostCardInstanceId = 297, // host card iid (Lórien Revealed)
                    searchingSeat = 1,
                    libraryZoneId = 32,
                    allLibraryIds = (260..279).toList(),
                    validTargetIds = listOf(260, 261, 262, 263),
                    maxFind = 1,
                    allowFailToFind = true,
                    promptId = PromptIds.SEARCH_TYPECYCLING,
                )

            assertSoftly {
                msg.type shouldBe GREMessageType.SearchReq_695e
                msg.prompt.promptId shouldBe PromptIds.SEARCH_TYPECYCLING
                // parameters[0] = host card iid (panel header anchor — names "Lórien Revealed")
                // parameters[1] = seat id
                msg.prompt.parametersList.size shouldBe 2
                msg.prompt.parametersList[0].numberValue shouldBe 297
                msg.prompt.parametersList[1].numberValue shouldBe 1
                msg.allowCancel shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowCancel.No_a526
            }

            val sr = msg.searchReq
            assertSoftly {
                // sourceId is the AB instance iid, distinct from the host card iid
                sr.sourceId shouldBe 296
                sr.itemsSoughtList shouldContainExactly listOf(260, 261, 262, 263)
                sr.itemsToSearchList.size shouldBe 20
                sr.allowFailToFind shouldBe AllowFailToFind.Any
            }
        }

        test("PendingClientInteraction.Search stores promptId") {
            val pending = PendingClientInteraction.Search("prompt-123")
            pending.promptId shouldBe "prompt-123"
        }
    })
