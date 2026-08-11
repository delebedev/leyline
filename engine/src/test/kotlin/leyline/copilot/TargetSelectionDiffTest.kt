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

        fun req(vararg targets: Target): SelectTargetsReq =
            SelectTargetsReq
                .newBuilder()
                .addTargets(TargetSelection.newBuilder().setTargetIdx(1).apply { targets.forEach { addTargets(it) } })
                .build()

        val allTargets = req(target(282, committed = false), target(283, committed = false), target(284, committed = false))

        test("committed targets are the Unselect-marked entries") {
            val r = req(target(282, committed = true), target(283, committed = false))
            TargetSelectionDiff.committedTargets(r) shouldBe setOf(282)
        }

        test("step selects the desired targets not yet committed, carrying the group targetIdx") {
            val step = TargetSelectionDiff.step(allTargets, committed = emptySet(), desired = listOf(282))
            val sel = step.shouldBeInstanceOf<SimDecision.SelectTargets>()
            sel.targetInstanceIds shouldBe listOf(282)
            sel.targetIdx shouldBe 1
        }

        test("step submits once committed equals desired") {
            TargetSelectionDiff.step(allTargets, committed = setOf(282), desired = listOf(282)) shouldBe SimDecision.SubmitTargets
        }

        test("step unselects a committed target no longer desired") {
            val step = TargetSelectionDiff.step(allTargets, committed = setOf(282, 283), desired = listOf(282))
            step.shouldBeInstanceOf<SimDecision.UnselectTargets>().targetInstanceIds shouldBe listOf(283)
        }

        test("step submits immediately when nothing is desired and nothing committed (declined optional)") {
            TargetSelectionDiff.step(allTargets, committed = emptySet(), desired = emptyList()) shouldBe SimDecision.SubmitTargets
        }

        test("step selects all missing at once for a multi-target spell") {
            val step = TargetSelectionDiff.step(allTargets, committed = setOf(282), desired = listOf(282, 283, 284))
            step.shouldBeInstanceOf<SimDecision.SelectTargets>().targetInstanceIds shouldBe listOf(283, 284)
        }
    })
