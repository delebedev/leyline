package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class TieredModalCostTest :
    SessionTest({

        test("Ice Magic emits a one-of-three Tiered modal-cost prompt") {
            startPuzzleFile("puzzles/tiered-ice-magic.pzl", validating = true)

            val cto = castSpellUntilCastingTimeOptionsReq("Ice Magic")
            val option = cto.getCastingTimeOptionReq(0)
            val modalReq = option.modalReq

            assertSoftly {
                option.ctoId shouldBe 2
                option.grpId shouldBe 95912
                modalReq.abilityGrpId shouldBe 189137
                modalReq.minSel shouldBe 1
                modalReq.maxSel shouldBe 1
                modalReq.modalOptionsList.map { it.grpId } shouldContainExactly listOf(189134, 189135, 189136)
                modalReq.excludedOptionsCount shouldBe 0

                modalReq.getModalOptions(0).modeCostList.map { it.manaCost.count } shouldContainExactly listOf(0)
                modalReq
                    .getModalOptions(0)
                    .getModeCost(0)
                    .manaCost
                    .getColor(0) shouldBe ManaColor.Generic
                modalReq.getModalOptions(1).modeCostList.map { it.manaCost.count } shouldContainExactly listOf(2)
                modalReq.getModalOptions(2).modeCostList.map { it.manaCost.count } shouldContainExactly listOf(5, 1)
                modalReq.modalOptionsList.flatMap { it.modeCostList }.map { it.id } shouldContainExactly listOf(1, 2, 3, 4)
                modalReq
                    .getModalOptions(2)
                    .getModeCost(1)
                    .manaCost
                    .getColor(0) shouldBe ManaColor.Blue_afc9
            }
        }

        test("Ice Magic selected first tier resolves through target selection") {
            startPuzzleFile("puzzles/tiered-ice-magic.pzl", validating = true)

            val cto = castSpellUntilCastingTimeOptionsReq("Ice Magic")
            val firstTier = cto.getCastingTimeOptionReq(0).modalReq.getModalOptions(0)
            harness.respondModalChoice(listOf(firstTier.grpId))

            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntilResolved()

            assertSoftly {
                "Grizzly Bears" should beInHandOf(human)
                "Ice Magic" should beInGraveyardOf(human)
            }
        }

        test("Thunder Magic selected middle tier pays the tier cost and resolves") {
            startPuzzleFile("puzzles/tiered-thunder-magic.pzl", validating = true)

            val cto = castSpellUntilCastingTimeOptionsReq("Thunder Magic")
            val option = cto.getCastingTimeOptionReq(0)
            val modalReq = option.modalReq
            val middleTier = modalReq.getModalOptions(1)

            assertSoftly {
                option.ctoId shouldBe 2
                option.playerIdToPrompt shouldBe 1
                option.grpId shouldBe 96031
                modalReq.abilityGrpId shouldBe 189322
                modalReq.minSel shouldBe 1
                modalReq.maxSel shouldBe 1
                modalReq.modalOptionsList.map { it.grpId } shouldContainExactly listOf(189319, 189320, 189321)
                middleTier.grpId shouldBe 189320
                middleTier.getModeCost(0).manaCost.count shouldBe 3
            }

            harness.respondModalChoice(listOf(middleTier.grpId))
            selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))
            passUntilResolved()

            assertSoftly {
                "Grizzly Bears" should beInGraveyardOf(ai)
                "Thunder Magic" should beInGraveyardOf(human)
            }
        }
    })
