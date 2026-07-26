package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.lastGsmMatching
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class BannerStaticChoiceTest :
    SessionTest({
        test("Patchwork Banner exposes the full creature subtype static subset") {
            startPuzzleFile("puzzles/patchwork-banner-static-choice.pzl", validating = true)

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

        test("Patchwork Banner static subtype choice emits ChoiceResult and LinkInfo") {
            startPuzzleFile("puzzles/patchwork-banner-static-choice.pzl", validating = true)

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
                    .first { AnnotationType.ChoiceResult in it.typeList }
            val linkInfo =
                checkNotNull(
                    allMessages.lastGsmMatching { gsm ->
                        gsm.persistentAnnotationsList.any { AnnotationType.LinkInfo in it.typeList }
                    },
                ) { "No persistent LinkInfo annotation emitted" }
                    .persistentAnnotationsList
                    .first { AnnotationType.LinkInfo in it.typeList }

            assertSoftly {
                choiceResult.detailInt(DetailKeys.CHOICE_VALUE) shouldBe goblinId
                choiceResult.detailInt(DetailKeys.CHOICE_DOMAIN) shouldBe 5
                linkInfo.affectedIdsList shouldBe listOf(6, goblinId)
                linkInfo.detailString(DetailKeys.CHOOSE_LINK_TYPE) shouldBe "Type"
                linkInfo.detailInt(DetailKeys.SOURCE_ABILITY_GRPID) shouldBe 176647
            }
        }

        test("retired static choice rejects response effects and session advance") {
            startPuzzleFile("puzzles/patchwork-banner-static-choice.pzl", validating = true)

            castSpellUntilSelectNReq("Patchwork Banner")
            val promptBridge = harness.bridge.promptBridge(SeatId(1))
            val before = messageSnapshot()
            promptBridge.beforeResponseRetirement = promptBridge::cancelPending

            respondToSelectN(listOf(StaticChoiceIds.subtypeIdFor("Goblin")!!))

            assertSoftly {
                promptBridge.journal.drainChoiceResults() shouldBe emptyList()
                messagesSince(before) shouldBe emptyList()
            }
        }

        test("Heraldic Banner static color choice emits ChoiceResult and LinkInfo") {
            startPuzzleFile("puzzles/heraldic-banner-static-choice.pzl", validating = true)

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
                    .first { AnnotationType.ChoiceResult in it.typeList }
            val linkInfo =
                checkNotNull(
                    allMessages.lastGsmMatching { gsm ->
                        gsm.persistentAnnotationsList.any { AnnotationType.LinkInfo in it.typeList }
                    },
                ) { "No persistent LinkInfo annotation emitted" }
                    .persistentAnnotationsList
                    .first { AnnotationType.LinkInfo in it.typeList }

            assertSoftly {
                choiceResult.detailInt(DetailKeys.CHOICE_VALUE) shouldBe redId
                choiceResult.detailInt(DetailKeys.CHOICE_DOMAIN) shouldBe 6
                linkInfo.affectedIdsList shouldBe listOf(redId)
                linkInfo.detailString(DetailKeys.CHOOSE_LINK_TYPE) shouldBe "Color"
                linkInfo.detailInt(DetailKeys.SOURCE_ABILITY_GRPID) shouldBe 88237
            }
        }
    })
