package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.state.CardRevealedKind
import leyline.game.state.GameBridge
import leyline.game.state.RevealProxyTracker

class RevealStateContributorTest :
    FunSpec({
        tags(UnitTag)

        test("resolved spell remains the CardRevealed affector after iid reallocation") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val sourceCardId = ForgeCardId(10)
            val revealedCardId = ForgeCardId(20)
            bridge.replaceProjectionStateForTest(
                bridge.projectionStateSnapshot().copy(
                    revealProxies = RevealProxyTracker.State(mapOf(revealedCardId to InstanceId(501))),
                ),
            )
            val transferResult =
                TransferResult(
                    transfers =
                        listOf(
                            AppliedTransfer(
                                origId = 200,
                                newId = 201,
                                category = TransferCategory.Resolve,
                                srcZoneId = ZoneIds.STACK,
                                destZoneId = ZoneIds.P1_GRAVEYARD,
                                forgeCardId = sourceCardId,
                                grpId = 105816,
                                ownerSeatId = 1,
                            ),
                        ),
                    patchedObjects = emptyList(),
                    patchedZones = emptyList(),
                )
            val ctx =
                annotationContext(
                    bridge = bridge,
                    events =
                        listOf(
                            GameEvent.CardsRevealed(
                                listOf(revealedCardId),
                                ownerSeatId = SeatId(2),
                                viewerSeatId = SeatId(1),
                                sourceZone = RevealZone.HAND,
                                sourceCardId = sourceCardId,
                            ),
                        ),
                    transferResult = transferResult,
                )

            val row =
                RevealStateContributor
                    .contribute(ctx)
                    .persistent
                    .getValue(CardRevealedKind)
                    .single()

            row.affectorId shouldBe 200
        }
        for (normalAfterLook in listOf(false, true)) {
            test("private look preserves a subsequent normal reveal: $normalAfterLook") {
                val bridge = GameBridge(cardRepository = InMemoryCardRepository())
                val source = ForgeCardId(10)
                val card = ForgeCardId(20)
                val sourceIid = bridge.getOrAllocInstanceId(source)
                bridge.replaceProjectionStateForTest(
                    bridge.projectionStateSnapshot().copy(revealProxies = RevealProxyTracker.State(mapOf(card to InstanceId(501)))),
                )
                val look = GameEvent.CardsRevealed(listOf(card), SeatId(2), SeatId(1), RevealZone.HAND, source, lookOnly = true)
                val events = if (normalAfterLook) listOf(look, look.copy(lookOnly = false)) else listOf(look)
                val result = RevealStateContributor.contribute(annotationContext(bridge = bridge, events = events))
                assertSoftly {
                    result.transient shouldHaveSize 1
                    if (normalAfterLook) {
                        result.persistent
                            .getValue(CardRevealedKind)
                            .single()
                            .affectorId shouldBe sourceIid.value
                    } else {
                        result.persistent.getValue(CardRevealedKind).shouldBeEmpty()
                    }
                }
            }
        }
    })
