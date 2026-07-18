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
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
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

private fun testPromptBridge(): InteractivePromptBridge = InteractivePromptBridge(timeoutMs = 1, strict = false)

private fun orderCards(): CardCollection =
    CardCollection(
        listOf(
            Card(1, null).also { it.name = "First" },
            Card(2, null).also { it.name = "Second" },
        ),
    )
