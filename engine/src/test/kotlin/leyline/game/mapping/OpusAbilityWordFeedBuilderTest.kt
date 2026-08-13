package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.testkit.BoardTest
import leyline.testkit.detailString

class OpusAbilityWordFeedBuilderTest :
    BoardTest({
        test("collapsed five-plus Opus trigger retains the player to ability marker") {
            val board = startWithBoard { _, human, _ -> addCard("Tackle Artist", human) }
            val event = opusEvent(active = true)
            val frameIds = FrameIdResolver(board.bridge.projectionIdentityWorkspace())
            val expectedAbilityIid = frameIds.triggerStackAbilityIid(event.abilityForgeId).value

            val marker = OpusAbilityWordFeedBuilder.build(listOf(event), frameIds).single()

            assertSoftly {
                marker.affectorId shouldBe 1
                marker.affectedIdsList shouldBe listOf(expectedAbilityIid)
                marker.detailString("AbilityWordName") shouldBe "Opus"
                marker.detailsList.map { it.key }.toSet() shouldBe setOf("AbilityWordName")
            }
        }

        test("collapsed below-five Opus trigger emits no marker") {
            val board = startWithBoard { _, human, _ -> addCard("Tackle Artist", human) }

            OpusAbilityWordFeedBuilder
                .build(listOf(opusEvent(active = false)), FrameIdResolver(board.bridge.projectionIdentityWorkspace()))
                .shouldBeEmpty()
        }
    })

private fun opusEvent(active: Boolean) =
    GameEvent.SpellCast(
        cardId = ForgeCardId(100),
        seatId = SeatId(1),
        isAbility = true,
        isTrigger = true,
        abilityForgeId = 200,
        opusActive = active,
    )
