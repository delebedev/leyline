package leyline.mechanics.dash

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.detailInt
import leyline.testkit.hasCard
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class DashLifecycleTest :
    SessionTest({
        session(
            "Zurgo Bellstriker casts for Dash and returns at end step",
            puzzle =
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
            validating = true,
        ) {
            val zurgoGrpId = bridge.cardRepository.findGrpIdByName("Zurgo Bellstriker")!!
            val dashAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(zurgoGrpId, KeywordAbilityIds.DASH)!!

            val snap = messageSnapshot()
            castSpellByName("Zurgo Bellstriker", alternativeGrpId = dashAbilityGrpId).shouldBeTrue()
            passUntil(maxPasses = 20) { game().stack.isEmpty }.shouldBeTrue()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == dashAbilityGrpId }
            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe dashAbilityGrpId
                "Zurgo Bellstriker" should beOnBattlefieldOf(human)
                passUntil(maxPasses = 30) { human.hasCard("Zurgo Bellstriker", ZoneType.Hand) }.shouldBeTrue()
            }

            assertSoftly {
                "Zurgo Bellstriker" should beInHandOf(human)
                "Zurgo Bellstriker" shouldNot beOnBattlefieldOf(human)
                "Zurgo Bellstriker" shouldNot beInGraveyardOf(human)
            }
        }
    })
