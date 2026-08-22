package leyline.mechanics.jumpstart

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

private val PUZZLE =
    """
    [metadata]
    Name:Jump-start Radical Idea
    Goal:Cast Radical Idea from graveyard by discarding a card.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Coral Merfolk
    humangraveyard=Radical Idea
    humanbattlefield=Island;Island
    humanlibrary=Island;Island;Island
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

class JumpStartLifecycleTest :
    SessionTest({
        session("Jump-start prompts for discard, resolves, and exiles the spell", puzzle = PUZZLE) {
            val radicalIdeaGrpId = cardGrpId("Radical Idea")!!
            val jumpStartAbilityGrpId =
                keywordAbilityGrpId(radicalIdeaGrpId, KeywordAbilityIds.JUMP_START)!!

            val snap = messageSnapshot()
            castSpellByName("Radical Idea", zone = ZoneType.Graveyard, alternativeGrpId = jumpStartAbilityGrpId).shouldBeTrue()

            val discardReq = lastSelectNReq()
            val discardId = findInstanceId(discardReq.idsList, "Coral Merfolk")
            assertSoftly {
                discardReq.context shouldBe SelectionContext.Discard_a163
                discardReq.listType shouldBe SelectionListType.Static
                discardReq.optionContext shouldBe OptionContext.Payment
                discardReq.minSel shouldBe 0
                discardReq.maxSel shouldBe 1
                discardReq.idsList shouldHaveSize 1
            }

            respondToSelectN(listOf(discardId))
            passPriority()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("castAbilityGrpId") == jumpStartAbilityGrpId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Radical Idea" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .none { it.name == "Radical Idea" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .any { it.name == "Island" }
                    .shouldBeTrue()
            }
        }

        session(
            "Jump-start pays the client-selected discard among multiple cards",
            puzzle =
                """
                [metadata]
                Name:Jump-start Radical Idea Multiple Discards
                Goal:Choose which card pays the Jump-start discard cost.
                Turns:3
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Coral Merfolk;Island
                humangraveyard=Radical Idea
                humanbattlefield=Island;Island
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val radicalIdeaGrpId = cardGrpId("Radical Idea")!!
            val jumpStartAbilityGrpId =
                keywordAbilityGrpId(radicalIdeaGrpId, KeywordAbilityIds.JUMP_START)!!

            castSpellByName("Radical Idea", zone = ZoneType.Graveyard, alternativeGrpId = jumpStartAbilityGrpId).shouldBeTrue()

            val discardReq = lastSelectNReq()
            val coralMerfolkId = findInstanceId(discardReq.idsList, "Coral Merfolk")
            assertSoftly {
                discardReq.context shouldBe SelectionContext.Discard_a163
                discardReq.idsList shouldHaveSize 2
            }

            respondToSelectN(listOf(coralMerfolkId))
            passPriority()

            assertSoftly {
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Radical Idea" }
                    .shouldBeTrue()
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Coral Merfolk" }
                    .shouldBeTrue()
                human.getZone(ZoneType.Hand).cards.filter { it.name == "Island" } shouldHaveSize 2
            }
        }
    })
