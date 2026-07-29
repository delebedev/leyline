package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.ActionToken
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.PriorityActionValue
import leyline.game.PriorityPlayKind

class PriorityActionPreparationTest :
    FunSpec({
        tags(UnitTag)

        test("binds exact command metadata to ordered owner tokens") {
            val cardId = ForgeCardId(7)
            val play = PriorityActionValue.PlayLand(PriorityPlayKind.LAND, cardId, 100, shouldStop = true)
            val builder = PriorityActionPreparationBuilder()
            builder.addAction(play, PlayerAction.PlayLand(cardId))
            builder.addAction(
                PriorityActionValue.Pass,
                PlayerAction.PassPriority,
                stackAbilityGrpId = 20,
                forgeAbilityId = 30,
                spellGrpId = 40,
            )

            val preparation = builder.build()
            val window =
                preparation.bindTokens(
                    "priority-1",
                    listOf(ActionToken("play"), ActionToken("pass")),
                )

            assertSoftly {
                preparation.commands shouldBe listOf(PlayerAction.PlayLand(cardId), PlayerAction.PassPriority)
                window.actions.actions shouldBe listOf(play, PriorityActionValue.Pass)
                window.offers.map { it.token } shouldBe listOf(ActionToken("play"), ActionToken("pass"))
                window.offers[0].cardId shouldBe cardId
                window.offers[1].stackAbilityGrpId shouldBe 20
                window.offers[1].forgeAbilityId shouldBe 30
                window.offers[1].spellGrpId shouldBe 40
            }
        }

        test("rejects a partial token batch") {
            val builder = PriorityActionPreparationBuilder()
            builder.addAction(PriorityActionValue.Pass, PlayerAction.PassPriority)

            shouldThrow<IllegalArgumentException> {
                builder.build().bindTokens("priority-1", emptyList())
            }
        }
    })
