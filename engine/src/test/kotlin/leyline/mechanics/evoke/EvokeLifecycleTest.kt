package leyline.mechanics.evoke

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class EvokeLifecycleTest :
    SessionTest({
        test("Mulldrifter casts for Evoke and sacrifices itself after entering") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Mulldrifter
                humanbattlefield=Island;Island;Island
                humanlibrary=Island;Island;Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Evoke Mulldrifter",
                validating = true,
            )

            val mulldrifterGrpId = harness.bridge.cardRepository.findGrpIdByName("Mulldrifter")!!
            val evokeAbilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(mulldrifterGrpId, KeywordAbilityIds.EVOKE)!!

            val snap = messageSnapshot()
            castSpellByName("Mulldrifter", alternativeGrpId = evokeAbilityGrpId).shouldBeTrue()
            passUntilResolved(maxPasses = 12)

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Mulldrifter"
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Mulldrifter" } shouldBe false
                val cto =
                    messagesSince(snap)
                        .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                        .first { it.detailInt("alternateCostGrpId") == evokeAbilityGrpId }
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe evokeAbilityGrpId
            }
        }
    })
