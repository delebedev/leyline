package leyline.mechanics.cleave

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
    Name:Cleave Path of Peril
    Goal:Cast Path of Peril with Cleave.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Path of Peril
    humanbattlefield=Plains;Swamp;Plains;Swamp;Plains;Swamp
    humanlibrary=Plains;Plains;Plains
    aibattlefield=Serra Angel;Raging Goblin
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class CleaveLifecycleTest :
    SessionTest({
        session("cleaved Path of Peril uses alt-cost rail and applies full board wipe", puzzle = PUZZLE) {
            val pathGrpId = bridge.cardRepository.findGrpIdByName("Path of Peril")!!
            val cleaveAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(pathGrpId, KeywordAbilityIds.CLEAVE)!!

            val snap = messageSnapshot()
            castSpellByName("Path of Peril", alternativeGrpId = cleaveAbilityGrpId).shouldBeTrue()
            passUntilResolved()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == cleaveAbilityGrpId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                ai
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .none { it.name == "Serra Angel" }
                    .shouldBeTrue()
                ai
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .none { it.name == "Raging Goblin" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Path of Peril" }
                    .shouldBeTrue()
            }
        }

        session("regular Path of Peril keeps larger creatures alive", puzzle = PUZZLE) {
            castSpellByName("Path of Peril").shouldBeTrue()
            passUntilResolved()

            val aiBattlefieldNames = ai.getZone(ZoneType.Battlefield).cards.map { it.name }
            assertSoftly {
                aiBattlefieldNames.count { it == "Serra Angel" } shouldBe 1
                aiBattlefieldNames.count { it == "Raging Goblin" } shouldBe 0
                ai
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Serra Angel" }
                    .shouldBeTrue()
                ai
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .none { it.name == "Raging Goblin" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Path of Peril" }
                    .shouldBeTrue()
            }
        }
    })
