package leyline.mechanics.spectacle

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
    Name:Spectacle Spawn of Mayhem
    Goal:Cast Spawn of Mayhem with Spectacle.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Shock;Spawn of Mayhem
    humanbattlefield=Mountain;Swamp;Swamp;Swamp
    humanlibrary=Mountain;Mountain;Mountain
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class SpectacleLifecycleTest :
    SessionTest({
        test("Spawn of Mayhem casts with Spectacle after opponent lost life") {
            startPuzzleRaw(PUZZLE)
            val spawnGrpId = harness.bridge.cardRepository.findGrpIdByName("Spawn of Mayhem")!!
            val spectacleAbilityGrpId =
                harness.bridge.cardRepository.findKeywordAbilityGrpId(spawnGrpId, KeywordAbilityIds.SPECTACLE)!!

            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            val snap = messageSnapshot()
            castSpellByName("Spawn of Mayhem", alternativeGrpId = spectacleAbilityGrpId).shouldBeTrue()
            passUntilResolved()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == spectacleAbilityGrpId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                ai.life shouldBe 18
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Spawn of Mayhem" }
                    .shouldBeTrue()
            }
        }
    })
