package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.testkit.BoardTest
import leyline.testkit.detailString

class VoidAbilityWordFeedBuilderTest :
    BoardTest({
        test("collapsed Void trigger retains the player to ability marker") {
            val board = startWithBoard { _, human, _ -> addCard("Insatiable Skittermaw", human) }
            val event = voidEvent(active = true)
            val frameIds = FrameIdResolver(board.bridge)
            val expectedAbilityIid = frameIds.triggerStackAbilityIid(event.abilityForgeId).value

            val marker = VoidAbilityWordFeedBuilder.build(listOf(event), frameIds).single()

            assertSoftly {
                marker.affectorId shouldBe 1
                marker.affectedIdsList shouldBe listOf(expectedAbilityIid)
                marker.detailString("AbilityWordName") shouldBe "Void"
                marker.detailsList.map { it.key }.toSet() shouldBe setOf("AbilityWordName")
            }
        }

        test("non-Void trigger emits no Void marker") {
            val board = startWithBoard { _, human, _ -> addCard("Insatiable Skittermaw", human) }

            VoidAbilityWordFeedBuilder
                .build(listOf(voidEvent(active = false)), FrameIdResolver(board.bridge))
                .shouldBeEmpty()
        }
    })

private fun voidEvent(active: Boolean) =
    GameEvent.SpellCast(
        cardId = ForgeCardId(100),
        seatId = SeatId(1),
        isAbility = true,
        isTrigger = true,
        abilityForgeId = 200,
        voidTrigger = active,
    )
