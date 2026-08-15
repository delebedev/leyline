package leyline.bridge.forge

import forge.game.ability.AbilityKey
import forge.game.card.CounterEnumType
import forge.game.cost.CostRemoveAnyCounter
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import leyline.testkit.Board
import leyline.testkit.BoardTest

/** CostDecision producer split: the grounded row uses GatherCounters; other shapes stay residual. */
class CostDecisionCounterTest :
    BoardTest({
        data class Fixture(
            val board: Board,
            val bridge: GameBridge,
            val player: Player,
            val source: forge.game.card.Card,
            val ability: SpellAbility,
            val decision: CostDecision,
        )

        fun fixture(): Fixture {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                }
            val localBridge = board.bridge
            val player = board.human
            val source = player.getCardsIn(ZoneType.Battlefield).first { it.name == "Hopeful Initiate" }
            val ability = source.spellAbilities.first { it.isActivatedAbility() }
            ability.activatingPlayer = player
            return Fixture(
                board,
                localBridge,
                player,
                source,
                ability,
                CostDecision(
                    localBridge.humanController ?: error("No human controller"),
                    player,
                    ability,
                    false,
                    localBridge.promptBridge(SeatId(1)),
                ),
            )
        }

        fun addCounters(fx: Fixture) {
            fx.player
                .getCardsIn(ZoneType.Battlefield)
                .filter { it.name == "Hopeful Initiate" }
                .forEach { card ->
                    card.addCounterInternal(CounterEnumType.P1P1, 1, fx.player, true, null, AbilityKey.newMap())
                }
        }

        test("Hopeful Initiate amount-two row gathers exact first-fit handles across sources") {
            val fx = fixture()
            addCounters(fx)
            val result =
                fx.decision.visit(
                    CostRemoveAnyCounter("2", CounterEnumType.P1P1, "Creature", "creature", false),
                )
            val creatures = fx.player.getCardsIn(ZoneType.Battlefield).filter { it.name == "Hopeful Initiate" }
            assertSoftly {
                result!!.counterTable.get(null, creatures[0], CounterEnumType.P1P1) shouldBe 1
                result.counterTable.get(null, creatures[1], CounterEnumType.P1P1) shouldBe 1
                fx.bridge.promptBridge(SeatId(1)).history shouldBe emptyList()
            }
        }

        test("unsupported counter amount remains the residual chooser") {
            val fx = fixture()
            addCounters(fx)
            val result =
                NonInteractiveScope.quiet {
                    fx.decision.visit(
                        CostRemoveAnyCounter("1", CounterEnumType.P1P1, "Creature", "creature", false),
                    )
                }
            val first = fx.player.getCardsIn(ZoneType.Battlefield).first { it.name == "Hopeful Initiate" }
            assertSoftly {
                result!!.counterTable.get(null, first, CounterEnumType.P1P1) shouldBe 1
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history.size shouldBe 1
            }
        }
    })
