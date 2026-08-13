package leyline.board.visibility

import forge.game.card.CardCollection
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.bundle.RequestBuilder
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.annotation
import leyline.testkit.annotationOrNull
import leyline.testkit.annotations
import leyline.testkit.gsm
import leyline.testkit.gsmOrNull
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CompletableFuture

/**
 * Reveal subsystem tests.
 *
 * Covers: RevealedCardCreated annotations, RevealedCard proxy synthesis,
 * hand visibility flip during reveal, proxy cleanup lifecycle,
 * and SelectNReq construction for reveal-choose prompts.
 */
class RevealTest :
    BoardTest({

        // ── Reveal annotations ──────────────────────────────────────────

        test("reveal produces RevealedCardCreated annotation") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val handCard =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            val gsm =
                board.snapshotDiff {
                    board.bridge
                        .promptBridge(SeatId(1))
                        .recordReveal(listOf(ForgeCardId(handCard.id)), SeatId(1), SeatId(2))
                }

            gsm
                .annotation(AnnotationType.RevealedCardCreated)
                .affectedIdsList shouldBe listOf(gsm.revealedCardProxies().single().instanceId)
        }

        test("multi-card reveal produces one annotation per card") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                    addCard("Giant Growth", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val handCards =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()

            val gsm =
                board.snapshotDiff {
                    board.bridge
                        .promptBridge(SeatId(1))
                        .recordReveal(handCards.map { ForgeCardId(it.id) }, SeatId(1), SeatId(2))
                }

            gsm.annotations(AnnotationType.RevealedCardCreated) shouldHaveSize 3
        }

        test("single-card hand reveal does not expose the rest of opponent hand") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                    addCard("Grizzly Bears", ai, ZoneType.Hand)
                }
            val handCards =
                board.game.aiPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val revealed = handCards.first()
            val coordinator = TargetingCoordinator(board.bridge.promptBridge(SeatId(1)), board.bridge.seating)

            board.bridge.seedDiffBaseline(board.game, board.counter.currentGsId())
            coordinator.captureReveal(CardCollection(listOf(revealed)), ZoneType.Hand, board.game.aiPlayer)
            val gsm =
                bundleBuilder(board.bridge)
                    .stateOnlyDiff(board.game, board.counter)
                    .gsmOrNull ?: error("stateOnlyDiff returned no GSM")

            assertSoftly {
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeReveal()
                    .shouldBeNull()
                gsm.annotations(AnnotationType.RevealedCardCreated) shouldHaveSize 1
                gsm.revealedCardProxies() shouldHaveSize 1
                gsm.zonesList.any { it.zoneId == ZoneIds.P2_HAND && it.visibility == Visibility.Public } shouldBe false
                gsm.gameObjectsList.count {
                    it.type == GameObjectType.Card &&
                        it.zoneId == ZoneIds.P2_HAND &&
                        it.visibility == Visibility.Public
                } shouldBe 0
            }
        }

        test("no reveal produces no RevealedCardCreated annotation") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }

            val gsm = board.snapshotDiff { /* no reveal */ }

            gsm.annotationOrNull(AnnotationType.RevealedCardCreated).shouldBeNull()
        }

        // ── Reveal-choose proxies ───────────────────────────────────────

        test("active reveal synthesizes RevealedCard proxy objects") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                    addCard("Grizzly Bears", ai, ZoneType.Hand)
                }
            val cardIds = aiHandCardIds(board.game)

            val gsm =
                board.snapshotDiff {
                    activateReveal(board.bridge, cardIds, ownerSeat = SeatId(2))
                }

            val proxies = gsm.revealedCardProxies()
            assertSoftly {
                proxies shouldHaveSize 2
                for (proxy in proxies) {
                    proxy.visibility shouldBe Visibility.Public
                    proxy.zoneId shouldBe ZoneIds.P2_HAND
                    proxy.ownerSeatId shouldBe 2
                    proxy.viewersCount shouldBe 1
                }
            }

            val revealedZone = gsm.zonesList.first { it.zoneId == ZoneIds.REVEALED_P2 }
            revealedZone.objectInstanceIdsList shouldHaveSize 2
        }

        test("active reveal flips opponent hand to Public visibility") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                }
            val cardIds = aiHandCardIds(board.game)

            val gsm =
                board.snapshotDiff {
                    activateReveal(board.bridge, cardIds, ownerSeat = SeatId(2))
                }

            val aiHandZone = gsm.zonesList.first { it.zoneId == ZoneIds.P2_HAND }
            aiHandZone.visibility shouldBe Visibility.Public
            aiHandZone.viewersList shouldHaveSize 2

            val handCards =
                gsm.gameObjectsList.filter {
                    it.type == GameObjectType.Card && it.zoneId == ZoneIds.P2_HAND
                }
            handCards shouldHaveSize 1
            handCards.first().visibility shouldBe Visibility.Public
        }

        test("stale activeReveal without pending prompt is auto-cleared") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                }
            val cardIds = aiHandCardIds(board.game)

            // First build: proxies allocated
            board.snapshotDiff {
                activateReveal(board.bridge, cardIds, ownerSeat = SeatId(2))
            }
            board.bridge
                .projectionStateSnapshot()
                .revealProxies.entries.values
                .shouldNotBeEmpty()

            // Second build: no prompt pending → stale guard clears activeReveal + proxies
            board.snapshotDiff {}

            board.bridge
                .promptBridge(SeatId(1))
                .journal
                .activeReveal()
                .shouldBeNull()
            board.bridge
                .projectionStateSnapshot()
                .revealProxies.entries.values
                .shouldBeEmpty()
        }

        test("clearing activeReveal triggers proxy cleanup in next GSM") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                }
            val cardIds = aiHandCardIds(board.game)

            board.snapshotDiff {
                activateReveal(board.bridge, cardIds, ownerSeat = SeatId(2))
            }
            board.bridge
                .projectionStateSnapshot()
                .revealProxies.entries.values
                .shouldNotBeEmpty()

            // Clear reveal (simulates choice completion)
            TargetingCoordinator.endReveal(board.bridge.promptBridge(SeatId(1)))

            val gsm = board.snapshotDiff {}

            board.bridge
                .projectionStateSnapshot()
                .revealProxies.entries.values
                .shouldBeEmpty()
            gsm.revealedCardProxies().shouldBeEmpty()
        }

        test("no active reveal produces no RevealedCard proxies") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                }

            val gsm = board.snapshotDiff {}

            gsm.revealedCardProxies().shouldBeEmpty()
        }

        // ── RequestBuilder ──────────────────────────────────────────────

        test("SelectNReq for reveal-choose with valid targets") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Lightning Bolt", ai, ZoneType.Hand)
                    addCard("Grizzly Bears", ai, ZoneType.Hand)
                }

            val bolt =
                board.game.aiPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Lightning Bolt" }
            val bears =
                board.game.aiPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val boltId = board.instanceId(bolt.id)
            val bearsId = board.instanceId(bears.id)

            val prompt =
                revealChoosePrompt(
                    candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, bolt.id, "Hand")),
                    unfilteredRefs =
                        listOf(
                            PromptCandidateRefDto(0, PromptCandidateKind.Card, bolt.id),
                            PromptCandidateRefDto(1, PromptCandidateKind.Card, bears.id),
                        ),
                    min = 1,
                    max = 1,
                    sourceEntityId = 999,
                )

            val req = RequestBuilder.buildSelectNReq(prompt, board.bridge, prompt.selectNRoute())

            assertSoftly {
                req.idsList shouldHaveSize 1
                req.idsList.first() shouldBe boltId
                req.unfilteredIdsList shouldHaveSize 2
                req.unfilteredIdsList.toSet() shouldBe setOf(boltId, bearsId)
                req.context shouldBe SelectionContext.Resolution_a163
                req.listType shouldBe SelectionListType.Dynamic
                req.idType shouldBe IdType.InstanceId_ab2c
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.sourceId shouldBeGreaterThan 0
            }
        }

        test("SelectNReq for reveal-choose with no valid targets") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Grizzly Bears", ai, ZoneType.Hand)
                }

            val bears =
                board.game.aiPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            val bearsId = board.instanceId(bears.id)

            val prompt =
                revealChoosePrompt(
                    candidateRefs = emptyList(),
                    unfilteredRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, bears.id)),
                    min = 0,
                    max = 0,
                )

            val req = RequestBuilder.buildSelectNReq(prompt, board.bridge, prompt.selectNRoute())

            assertSoftly {
                req.idsList.shouldBeEmpty()
                req.unfilteredIdsList shouldHaveSize 1
                req.unfilteredIdsList.first() shouldBe bearsId
                req.minSel shouldBe 0
                req.maxSel shouldBe 0
            }
        }
    }) {
    companion object {
        /** Extract AI hand card IDs as ForgeCardIds. */
        private fun aiHandCardIds(game: forge.game.Game): List<ForgeCardId> =
            game.aiPlayer
                .getZone(ZoneType.Hand)
                .cards
                .map { ForgeCardId(it.id) }

        /** Filter GSM objects to RevealedCard proxies. */
        private fun GameStateMessage.revealedCardProxies() = gameObjectsList.filter { it.type == GameObjectType.RevealedCard }

        /** Set up activeReveal + recordReveal in one call. */
        private fun activateReveal(
            b: GameBridge,
            cardIds: List<ForgeCardId>,
            ownerSeat: SeatId,
        ) {
            TargetingCoordinator.startReveal(b.promptBridge(SeatId(1)), cardIds, ownerSeat)
            b.promptBridge(SeatId(1)).recordReveal(cardIds, ownerSeat, SeatId(if (ownerSeat.value == 1) 2 else 1))
        }

        /** Build a PendingPrompt for reveal-choose scenarios. */
        private fun revealChoosePrompt(
            candidateRefs: List<PromptCandidateRefDto>,
            unfilteredRefs: List<PromptCandidateRefDto>,
            min: Int,
            max: Int,
            sourceEntityId: Int = 0,
        ): InteractivePromptBridge.PendingPrompt =
            InteractivePromptBridge.PendingPrompt(
                promptId = "test",
                request =
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose a card to discard",
                        options = candidateRefs.map { "card" },
                        min = min,
                        max = max,
                        candidateRefs = candidateRefs,
                        route = PromptRouteResolver.resolve(PromptSemantic.RevealChoose),
                        unfilteredRefs = unfilteredRefs,
                        sourceEntityId = sourceEntityId,
                    ),
                future = CompletableFuture(),
            )
    }
}

private fun InteractivePromptBridge.PendingPrompt.selectNRoute() = (request.route as ResolvedPromptRoute.SelectN).descriptor
