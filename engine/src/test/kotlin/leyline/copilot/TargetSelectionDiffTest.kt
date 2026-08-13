package leyline.copilot

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.Target
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection

/**
 * Pins the stateless target-declaration walk: committed picks are read off the
 * (re-)prompt as the Unselect-marked targets, diffed against the AI's desired
 * set, and the step is select-missing / unselect-extra until they match — then
 * Submit.
 */
@Suppress("MissingAssertSoftly")
class TargetSelectionDiffTest :
    FunSpec({

        tags(UnitTag)

        fun target(
            iid: Int,
            committed: Boolean,
        ): Target =
            Target
                .newBuilder()
                .setTargetInstanceId(iid)
                .setLegalAction(if (committed) SelectAction.Unselect else SelectAction.Select_a1ad)
                .build()

        fun group(
            targetIdx: Int,
            min: Int,
            max: Int,
            vararg targets: Target,
        ): TargetSelection =
            TargetSelection
                .newBuilder()
                .setTargetIdx(targetIdx)
                .setMinTargets(min)
                .setMaxTargets(max)
                .apply { targets.forEach { addTargets(it) } }
                .build()

        fun req(vararg groups: TargetSelection): SelectTargetsReq = SelectTargetsReq.newBuilder().addAllTargets(groups.toList()).build()

        val allTargets =
            req(
                group(
                    targetIdx = 1,
                    min = 1,
                    max = 3,
                    target(282, committed = false),
                    target(283, committed = false),
                    target(284, committed = false),
                ),
            )

        test("committed targets retain their request group") {
            val r =
                req(
                    group(1, 1, 1, target(282, committed = true), target(283, committed = false)),
                    group(2, 1, 1, target(384, committed = true), target(385, committed = false)),
                )
            TargetSelectionDiff.committedTargets(r) shouldBe mapOf(1 to listOf(282), 2 to listOf(384))
        }

        test("step selects one desired target and carries its group targetIdx") {
            val step = TargetSelectionDiff.step(allTargets, committed = mapOf(1 to emptyList()), desired = mapOf(1 to listOf(282, 283)))
            val sel = step.shouldBeInstanceOf<SimDecision.SelectTargets>()
            sel.targetInstanceIds shouldBe listOf(282)
            sel.targetIdx shouldBe 1
        }

        test("step submits once committed equals desired") {
            TargetSelectionDiff.step(allTargets, committed = mapOf(1 to listOf(282)), desired = mapOf(1 to listOf(282))) shouldBe
                SimDecision.SubmitTargets
        }

        test("step unselects a committed target no longer desired") {
            val step = TargetSelectionDiff.step(allTargets, committed = mapOf(1 to listOf(282, 283)), desired = mapOf(1 to listOf(282)))
            step.shouldBeInstanceOf<SimDecision.UnselectTargets>().targetInstanceIds shouldBe listOf(283)
        }

        test("step submits immediately when nothing is desired and nothing committed (declined optional)") {
            val optional = req(group(1, 0, 1, target(282, committed = false)))
            TargetSelectionDiff.step(optional, committed = mapOf(1 to emptyList()), desired = mapOf(1 to emptyList())) shouldBe
                SimDecision.SubmitTargets
        }

        test("two required groups advance one group per echoed prompt") {
            val r =
                req(
                    group(1, 1, 1, target(101, committed = false), target(102, committed = false)),
                    group(2, 1, 1, target(201, committed = false), target(202, committed = false)),
                )
            val desired = mapOf(1 to listOf(101), 2 to listOf(201))

            TargetSelectionDiff
                .step(r, committed = mapOf(1 to emptyList(), 2 to emptyList()), desired = desired)
                .shouldBeInstanceOf<SimDecision.SelectTargets>() shouldBe SimDecision.SelectTargets(listOf(101), targetIdx = 1)
            TargetSelectionDiff
                .step(r, committed = mapOf(1 to listOf(101), 2 to emptyList()), desired = desired)
                .shouldBeInstanceOf<SimDecision.SelectTargets>() shouldBe SimDecision.SelectTargets(listOf(201), targetIdx = 2)
            TargetSelectionDiff.step(r, committed = desired, desired = desired) shouldBe SimDecision.SubmitTargets
        }

        test("an over-full group is repaired instead of accepted by aggregate count") {
            val r =
                req(
                    group(1, 1, 1, target(101, committed = false), target(102, committed = false)),
                    group(2, 1, 1, target(201, committed = true), target(202, committed = true)),
                )
            val committed = TargetSelectionDiff.committedTargets(r)
            val desired = mapOf(1 to listOf(101), 2 to listOf(201))

            TargetSelectionDiff.isValid(r, committed) shouldBe false
            TargetSelectionDiff.step(r, committed = committed, desired = committed) shouldBe null
            TargetSelectionDiff.step(r, committed = committed, desired = desired) shouldBe
                SimDecision.UnselectTargets(listOf(202), targetIdx = 2)
        }
    })
