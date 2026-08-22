package leyline.mechanics.blitz

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.detailInt
import leyline.testkit.hasCard
import leyline.testkit.persistentAnnotationsOfType
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class BlitzLifecycleTest :
    SessionTest({
        session(
            "Mayhem Patrol casts for Blitz, sacrifices itself, and draws",
            puzzle =
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
        ) {
            val patrolGrpId = cardGrpId("Mayhem Patrol")!!
            val blitzAbilityGrpId = keywordAbilityGrpId(patrolGrpId, KeywordAbilityIds.BLITZ)!!

            val snap = messageSnapshot()
            castSpellByName("Mayhem Patrol", alternativeGrpId = blitzAbilityGrpId).shouldBeTrue()
            passUntil(maxPasses = 20) { observe().stackSize == 0 }.shouldBeTrue()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == blitzAbilityGrpId }
            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe blitzAbilityGrpId
            }

            val returnedAndDrew: HeadlessMatch.() -> Boolean = {
                human.hasCard("Mayhem Patrol", ZoneType.Graveyard) && human.hasCard("Mountain", ZoneType.Hand)
            }
            assertSoftly {
                "Mayhem Patrol" should beOnBattlefieldOf(human)
                passUntil(maxPasses = 30, stopWhen = returnedAndDrew).shouldBeTrue()
            }

            assertSoftly {
                "Mayhem Patrol" should beInGraveyardOf(human)
                "Mountain" should beInHandOf(human)
            }
        }
    })
