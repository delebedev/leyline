package leyline.game.annotations

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferCategoryResolver
import leyline.game.event.GameEvent
import leyline.game.event.Zone

class TransferCategoryTest :
    FunSpec({

        tags(UnitTag)

        test("event resolver keeps SpellCast when cast and resolve share an event drain") {
            val cardId = ForgeCardId(42)
            val events =
                listOf(
                    GameEvent.SpellCast(cardId = cardId, seatId = SeatId(1)),
                    GameEvent.ZoneChanged(cardId = cardId, from = Zone.Stack, to = Zone.Battlefield),
                    GameEvent.SpellResolved(cardId = cardId, hasFizzled = false),
                )

            TransferCategoryResolver.categoryFromEvents(cardId, events) shouldBe TransferCategory.CastSpell
        }

        test("cast announcement remains CastSpell before a stack zone change") {
            val cardId = ForgeCardId(42)
            val events =
                listOf(
                    GameEvent.SpellCast(cardId = cardId, seatId = SeatId(1)),
                    GameEvent.ZoneChanged(cardId = cardId, from = Zone.Hand, to = Zone.Stack),
                )

            TransferCategoryResolver.categoryFromEvents(cardId, events) shouldBe TransferCategory.CastSpell
        }
    })
