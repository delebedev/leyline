package leyline.mechanics.spectacle

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
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
        session("Spawn of Mayhem casts with Spectacle after opponent lost life", puzzle = PUZZLE) {
            val spawnGrpId = bridge.cardRepository.findGrpIdByName("Spawn of Mayhem")!!
            val spectacleAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(spawnGrpId, KeywordAbilityIds.SPECTACLE)!!

            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            if (game().stackZone.size() > 0) {
                passUntilResolved()
            }
            ai.life shouldBe 18

            val snap = messageSnapshot()
            castSpellByName("Spawn of Mayhem", alternativeGrpId = spectacleAbilityGrpId).shouldBeTrue()
            if (game().stackZone.size() > 0) {
                passUntilResolved()
            }

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == spectacleAbilityGrpId }
            val projectedStates = messagesSince(snap).gameStateMessages()
            val resolution =
                projectedStates
                    .first { gsm ->
                        gsm.annotationsList.any { annotation ->
                            AnnotationType.ZoneTransfer_af5a in annotation.typeList &&
                                annotation.detailString("category") == "Resolve" &&
                                cto.affectorId in annotation.affectedIdsList
                        }
                    }
            val resolutionPrefix = projectedStates.takeWhile { it.gameStateId <= resolution.gameStateId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                resolutionPrefix
                    .flatMap { it.annotationsList }
                    .count { AnnotationType.ModifiedLife in it.typeList } shouldBe 0
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Spawn of Mayhem" }
                    .shouldBeTrue()
            }
        }
    })
