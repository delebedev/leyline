package leyline.mechanics.blitz

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class BlitzLifecycleTest :
    SessionTest({
        test("Mayhem Patrol casts for Blitz, sacrifices itself, and draws") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Mayhem Patrol
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Island;Island;Island
                """.trimIndent(),
                name = "Blitz Mayhem Patrol",
                validating = true,
            )

            val patrolGrpId = harness.bridge.cardRepository.findGrpIdByName("Mayhem Patrol")!!
            val blitzAbilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(patrolGrpId, KeywordAbilityIds.BLITZ)!!

            val snap = messageSnapshot()
            castSpellByName("Mayhem Patrol", alternativeGrpId = blitzAbilityGrpId).shouldBeTrue()
            passUntil(maxPasses = 20) { game().stack.isEmpty }.shouldBeTrue()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == blitzAbilityGrpId }
            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe blitzAbilityGrpId
            }

            human.hasCard("Mayhem Patrol", ZoneType.Battlefield).shouldBeTrue()
            val returnedAndDrew = { human.hasCard("Mayhem Patrol", ZoneType.Graveyard) && human.hasCard("Mountain", ZoneType.Hand) }
            passUntil(
                maxPasses = 30,
                stopWhen = returnedAndDrew,
            ).shouldBeTrue()

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Mayhem Patrol"
                human.hasCard("Mayhem Patrol", ZoneType.Battlefield).shouldBeFalse()
                human.hasCard("Mountain", ZoneType.Hand).shouldBeTrue()
            }
        }
    })

private fun forge.game.player.Player.hasCard(
    name: String,
    zone: ZoneType,
): Boolean = getZone(zone).cards.any { it.name == name }
