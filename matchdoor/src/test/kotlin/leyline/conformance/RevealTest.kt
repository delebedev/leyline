package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.ForgeCardId
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PromptCandidateRefDto
import leyline.bridge.PromptRequest
import leyline.bridge.PromptSemantic
import leyline.bridge.SeatId
import leyline.game.RequestBuilder
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Reveal subsystem tests.
 *
 * Covers: RevealedCardCreated annotations, RevealedCard proxy synthesis,
 * hand visibility flip during reveal, proxy cleanup lifecycle,
 * and SelectNReq construction for reveal-choose prompts.
 */
class RevealTest :
    SubsystemTest({

        // ── Reveal annotations ──────────────────────────────────────────

        test("reveal produces RevealedCardCreated annotation") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Lightning Bolt", human, ZoneType.Hand)
            }
            val handCard = game.humanPlayer.getZone(ZoneType.Hand).cards.first()
            val instanceId = b.getOrAllocInstanceId(ForgeCardId(handCard.id))

            val gsm = capture(b, game, counter) {
                b.promptBridge(1).recordReveal(listOf(ForgeCardId(handCard.id)), SeatId(1))
            }

            val revealAnn = gsm.annotationOrNull(AnnotationType.RevealedCardCreated)
            revealAnn.shouldNotBeNull()
            revealAnn.affectedIdsList.contains(instanceId.value).shouldBeTrue()
        }

        test("multi-card reveal produces one annotation per card") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Lightning Bolt", human, ZoneType.Hand)
                addCard("Giant Growth", human, ZoneType.Hand)
                addCard("Grizzly Bears", human, ZoneType.Hand)
            }
            val handCards = game.humanPlayer.getZone(ZoneType.Hand).cards.toList()
            val cardCount = handCards.size

            val gsm = capture(b, game, counter) {
                b.promptBridge(1).recordReveal(handCards.map { ForgeCardId(it.id) }, SeatId(1))
            }

            gsm.annotations(AnnotationType.RevealedCardCreated) shouldHaveSize cardCount
        }

        test("no reveal produces no RevealedCardCreated annotation") {
            val (b, game, counter) = startWithBoard { _, human, _ ->
                addCard("Forest", human, ZoneType.Hand)
            }

            val gsm = capture(b, game, counter) { /* no reveal */ }

            gsm.annotationOrNull(AnnotationType.RevealedCardCreated).shouldBeNull()
        }

        // ── Reveal-choose proxies ───────────────────────────────────────

        test("active reveal synthesizes RevealedCard proxy objects") {
            val (b, game, counter) = startWithBoard { _, _, ai ->
                addCard("Lightning Bolt", ai, ZoneType.Hand)
                addCard("Grizzly Bears", ai, ZoneType.Hand)
            }
            val cardIds = aiHandCardIds(game)

            val gsm = capture(b, game, counter) {
                activateReveal(b, cardIds, ownerSeat = SeatId(2))
            }

            val proxies = gsm.revealedCardProxies()
            proxies shouldHaveSize 2

            assertSoftly {
                for (proxy in proxies) {
                    proxy.visibility shouldBe Visibility.Public
                    proxy.zoneId shouldBe ZoneIds.P2_HAND
                    proxy.ownerSeatId shouldBe 2
                    proxy.viewersCount shouldBeGreaterThan 0
                }
            }

            val revealedZone = gsm.zonesList.first { it.zoneId == ZoneIds.REVEALED_P2 }
            revealedZone.objectInstanceIdsList shouldHaveSize 2

            b.promptBridge(1).activeReveal = null
        }

        test("active reveal flips opponent hand to Public visibility") {
            val (b, game, counter) = startWithBoard { _, _, ai ->
                addCard("Lightning Bolt", ai, ZoneType.Hand)
            }
            val cardIds = aiHandCardIds(game)

            val gsm = capture(b, game, counter) {
                activateReveal(b, cardIds, ownerSeat = SeatId(2))
            }

            val aiHandZone = gsm.zonesList.first { it.zoneId == ZoneIds.P2_HAND }
            aiHandZone.visibility shouldBe Visibility.Public
            aiHandZone.viewersList shouldHaveSize 2

            val handCards = gsm.gameObjectsList.filter {
                it.type == GameObjectType.Card && it.zoneId == ZoneIds.P2_HAND
            }
            handCards shouldHaveSize 1
            handCards.first().visibility shouldBe Visibility.Public

            b.promptBridge(1).activeReveal = null
        }

        test("stale activeReveal without pending prompt is auto-cleared") {
            val (b, game, counter) = startWithBoard { _, _, ai ->
                addCard("Lightning Bolt", ai, ZoneType.Hand)
            }
            val cardIds = aiHandCardIds(game)

            // First build: proxies allocated
            capture(b, game, counter) {
                activateReveal(b, cardIds, ownerSeat = SeatId(2))
            }
            b.activeRevealProxies.size shouldBe 1

            // Second build: no prompt pending → stale guard clears activeReveal + proxies
            capture(b, game, counter) {}

            b.promptBridge(1).activeReveal.shouldBeNull()
            b.activeRevealProxies.size shouldBe 0
        }

        test("clearing activeReveal triggers proxy cleanup in next GSM") {
            val (b, game, counter) = startWithBoard { _, _, ai ->
                addCard("Lightning Bolt", ai, ZoneType.Hand)
            }
            val cardIds = aiHandCardIds(game)

            capture(b, game, counter) {
                activateReveal(b, cardIds, ownerSeat = SeatId(2))
            }
            b.activeRevealProxies.size shouldBe 1

            // Clear reveal (simulates choice completion)
            b.promptBridge(1).activeReveal = null

            val gsm = capture(b, game, counter) {}

            b.activeRevealProxies.size shouldBe 0
            gsm.revealedCardProxies().shouldBeEmpty()
        }

        test("no active reveal produces no RevealedCard proxies") {
            val (b, game, counter) = startWithBoard { _, _, ai ->
                addCard("Lightning Bolt", ai, ZoneType.Hand)
            }

            val gsm = capture(b, game, counter) {}

            gsm.revealedCardProxies().shouldBeEmpty()
        }

        // ── RequestBuilder ──────────────────────────────────────────────

        test("SelectNReq for reveal-choose with valid targets") {
            val (b, game, _) = startWithBoard { _, _, ai ->
                addCard("Lightning Bolt", ai, ZoneType.Hand)
                addCard("Grizzly Bears", ai, ZoneType.Hand)
            }

            val bolt = game.aiPlayer.getZone(ZoneType.Hand).cards.first { it.name == "Lightning Bolt" }
            val bears = game.aiPlayer.getZone(ZoneType.Hand).cards.first { it.name == "Grizzly Bears" }
            val boltId = b.getOrAllocInstanceId(ForgeCardId(bolt.id)).value
            val bearsId = b.getOrAllocInstanceId(ForgeCardId(bears.id)).value

            val prompt = revealChoosePrompt(
                candidateRefs = listOf(PromptCandidateRefDto(0, "card", bolt.id, "Hand")),
                unfilteredRefs = listOf(
                    PromptCandidateRefDto(0, "card", bolt.id),
                    PromptCandidateRefDto(1, "card", bears.id),
                ),
                min = 1,
                max = 1,
                sourceEntityId = 999,
            )

            val req = RequestBuilder.buildSelectNReq(prompt, b)

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
            val (b, game, _) = startWithBoard { _, _, ai ->
                addCard("Grizzly Bears", ai, ZoneType.Hand)
            }

            val bears = game.aiPlayer.getZone(ZoneType.Hand).cards.first()
            val bearsId = b.getOrAllocInstanceId(ForgeCardId(bears.id)).value

            val prompt = revealChoosePrompt(
                candidateRefs = emptyList(),
                unfilteredRefs = listOf(PromptCandidateRefDto(0, "card", bears.id)),
                min = 0,
                max = 0,
            )

            val req = RequestBuilder.buildSelectNReq(prompt, b)

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
            game.aiPlayer.getZone(ZoneType.Hand).cards.map { ForgeCardId(it.id) }

        /** Filter GSM objects to RevealedCard proxies. */
        private fun GameStateMessage.revealedCardProxies() =
            gameObjectsList.filter { it.type == GameObjectType.RevealedCard }

        /** Set up activeReveal + recordReveal in one call. */
        private fun activateReveal(
            b: leyline.game.GameBridge,
            cardIds: List<ForgeCardId>,
            ownerSeat: SeatId,
        ) {
            b.promptBridge(1).activeReveal =
                InteractivePromptBridge.ActiveReveal(cardIds, ownerSeat)
            b.promptBridge(1).recordReveal(cardIds, ownerSeat)
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
                request = PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card to discard",
                    options = candidateRefs.map { "card" },
                    min = min,
                    max = max,
                    semantic = PromptSemantic.RevealChoose,
                    candidateRefs = candidateRefs,
                    unfilteredRefs = unfilteredRefs,
                    sourceEntityId = sourceEntityId,
                ),
                future = java.util.concurrent.CompletableFuture(),
            )
    }
}
