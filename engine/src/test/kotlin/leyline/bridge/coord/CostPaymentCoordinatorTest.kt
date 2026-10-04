package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.effectCostResp
import leyline.testkit.graveyard
import leyline.testkit.hasDetail
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class CostPaymentCoordinatorTest :
    SessionTest({
        session(
            "automatic payment handles a mana source that sacrifices another creature",
            fullControl = true,
            puzzleFile = "data/puzzles/phyrexian-tower-sacrifice-mana.pzl",
        ) {
            val bridgedController = human.controller
            castSpellByName("Bad Moon").shouldBeTrue()

            val resolved =
                passUntil(maxPasses = 8) {
                    runCatching { human.battlefield.card("Bad Moon") }.isSuccess
                }
            assertSoftly {
                human.controller shouldBe bridgedController
                resolved.shouldBeTrue()
                human.graveyard.card("Grizzly Bears").name shouldBe "Grizzly Bears"
                human.battlefield
                    .card("Phyrexian Tower")
                    .isTapped
                    .shouldBeTrue()
            }
        }

        fun delvePuzzle(islands: Int = 5) =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=${List(islands) { "Island" }.joinToString(";")}
            humanhand=Treasure Cruise
            humangraveyard=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
            humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            ailibrary=Plains;Plains;Plains
            """.trimIndent()

        session("delve selects seven graveyard cards and pays only blue", puzzle = delvePuzzle()) {
            castSpellByName("Treasure Cruise").shouldBeTrue()
            val request = allMessages.last { it.hasPayCostsReq() }
            val selection = request.payCostsReq.effectCostReq.costSelection
            assertSoftly {
                selection.minSel shouldBe 0
                selection.maxSel shouldBe 7
                selection.idsList shouldHaveSize 9
                request.allowCancel shouldBe AllowCancel.Abort
            }
            val activePayment = checkNotNull(bridge.cutCoordinator.oneShotPayCosts.current())
            assertSoftly {
                bridge.cutCoordinator.acceptSettled(
                    effectCostResp(listOf(human.battlefield.iid("Island"))),
                    activePayment.gameStateId,
                ) shouldBe
                    false
                bridge.cutCoordinator.acceptSettled(effectCostResp(selection.idsList.take(8)), activePayment.gameStateId) shouldBe false
                bridge.cutCoordinator.acceptSettled(
                    effectCostResp(List(2) { selection.idsList.first() }),
                    activePayment.gameStateId,
                ) shouldBe
                    false
                bridge.cutCoordinator.oneShotPayCosts.current() shouldBe activePayment
            }
            val selected = selection.idsList.take(7)
            val paymentSnapshot = messageSnapshot()
            respondToEffectCost(selected)
            val paymentAnnotations =
                gameStateMessagesSince(paymentSnapshot)
                    .single { gsm ->
                        gsm.annotationsList.any {
                            it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                                it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.DELVE
                        }
                    }.annotationsList
            val delvePayments =
                paymentAnnotations.filter {
                    AnnotationType.ManaPaid in it.typeList &&
                        it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                        it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.DELVE
                }
            delvePayments.map { it.affectorId } shouldBe selected
            delvePayments.forEach {
                it.hasDetail(DetailKeys.ID) shouldBe false
                it.detailInt(DetailKeys.COLOR) shouldBe ManaColor.Generic.number
            }
            val spellIid = delvePayments.first().affectedIdsList.single()
            delvePayments.forEach { it.affectedIdsList shouldBe listOf(spellIid) }
            paymentAnnotations.indexOf(delvePayments.first()) shouldBe
                paymentAnnotations.indexOfLast { AnnotationType.ZoneTransfer_af5a in it.typeList } + 1
            passUntilResolved(maxPasses = 8)
            assertSoftly {
                human.getZone(ZoneType.Exile).size() shouldBe 7
                human.getZone(ZoneType.Graveyard).size() shouldBe 3
                human.getZone(ZoneType.Hand).size() shouldBe 3
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 1
                game().stackZone.isEmpty.shouldBeTrue()
            }
            playLand("Mountain").shouldBeTrue()
        }

        session("partial delve pays the remaining generic mana", puzzle = delvePuzzle()) {
            castSpellByName("Treasure Cruise").shouldBeTrue()
            val selection =
                allMessages
                    .last { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection
            respondToEffectCost(selection.idsList.take(3))
            passUntilResolved(maxPasses = 8)
            assertSoftly {
                human.getZone(ZoneType.Exile).size() shouldBe 3
                human.getZone(ZoneType.Hand).size() shouldBe 3
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 5
            }
        }

        session("zero delve can pay the full mana cost", puzzle = delvePuzzle(8)) {
            castSpellByName("Treasure Cruise").shouldBeTrue()
            respondToEffectCost(emptyList())
            passUntilResolved(maxPasses = 8)
            assertSoftly {
                human.getZone(ZoneType.Exile).size() shouldBe 0
                human.getZone(ZoneType.Hand).size() shouldBe 3
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 8
            }
        }

        session("cancelling delve abandons even a fully affordable cast", puzzle = delvePuzzle(8)) {
            castSpellByName("Treasure Cruise").shouldBeTrue()
            val paymentCount = allMessages.count { it.hasPayCostsReq() }
            cancelAction()
            assertSoftly {
                human.hand.card("Treasure Cruise").name shouldBe "Treasure Cruise"
                human.getZone(ZoneType.Exile).size() shouldBe 0
                human.getZone(ZoneType.Graveyard).size() shouldBe 9
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
                game().stackZone.isEmpty.shouldBeTrue()
                allMessages.count { it.hasPayCostsReq() } shouldBe paymentCount
            }
        }
        session("insufficient mana rolls back zero delve without exiling", puzzle = delvePuzzle()) {
            castSpellByName("Treasure Cruise").shouldBeTrue()
            respondToEffectCost(emptyList())
            assertSoftly {
                human.hand.card("Treasure Cruise").name shouldBe "Treasure Cruise"
                human.getZone(ZoneType.Exile).size() shouldBe 0
                human.getZone(ZoneType.Graveyard).size() shouldBe 9
                game().stackZone.isEmpty.shouldBeTrue()
            }
        }

        session(
            "Dig Through Time delves six and retains both blue costs",
            puzzle = delvePuzzle(2).replace("Treasure Cruise", "Dig Through Time"),
        ) {
            castSpellByName("Dig Through Time").shouldBeTrue()
            val selection =
                allMessages
                    .last { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection
            selection.maxSel shouldBe 6
            respondToEffectCost(selection.idsList.take(6))
            assertSoftly {
                human.getZone(ZoneType.Exile).size() shouldBe 6
                human.getZone(ZoneType.Battlefield).cards.count { it.isTapped } shouldBe 2
                game().stackZone.single().name shouldBe "Dig Through Time"
            }
        }
    })
