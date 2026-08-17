package leyline.mechanics.overload

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

private val PUZZLE =
    """
    [metadata]
    Name:Overload Mizzium Mortars
    Goal:Cast Mizzium Mortars with Overload.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Mizzium Mortars
    humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    humanlibrary=Mountain;Mountain;Mountain
    aibattlefield=Serra Angel;Raging Goblin
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class OverloadLifecycleTest :
    SessionTest({
        session("overloaded Mizzium Mortars is targetless and hits each opposing creature", puzzle = PUZZLE) {
            val mortarsGrpId = bridge.cardRepository.findGrpIdByName("Mizzium Mortars")!!
            val overloadAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(mortarsGrpId, KeywordAbilityIds.OVERLOAD)!!

            val snap = messageSnapshot()
            castSpellByName("Mizzium Mortars", alternativeGrpId = overloadAbilityGrpId).shouldBeTrue()
            passUntilResolved()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == overloadAbilityGrpId }
            val aiBattlefieldNames = ai.getZone(ZoneType.Battlefield).cards.map { it.name }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                aiBattlefieldNames.count { it == "Serra Angel" } shouldBe 0
                aiBattlefieldNames.count { it == "Raging Goblin" } shouldBe 0
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Mizzium Mortars" }
                    .shouldBeTrue()
            }
        }
    })
