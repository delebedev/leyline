package leyline.mechanics.protection

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
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
        for ((quality, payment) in listOf("Artifact" to ManaColor.Phyrexian_afc9, "red" to ManaColor.White_afc9)) {
            session(
                "mixed protection retains $quality with selected $payment payment",
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Apostle's Blessing;Ornithopter
                    humanbattlefield=Grizzly Bears;${if (payment == ManaColor.Phyrexian_afc9) "Forest" else "Plains;Plains"}
                    humanlibrary=Forest;Forest
                    ailibrary=Forest;Forest
                """,
                fullControl = true,
            ) {
                val request = after { castSpellByName("Apostle's Blessing").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
                respondToManaTypeChoices(request.castingTimeOptionReqList.filter { it.hasSelectManaTypeReq() }.map { it.ctoId to payment })
                selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
                passUntil(8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
                val choice = lastSelectNReq()
                choice.idType shouldBe IdType.PromptParameterIndex
                val labels = choice.prompt.parametersList.map { it.stringValue }
                labels shouldBe listOf("white", "blue", "black", "red", "green", "Artifact")
                respondToSelectN(listOf(labels.indexOf(quality) + 1))
                passUntilResolved()
                assertSoftly {
                    val creature = human.battlefield.card("Grizzly Bears")
                    creature.hasKeyword(if (quality == "Artifact") "Protection:Artifact" else "Protection from red").shouldBeTrue()
                    human.life shouldBe if (payment == ManaColor.Phyrexian_afc9) 18 else 20
                    human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe
                        if (payment == ManaColor.Phyrexian_afc9) 1 else 2
                    human.graveyard.card("Apostle's Blessing")
                }
                castSpellByName("Ornithopter").shouldBeTrue()
                passUntilResolved()
                human.battlefield.card("Ornithopter")
            }
        }

        session(
            "life payment does not erase the generic mana requirement",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Apostle's Blessing
                humanbattlefield=Grizzly Bears
                humanlibrary=Forest;Forest
                ailibrary=Forest;Forest
            """,
            fullControl = true,
        ) {
            val iid = human.hand.iid("Apostle's Blessing")
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .none {
                    it.instanceId == iid && it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Cast
                }.shouldBeTrue()
            human.life shouldBe 20
            human.hand.card("Apostle's Blessing")
        }
    })
