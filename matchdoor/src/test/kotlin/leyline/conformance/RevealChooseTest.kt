package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PromptCandidateRefDto
import leyline.bridge.PromptRequest
import leyline.bridge.PromptSemantic
import leyline.bridge.SeatId
import leyline.game.RequestBuilder
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Reveal-choose infrastructure: RevealedCard proxy synthesis, hand visibility flip,
 * SelectNReq shape with ids/unfilteredIds, and proxy cleanup.
 */
class RevealChooseTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("active reveal synthesizes RevealedCard proxy objects in GSM") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Lightning Bolt", ai, ZoneType.Hand)
                base.addCard("Grizzly Bears", ai, ZoneType.Hand)
            }
            val aiHand = game.aiPlayer.getZone(ZoneType.Hand).cards.toList()
            val cardIds = aiHand.map { ForgeCardId(it.id) }

            val gsm = base.captureAfterAction(b, game, counter) {
                // Simulate active reveal (set by WebPlayerController.reveal)
                b.promptBridge(1).activeReveal = InteractivePromptBridge.ActiveReveal(cardIds, SeatId(2))
                b.promptBridge(1).recordReveal(cardIds, SeatId(2))
            }

            // Proxies should exist with type=RevealedCard
            val proxyObjects = gsm.gameObjectsList.filter { it.type == GameObjectType.RevealedCard }
            proxyObjects shouldHaveSize 2

            assertSoftly {
                for (proxy in proxyObjects) {
                    proxy.visibility shouldBe Visibility.Public
                    proxy.zoneId shouldBe ZoneIds.P2_HAND // proxy overlays hand zone
                    proxy.ownerSeatId shouldBe 2
                    proxy.viewersCount shouldBeGreaterThan 0
                }
            }

            // Revealed zone (19) should contain proxy instanceIds
            val revealedZone = gsm.zonesList.first { it.zoneId == ZoneIds.REVEALED_P2 }
            revealedZone.objectInstanceIdsList shouldHaveSize 2

            // Clean up
            b.promptBridge(1).activeReveal = null
        }

        test("active reveal flips opponent hand to Public visibility") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Lightning Bolt", ai, ZoneType.Hand)
            }
            val aiHand = game.aiPlayer.getZone(ZoneType.Hand).cards.toList()
            val cardIds = aiHand.map { ForgeCardId(it.id) }

            val gsm = base.captureAfterAction(b, game, counter) {
                b.promptBridge(1).activeReveal = InteractivePromptBridge.ActiveReveal(cardIds, SeatId(2))
                b.promptBridge(1).recordReveal(cardIds, SeatId(2))
            }

            // AI hand zone should be Public with both players as viewers
            val aiHandZone = gsm.zonesList.first { it.zoneId == ZoneIds.P2_HAND }
            aiHandZone.visibility shouldBe Visibility.Public
            aiHandZone.viewersList shouldHaveSize 2

            // Real hand cards should appear as GameObjectInfo with Public visibility
            val handCards = gsm.gameObjectsList.filter {
                it.type == GameObjectType.Card && it.zoneId == ZoneIds.P2_HAND
            }
            handCards shouldHaveSize 1
            handCards.first().visibility shouldBe Visibility.Public

            // Clean up
            b.promptBridge(1).activeReveal = null
        }

        test("stale activeReveal without pending prompt is auto-cleared") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Lightning Bolt", ai, ZoneType.Hand)
            }
            val aiHand = game.aiPlayer.getZone(ZoneType.Hand).cards.toList()
            val cardIds = aiHand.map { ForgeCardId(it.id) }

            // First build: proxies allocated
            base.captureAfterAction(b, game, counter) {
                b.promptBridge(1).activeReveal = InteractivePromptBridge.ActiveReveal(cardIds, SeatId(2))
                b.promptBridge(1).recordReveal(cardIds, SeatId(2))
            }
            b.activeRevealProxies.size shouldBe 1

            // Second build: no prompt pending → stale guard clears activeReveal
            val gsm2 = base.captureAfterAction(b, game, counter) {}

            b.promptBridge(1).activeReveal.shouldBeNull()
            b.activeRevealProxies.size shouldBe 0
            // Cleanup annotations emitted
            gsm2.annotationOrNull(AnnotationType.RevealedCardDeleted).shouldNotBeNull()
        }

        test("clearing activeReveal triggers proxy cleanup with RevealedCardDeleted") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Lightning Bolt", ai, ZoneType.Hand)
            }
            val aiHand = game.aiPlayer.getZone(ZoneType.Hand).cards.toList()
            val cardIds = aiHand.map { ForgeCardId(it.id) }

            // Build with reveal active
            base.captureAfterAction(b, game, counter) {
                b.promptBridge(1).activeReveal = InteractivePromptBridge.ActiveReveal(cardIds, SeatId(2))
                b.promptBridge(1).recordReveal(cardIds, SeatId(2))
            }

            // Clear reveal (simulates choice completion)
            b.promptBridge(1).activeReveal = null

            // Next GSM should emit RevealedCardDeleted and clean up proxies
            val gsm = base.captureAfterAction(b, game, counter) {}

            val deleteAnn = gsm.annotationOrNull(AnnotationType.RevealedCardDeleted)
            deleteAnn.shouldNotBeNull()

            // Proxies should not appear in the diff objects
            val proxyObjects = gsm.gameObjectsList.filter { it.type == GameObjectType.RevealedCard }
            proxyObjects.shouldBeEmpty()
        }

        test("no active reveal produces no proxies") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Lightning Bolt", ai, ZoneType.Hand)
            }

            val gsm = base.captureAfterAction(b, game, counter) {}

            val proxyObjects = gsm.gameObjectsList.filter { it.type == GameObjectType.RevealedCard }
            proxyObjects.shouldBeEmpty()
        }

        test("RequestBuilder produces correct SelectNReq for reveal-choose with valid targets") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Lightning Bolt", ai, ZoneType.Hand) // noncreature nonland
                base.addCard("Grizzly Bears", ai, ZoneType.Hand) // creature (filtered out)
            }

            val bolt = game.aiPlayer.getZone(ZoneType.Hand).cards.first { it.name == "Lightning Bolt" }
            val bears = game.aiPlayer.getZone(ZoneType.Hand).cards.first { it.name == "Grizzly Bears" }

            val boltId = b.getOrAllocInstanceId(ForgeCardId(bolt.id)).value
            val bearsId = b.getOrAllocInstanceId(ForgeCardId(bears.id)).value

            val prompt = InteractivePromptBridge.PendingPrompt(
                promptId = "test",
                request = PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card to discard",
                    options = listOf("Lightning Bolt"),
                    min = 1,
                    max = 1,
                    semantic = PromptSemantic.RevealChoose,
                    candidateRefs = listOf(
                        PromptCandidateRefDto(0, "card", bolt.id, "Hand"),
                    ),
                    unfilteredRefs = listOf(
                        PromptCandidateRefDto(0, "card", bolt.id),
                        PromptCandidateRefDto(1, "card", bears.id),
                    ),
                    sourceEntityId = 999, // synthetic source
                ),
                future = java.util.concurrent.CompletableFuture(),
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

        test("RequestBuilder produces empty ids for reveal-choose with no valid targets") {
            val (b, game, counter) = base.startWithBoard { _, _, ai ->
                base.addCard("Grizzly Bears", ai, ZoneType.Hand) // creature (all filtered)
            }

            val bears = game.aiPlayer.getZone(ZoneType.Hand).cards.first()
            val bearsId = b.getOrAllocInstanceId(ForgeCardId(bears.id)).value

            val prompt = InteractivePromptBridge.PendingPrompt(
                promptId = "test",
                request = PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card to discard",
                    options = emptyList(),
                    min = 0,
                    max = 0,
                    semantic = PromptSemantic.RevealChoose,
                    candidateRefs = emptyList(), // no valid targets
                    unfilteredRefs = listOf(
                        PromptCandidateRefDto(0, "card", bears.id),
                    ),
                ),
                future = java.util.concurrent.CompletableFuture(),
            )

            val req = RequestBuilder.buildSelectNReq(prompt, b)

            assertSoftly {
                req.idsList.shouldBeEmpty()
                req.unfilteredIdsList shouldHaveSize 1
                req.unfilteredIdsList.first() shouldBe bearsId
                // minSel/maxSel should be 0 (not set) for empty ids
                req.minSel shouldBe 0
                req.maxSel shouldBe 0
            }
        }
    })
