package leyline.mechanics.surge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class SurgeLifecycleTest :
    SessionTest({
        session(
            "Crush of Tentacles casts with Surge after another spell this turn",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Crush of Tentacles
                humanbattlefield=Mountain;Island;Island;Island;Island;Island
                humanlibrary=Island;Island;Island
                ailibrary=Island;Island;Island
                """.trimIndent(),
        ) {
            val crushGrpId = cardGrpId("Crush of Tentacles")!!
            val surgeAbilityGrpId = keywordAbilityGrpId(crushGrpId, KeywordAbilityIds.SURGE)!!
            val crush = human.getZone(ZoneType.Hand).cards.first { it.name == "Crush of Tentacles" }

            crush.abilityIds.shouldNotBeEmpty()

            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            val snap = messageSnapshot()
            castSpellByName("Crush of Tentacles", alternativeGrpId = surgeAbilityGrpId).shouldBeTrue()
            passUntil(maxPasses = 12) { observe().stackSize == 0 }.shouldBeTrue()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == surgeAbilityGrpId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Crush of Tentacles"
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Shock"
                ai.life shouldBe 18
            }
        }
    })
