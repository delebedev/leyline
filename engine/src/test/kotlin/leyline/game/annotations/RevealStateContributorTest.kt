package leyline.game.annotations

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
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
                    retiredIds = emptyList(),
                    zoneRecordings = emptyList(),
                )
            val ctx =
                AnnotationContext(
                    bridge = bridge,
                    snap = GsmSnapshot.forTest(),
                    frameIds = FrameIdResolver(bridge.projectionIdentityWorkspace()),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
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
    })
