package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.StaticChoiceIds
import leyline.game.codes.DetailKeys
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.lastGsmMatching
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

private val HERALDIC_BANNER_PUZZLE =
    """
    # Design Narrative
    # Mechanic: Heraldic Banner's static color choice prompt and chosen-color power buff.
    # Forcing: Heraldic Banner is the only hand card; choosing Red makes the existing Raging Goblin visibly stronger.
    # AI behavior: Opponent has no board or hand decisions; the scripted player casts the artifact and answers the static prompt.
    # Failure modes:
    #   WIN = Heraldic Banner resolved; the acceptance suite checks Raging Goblin's buffed stats.
    #   LOSE = the artifact could not be cast or did not resolve within the turn budget.
    #   TIMEOUT = the static SelectN prompt was not emitted or the response did not unblock resolution.
    # Card roles:
    #   Heraldic Banner - source of the color static choice and chosen-color anthem.
    #   Raging Goblin - red creature whose power buff proves the chosen color was stored and applied.
    #   Mountains - pay the three generic mana cost.
    # Protocol path: Cast action -> priority pass -> SelectNReq static color -> SelectNResp id 4 -> ChoiceResult and persistent LinkInfo.

    [metadata]
    Name:Heraldic Banner Static Choice
    Goal:Play the Specified Permanent
    Turns:3
    Difficulty:Easy
    Description:Cast Heraldic Banner, choose Red, and verify the chosen-color buff applies.
    Targets:Heraldic Banner

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Mountain;Mountain;Mountain;Raging Goblin
    humanhand=Heraldic Banner
    humanlibrary=Mountain
    ailibrary=Forest
    """.trimIndent()

class BannerStaticChoiceTest :
    SessionTest({
        session(
            "Patchwork Banner exposes the full creature subtype static subset",
            puzzleFile = "puzzles/patchwork-banner-static-choice.pzl",
        ) {
            val req = castSpellUntilSelectNReq("Patchwork Banner")
            val ids = req.idsList

            assertSoftly {
                req.listType shouldBe SelectionListType.StaticSubset
                req.staticList shouldBe StaticList.SubTypes
                req.idsCount shouldBeGreaterThan 200
                ids shouldContain StaticChoiceIds.subtypeIdFor("Goblin")!!
                ids shouldContain StaticChoiceIds.subtypeIdFor("Berserker")!!
                ids shouldContain StaticChoiceIds.subtypeIdFor("Human")!!
                ids shouldContain StaticChoiceIds.subtypeIdFor("Kithkin")!!
            }
        }

        session(
            "Patchwork Banner static subtype choice emits ChoiceResult and LinkInfo",
            puzzleFile = "puzzles/patchwork-banner-static-choice.pzl",
        ) {
            castSpellUntilSelectNReq("Patchwork Banner")
            val goblinId = StaticChoiceIds.subtypeIdFor("Goblin")!!
            respondToSelectN(listOf(goblinId))
            passUntilResolved()

            val choiceResult =
                checkNotNull(
                    allMessages.lastGsmMatching { gsm ->
                        gsm.annotationsList.any { AnnotationType.ChoiceResult in it.typeList }
                    },
                ) { "No ChoiceResult annotation emitted" }
                    .annotationsList
                    .annotation(AnnotationType.ChoiceResult)
            val linkInfo =
                checkNotNull(
                    allMessages.lastGsmMatching { gsm ->
                        gsm.persistentAnnotationsList.any { AnnotationType.LinkInfo in it.typeList }
                    },
                ) { "No persistent LinkInfo annotation emitted" }
                    .persistentAnnotationsList
                    .annotation(AnnotationType.LinkInfo)

            assertSoftly {
                choiceResult.detailInt(DetailKeys.CHOICE_VALUE) shouldBe goblinId
                choiceResult.detailInt(DetailKeys.CHOICE_DOMAIN) shouldBe 5
                linkInfo.affectedIdsList shouldBe listOf(6, goblinId)
                linkInfo.detailString(DetailKeys.CHOOSE_LINK_TYPE) shouldBe "Type"
                linkInfo.detailInt(DetailKeys.SOURCE_ABILITY_GRPID) shouldBe 176647
            }
        }

        session(
            "Heraldic Banner static color choice emits ChoiceResult and LinkInfo",
            puzzle = HERALDIC_BANNER_PUZZLE,
        ) {
            castSpellUntilSelectNReq("Heraldic Banner")
            val redId = StaticChoiceIds.colorIdForName("Red")!!
            respondToSelectN(listOf(redId))
            passUntilResolved()

            val choiceResult =
                checkNotNull(
                    allMessages.lastGsmMatching { gsm ->
                        gsm.annotationsList.any { AnnotationType.ChoiceResult in it.typeList }
                    },
                ) { "No ChoiceResult annotation emitted" }
                    .annotationsList
                    .annotation(AnnotationType.ChoiceResult)
            val linkInfo =
                checkNotNull(
                    allMessages.lastGsmMatching { gsm ->
                        gsm.persistentAnnotationsList.any { AnnotationType.LinkInfo in it.typeList }
                    },
                ) { "No persistent LinkInfo annotation emitted" }
                    .persistentAnnotationsList
                    .annotation(AnnotationType.LinkInfo)

            assertSoftly {
                choiceResult.detailInt(DetailKeys.CHOICE_VALUE) shouldBe redId
                choiceResult.detailInt(DetailKeys.CHOICE_DOMAIN) shouldBe 6
                linkInfo.affectedIdsList shouldBe listOf(redId)
                linkInfo.detailString(DetailKeys.CHOOSE_LINK_TYPE) shouldBe "Color"
                linkInfo.detailInt(DetailKeys.SOURCE_ABILITY_GRPID) shouldBe 88237
            }
        }
    })
