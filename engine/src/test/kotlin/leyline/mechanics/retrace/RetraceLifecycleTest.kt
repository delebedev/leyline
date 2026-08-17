package leyline.mechanics.retrace

import forge.game.spellability.OptionalCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

class RetraceLifecycleTest :
    SessionTest({
        session(
            "Waves of Aggression casts from graveyard by discarding a land",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humangraveyard=Waves of Aggression
                humanhand=Plains;Mountain;Coral Merfolk
                humanbattlefield=Plains;Plains;Plains;Plains;Plains
                humanlibrary=Plains;Plains;Plains
                ailibrary=Island;Island;Island
                """.trimIndent(),
            validating = true,
        ) {
            val wavesGrpId = bridge.cardRepository.findGrpIdByName("Waves of Aggression")!!
            val retraceAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(wavesGrpId, KeywordAbilityIds.RETRACE)!!
            val waves = human.getZone(ZoneType.Graveyard).cards.first { it.name == "Waves of Aggression" }

            getAllCastableAbilities(waves, human)
                .firstOrNull { it.isOptionalCostPaid(OptionalCost.Retrace) }
                .shouldNotBeNull()

            val snap = messageSnapshot()
            castSpellByName("Waves of Aggression", zone = ZoneType.Graveyard, alternativeGrpId = retraceAbilityGrpId).shouldBeTrue()

            val discardReq = lastSelectNReq()
            val plainsId = findInstanceId(discardReq.idsList, "Plains")
            assertSoftly {
                discardReq.context shouldBe SelectionContext.Discard_a163
                discardReq.listType shouldBe SelectionListType.Static
                discardReq.optionContext shouldBe OptionContext.Payment
                discardReq.minSel shouldBe 0
                discardReq.maxSel shouldBe 1
                discardReq.idsList shouldHaveSize 2
            }

            respondToSelectN(listOf(plainsId))
            passUntil(maxPasses = 12) { game().stack.isEmpty }.shouldBeTrue()

            val graveyardNames = human.getZone(ZoneType.Graveyard).cards.map { it.name }
            assertSoftly {
                graveyardNames shouldContain "Waves of Aggression"
                graveyardNames shouldContain "Plains"
                val cto =
                    messagesSince(snap)
                        .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                        .first { it.detailInt("alternateCostGrpId") == retraceAbilityGrpId }
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe retraceAbilityGrpId
            }
        }
    })
