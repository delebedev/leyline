package leyline.mechanics.protection

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class ProtectionChoiceTest :
    SessionTest({
        val puzzle = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Gods Willing;Shock
            humanbattlefield=Plains;Mountain;Mountain;Grizzly Bears
            humanlibrary=Island;Island;Island
            aibattlefield=Centaur Courser
            ailibrary=Island;Island;Island
        """

        session("choosing red protection removes the creature from red spell targets", puzzle = puzzle, fullControl = true) {
            val protected = human.battlefield.iid("Grizzly Bears")
            val unprotected = ai.battlefield.iid("Centaur Courser")
            castSpellUntilSelectTargetsReq("Gods Willing")
            selectTargets(listOf(protected))
            passUntil(8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val req = lastSelectNReq()
            assertSoftly {
                req.listType shouldBe SelectionListType.StaticSubset
                req.staticList shouldBe StaticList.CardColors
                req.idsList shouldBe listOf(1, 2, 3, 4, 5)
            }
            respondToSelectN(listOf(4))
            passUntil(8) { allMessages.any { it.hasGroupReq() } }.shouldBeTrue()
            val scry = allMessages.last { it.hasGroupReq() }.groupReq
            respondToScry(emptyList(), scry.instanceIdsList)
            passUntilResolved()
            val targets =
                castSpellUntilSelectTargetsReq("Shock")
                    .targetsList
                    .flatMap { it.targetsList }
                    .map { it.targetInstanceId }
            assertSoftly {
                targets shouldNotContain protected
                targets shouldContain unprotected
                allMessages.annotationsOfType(AnnotationType.ChoiceResult).last().detailInt(DetailKeys.CHOICE_VALUE) shouldBe 4
            }
            selectTargets(listOf(unprotected))
            passUntilResolved()
            human.battlefield.card("Grizzly Bears").damage shouldBe 0
            ai.battlefield.card("Centaur Courser").damage shouldBe 2
        }

        session(
            "Angelic Intervention allows colorless and applies its protection and counter",
            puzzle = puzzle.replace("Gods Willing;Shock", "Angelic Intervention"),
            fullControl = true,
        ) {
            castSpellUntilSelectTargetsReq("Angelic Intervention")
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntil(8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val req = lastSelectNReq()
            assertSoftly {
                req.staticList shouldBe StaticList.CardColors
                req.idsList.toSet() shouldBe setOf(0, 1, 2, 3, 4, 5)
            }
            respondToSelectN(listOf(0))
            passUntilResolved()
            val creature = human.battlefield.card("Grizzly Bears")
            assertSoftly {
                creature.hasKeyword("Protection from colorless").shouldBeTrue()
                creature.netPower shouldBe 3
                creature.netToughness shouldBe 3
                allMessages.annotationsOfType(AnnotationType.ChoiceResult).last().detailInt(DetailKeys.CHOICE_VALUE) shouldBe 0
            }
        }
    })
