package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.InstanceId
import leyline.conformance.ConformanceTestBase
import leyline.game.mapper.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Regression test for #39: autotap should prefer lands over mana dorks.
 * Puzzle: Pacifism (1W) in hand, 4 Plains + Forest + Llanowar Elves on battlefield.
 * Expected: autoTapSolution taps 2 Plains (not Llanowar Elves).
 *
 * Note: this tests our proto hint (autoTapSolution), which is correct.
 * The actual bug is in Forge's ComputerUtilMana at spell resolution time.
 */
class AutotapDorkTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("autoTapSolution prefers lands over Llanowar Elves for Pacifism (1W)") {
            val puzzleText = java.io.File("src/test/resources/puzzles/autotap-dork.pzl").readText()
            val (bridge, game, _) = base.startPuzzleAtMain1(puzzleText)

            val actions = ActionMapper.buildActions(game, 1, bridge)

            // Find the Cast action for Pacifism
            val castAction = actions.actionsList.firstOrNull {
                it.actionType == ActionType.Cast
            }
            castAction.shouldNotBeNull()

            val autoTap = castAction.autoTapSolution
            autoTap.shouldNotBeNull()

            // All tapped sources should be lands (Plains), not Llanowar Elves
            val human = game.players.first { it.name != "AI" }
            for (tap in autoTap.autoTapActionsList) {
                val forgeId = bridge.getForgeCardId(InstanceId(tap.instanceId))
                forgeId.shouldNotBeNull()
                val card = human.getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards.firstOrNull { it.id == forgeId.value }
                card.shouldNotBeNull()
                card.isLand shouldBe true
            }
        }
    })
