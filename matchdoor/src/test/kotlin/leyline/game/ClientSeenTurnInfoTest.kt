package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo

/**
 * Unit tests for client-seen turn info — tracks the last
 * TurnInfo sent to the client, used by [BundleBuilder.postAction] to
 * detect phase/step transitions for PhaseOrStepModified annotations.
 *
 * Separated from the diff baseline (used for diff computation) so that
 * drainPlayback's snapshot updates don't clobber the annotation decision.
 */
class ClientSeenTurnInfoTest :
    FunSpec({

        tags(UnitTag)

        fun turnInfo(phase: Phase, step: Step, turn: Int = 1): TurnInfo =
            TurnInfo.newBuilder()
                .setPhase(phase).setStep(step).setTurnNumber(turn)
                .setActivePlayer(1).setPriorityPlayer(1).setDecisionPlayer(1)
                .build()

        fun gsm(turnInfo: TurnInfo, gsId: Int = 1): GameStateMessage =
            GameStateMessage.newBuilder()
                .setGameStateId(gsId)
                .setTurnInfo(turnInfo)
                .build()

        test("initially null") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            snap.getClientSeenTurnInfo().shouldBeNull()
        }

        test("update from gsm extracts turn info") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            val ti = turnInfo(Phase.Main1_a549, Step.None_a2cb)
            snap.recordClientSeenTurnInfo(gsm(ti))
            snap.getClientSeenTurnInfo() shouldBe ti
        }

        test("update from multiple gsms keeps last") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            val ti1 = turnInfo(Phase.Main1_a549, Step.None_a2cb)
            val ti2 = turnInfo(Phase.Combat_a549, Step.DeclareAttack_a2cb)
            snap.recordClientSeenTurnInfo(gsm(ti1, gsId = 1))
            snap.recordClientSeenTurnInfo(gsm(ti2, gsId = 2))
            snap.getClientSeenTurnInfo() shouldBe ti2
        }

        test("gsm without turn info does not overwrite") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            val ti = turnInfo(Phase.Main1_a549, Step.None_a2cb)
            snap.recordClientSeenTurnInfo(gsm(ti))

            // GSM with no turnInfo set
            val bare = GameStateMessage.newBuilder().setGameStateId(2).build()
            snap.recordClientSeenTurnInfo(bare)

            snap.getClientSeenTurnInfo() shouldBe ti
        }

        test("phase changed detection") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            val tiMain1 = turnInfo(Phase.Main1_a549, Step.None_a2cb)
            val tiCombat = turnInfo(Phase.Combat_a549, Step.BeginCombat_a2cb)
            val tiMain1Again = turnInfo(Phase.Main1_a549, Step.None_a2cb, turn = 1)

            snap.recordClientSeenTurnInfo(gsm(tiMain1))

            // Different phase → changed
            snap.isPhaseChangedFromClientSeen(tiCombat) shouldBe true
            // Same phase → not changed
            snap.isPhaseChangedFromClientSeen(tiMain1Again) shouldBe false
        }

        test("phase changed when last sent is null") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            val ti = turnInfo(Phase.Main1_a549, Step.None_a2cb)
            // When we have no prior info, we should detect a change (first state sent)
            snap.isPhaseChangedFromClientSeen(ti) shouldBe true
        }

        test("step change within same phase detected") {
            val snap = DiffSnapshotter(InstanceIdRegistry())
            val tiAttackers = turnInfo(Phase.Combat_a549, Step.DeclareAttack_a2cb)
            val tiBlockers = turnInfo(Phase.Combat_a549, Step.DeclareBlock_a2cb)

            snap.recordClientSeenTurnInfo(gsm(tiAttackers))
            snap.isPhaseChangedFromClientSeen(tiBlockers) shouldBe true
        }

        test("independent from prev snapshot") {
            val snap = DiffSnapshotter(InstanceIdRegistry())

            // Set diff baseline to Main1 (for diff computation)
            val main1Gsm = gsm(turnInfo(Phase.Main1_a549, Step.None_a2cb))
            snap.snapshotDiffBaseline(main1Gsm)

            // Set client-seen turn info to Combat (what client last saw)
            val combatTi = turnInfo(Phase.Combat_a549, Step.BeginCombat_a2cb)
            snap.recordClientSeenTurnInfo(gsm(combatTi))

            // They should be independent
            snap.getDiffBaselineState()?.turnInfo?.phase shouldBe Phase.Main1_a549
            snap.getClientSeenTurnInfo()?.phase shouldBe Phase.Combat_a549
        }
    })
