package leyline.bridge.coord

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectInteractionRuntime
import leyline.bridge.handoff.CardSelectInteractionTimeoutException
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderInteractionResult
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.OrderMoveIntent
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.bridge.types.Seating

class TargetingCoordinatorTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("library order uses revealed library position when present") {
            val bridge = testPromptBridge()
            val coordinator = TargetingCoordinator(bridge, testSeating)

            coordinator.orderMoveToZoneList(
                orderCards(),
                ZoneType.Library,
                abilitySub(
                    ApiType.DigUntil,
                    mapOf("RevealedLibraryPosition" to "-1"),
                ),
            )

            bridge.history.shouldHaveSize(1)
            bridge.history.single().semantic shouldBe PromptSemantic.OrderForBottom
        }

        test("dig library order defaults to bottom unless an explicit position overrides it") {
            assertSoftly {
                promptSemantic(abilitySub(ApiType.Dig)) shouldBe PromptSemantic.OrderForBottom
                promptSemantic(abilitySub(ApiType.Dig, mapOf("LibraryPosition2" to "0"))) shouldBe
                    PromptSemantic.OrderForTop
            }
        }

        test("legend rule returns the exact selected handle and records every unchosen victim") {
            val cards = legendCards()
            val bridge = testPromptBridge(cardSelectRuntime = selectingCard(1))
            val coordinator = TargetingCoordinator(bridge, testSeating)

            val chosen =
                coordinator.chooseSingleEntity(
                    cards,
                    abilitySub(ApiType.InternalLegendaryRule),
                    "Choose a legendary permanent to keep",
                    isOptional = false,
                    hasDelayedReveal = false,
                )

            assertSoftly {
                chosen shouldBeSameInstanceAs cards[1]
                bridge.journal.consumeLegendVictim(ForgeCardId(cards[0].id)) shouldBe true
                bridge.journal.consumeLegendVictim(ForgeCardId(cards[1].id)) shouldBe false
                (bridge.history.single().route as ResolvedPromptRoute.CardSelect).descriptor.kind shouldBe
                    CardSelectKind.LegendRule
                bridge.history.single().result shouldBe listOf(1)
            }
        }

        test("legend rule timeout keeps the configured default and records the other victim") {
            val cards = legendCards()
            var timedOut = false
            val bridge = testPromptBridge(cardSelectRuntime = timingOutCardSelect()).also { it.timeoutListener = { timedOut = true } }
            val coordinator = TargetingCoordinator(bridge, testSeating)

            val chosen =
                coordinator.chooseSingleEntity(
                    cards,
                    abilitySub(ApiType.InternalLegendaryRule),
                    "Choose a legendary permanent to keep",
                    isOptional = false,
                    hasDelayedReveal = false,
                )

            assertSoftly {
                chosen shouldBeSameInstanceAs cards[0]
                timedOut shouldBe true
                bridge.journal.consumeLegendVictim(ForgeCardId(cards[0].id)) shouldBe false
                bridge.journal.consumeLegendVictim(ForgeCardId(cards[1].id)) shouldBe true
                bridge.history.single().outcome shouldBe InteractivePromptBridge.PromptCallStatus.TIMEOUT
                bridge.history.single().result shouldBe listOf(0)
            }
        }
    })

private fun promptSemantic(sa: SpellAbility): PromptSemantic {
    val bridge = testPromptBridge()
    val coordinator = TargetingCoordinator(bridge, testSeating)

    coordinator.orderMoveToZoneList(orderCards(), ZoneType.Library, sa)

    bridge.history.shouldHaveSize(1)
    return bridge.history.single().semantic
}

private fun abilitySub(
    api: ApiType,
    params: Map<String, String> = emptyMap(),
): AbilitySub = AbilitySub(api, Card(7, null).also { it.name = "Host" }, null, params)

private val testSeating = Seating(humanSeat = SeatId(1), familiarSeat = SeatId(2))

private fun testPromptBridge(cardSelectRuntime: CardSelectInteractionRuntime? = null): InteractivePromptBridge =
    InteractivePromptBridge(timeoutMs = 1, strict = false).also { bridge ->
        bridge.cardSelectRuntime = cardSelectRuntime
        bridge.orderRuntime =
            object : OrderInteractionRuntime {
                override fun awaitOrder(
                    request: PromptRequest,
                    candidateHandles: List<Card>,
                    move: OrderMoveIntent?,
                    timeoutMs: Long?,
                ): OrderInteractionResult = OrderInteractionResult(candidateHandles.indices.toList(), candidateHandles)
            }
    }

private fun orderCards(): CardCollection =
    CardCollection(
        listOf(
            Card(1, null).also { it.name = "First" },
            Card(2, null).also { it.name = "Second" },
        ),
    )

private fun legendCards(): CardCollection =
    CardCollection(
        listOf(
            Card(10, null).also { it.name = "First legend" },
            Card(11, null).also { it.name = "Second legend" },
        ),
    )

private fun selectingCard(index: Int): CardSelectInteractionRuntime =
    object : CardSelectInteractionRuntime {
        override fun awaitSelection(
            request: PromptRequest,
            candidateHandles: List<Card>,
            timeoutMs: Long?,
        ): CardSelectInteractionResult = CardSelectInteractionResult(listOf(index), listOf(candidateHandles[index]))
    }

private fun timingOutCardSelect(): CardSelectInteractionRuntime =
    object : CardSelectInteractionRuntime {
        override fun awaitSelection(
            request: PromptRequest,
            candidateHandles: List<Card>,
            timeoutMs: Long?,
        ): CardSelectInteractionResult = throw CardSelectInteractionTimeoutException()
    }
