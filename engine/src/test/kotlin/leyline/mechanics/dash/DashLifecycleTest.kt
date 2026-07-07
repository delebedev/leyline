package leyline.mechanics.dash

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest

class DashLifecycleTest :
    SessionTest({
        test("Zurgo Bellstriker casts for Dash and returns at end step") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Zurgo Bellstriker
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Island;Island;Island
                """.trimIndent(),
                name = "Dash Zurgo Bellstriker",
                validating = true,
            )

            val zurgoGrpId = harness.bridge.cardRepository.findGrpIdByName("Zurgo Bellstriker")!!
            val dashAbilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(zurgoGrpId, KeywordAbilityIds.DASH)!!

            castSpellByName("Zurgo Bellstriker", alternativeGrpId = dashAbilityGrpId).shouldBeTrue()
            passUntil(maxPasses = 20) { game().stack.isEmpty }.shouldBeTrue()

            human.hasCard("Zurgo Bellstriker", ZoneType.Battlefield).shouldBeTrue()
            passUntil(maxPasses = 30) { human.hasCard("Zurgo Bellstriker", ZoneType.Hand) }.shouldBeTrue()

            assertSoftly {
                human.hasCard("Zurgo Bellstriker", ZoneType.Hand).shouldBeTrue()
                human.hasCard("Zurgo Bellstriker", ZoneType.Battlefield).shouldBeFalse()
                human.hasCard("Zurgo Bellstriker", ZoneType.Graveyard).shouldBeFalse()
            }
        }
    })

private fun forge.game.player.Player.hasCard(
    name: String,
    zone: ZoneType,
): Boolean = getZone(zone).cards.any { it.name == name }
