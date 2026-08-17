package leyline.mechanics.harmonize

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
    Name:Harmonize Winternight Stories
    Goal:Cast Winternight Stories from graveyard with Harmonize.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humangraveyard=Winternight Stories
    humanhand=Island;Island
    humanbattlefield=Island;Island;Island;Island;Island
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class HarmonizeLifecycleTest :
    SessionTest({
        session("Winternight Stories casts from graveyard with Harmonize", puzzle = PUZZLE) {
            val cardGrpId = bridge.cardRepository.findGrpIdByName("Winternight Stories")!!
            val harmonizeAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.HARMONIZE)!!

            val snap = messageSnapshot()
            castSpellByName(
                "Winternight Stories",
                zone = forge.game.zone.ZoneType.Graveyard,
                alternativeGrpId = harmonizeAbilityGrpId,
            ).shouldBeTrue()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == harmonizeAbilityGrpId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe harmonizeAbilityGrpId
            }
        }
    })
