package leyline.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import wotc.mtgo.gre.external.messaging.Messages.TurnInfo

/**
 * Unit tests for [DiffSnapshotter.lastSentTurnInfo] — tracks the last
 * TurnInfo sent to the client, used by [BundleBuilder.postAction] to
 * detect phase/step transitions for PhaseOrStepModified annotations.
 *
 * Separated from prevSnapshot (used for diff computation) so that
 * drainPlayback's snapshot updates don't clobber the annotation decision.
 */
@Test(groups = ["unit"])
class LastSentTurnInfoTest {

    private fun turnInfo(phase: Phase, step: Step, turn: Int = 1): TurnInfo =
        TurnInfo.newBuilder()
            .setPhase(phase).setStep(step).setTurnNumber(turn)
            .setActivePlayer(1).setPriorityPlayer(1).setDecisionPlayer(1)
            .build()

    private fun gsm(turnInfo: TurnInfo, gsId: Int = 1): GameStateMessage =
        GameStateMessage.newBuilder()
            .setGameStateId(gsId)
            .setTurnInfo(turnInfo)
            .build()

    @Test
    fun initiallyNull() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        assertNull(snap.getLastSentTurnInfo(), "lastSentTurnInfo should be null initially")
    }

    @Test
    fun updateFromGsmExtractsTurnInfo() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        val ti = turnInfo(Phase.Main1_a549, Step.None_a2cb)
        snap.updateLastSentTurnInfo(gsm(ti))
        assertEquals(snap.getLastSentTurnInfo(), ti)
    }

    @Test
    fun updateFromMultipleGsmsKeepsLast() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        val ti1 = turnInfo(Phase.Main1_a549, Step.None_a2cb)
        val ti2 = turnInfo(Phase.Combat_a549, Step.DeclareAttack_a2cb)
        snap.updateLastSentTurnInfo(gsm(ti1, gsId = 1))
        snap.updateLastSentTurnInfo(gsm(ti2, gsId = 2))
        assertEquals(snap.getLastSentTurnInfo(), ti2)
    }

    @Test
    fun gsmWithoutTurnInfoDoesNotOverwrite() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        val ti = turnInfo(Phase.Main1_a549, Step.None_a2cb)
        snap.updateLastSentTurnInfo(gsm(ti))

        // GSM with no turnInfo set
        val bare = GameStateMessage.newBuilder().setGameStateId(2).build()
        snap.updateLastSentTurnInfo(bare)

        assertEquals(snap.getLastSentTurnInfo(), ti, "Should not overwrite with missing turnInfo")
    }

    @Test
    fun phaseChangedDetection() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        val tiMain1 = turnInfo(Phase.Main1_a549, Step.None_a2cb)
        val tiCombat = turnInfo(Phase.Combat_a549, Step.BeginCombat_a2cb)
        val tiMain1Again = turnInfo(Phase.Main1_a549, Step.None_a2cb, turn = 1)

        snap.updateLastSentTurnInfo(gsm(tiMain1))

        // Different phase → changed
        assertTrue(snap.isPhaseChangedFromLastSent(tiCombat), "Combat vs Main1 should be a phase change")
        // Same phase → not changed
        assertFalse(snap.isPhaseChangedFromLastSent(tiMain1Again), "Same phase should not be a change")
    }

    @Test
    fun phaseChangedWhenLastSentIsNull() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        val ti = turnInfo(Phase.Main1_a549, Step.None_a2cb)
        // When we have no prior info, we should detect a change (first state sent)
        assertTrue(snap.isPhaseChangedFromLastSent(ti), "Should detect change when no prior turnInfo exists")
    }

    @Test
    fun stepChangeWithinSamePhaseDetected() {
        val snap = DiffSnapshotter(InstanceIdRegistry())
        val tiAttackers = turnInfo(Phase.Combat_a549, Step.DeclareAttack_a2cb)
        val tiBlockers = turnInfo(Phase.Combat_a549, Step.DeclareBlock_a2cb)

        snap.updateLastSentTurnInfo(gsm(tiAttackers))
        assertTrue(snap.isPhaseChangedFromLastSent(tiBlockers), "Step change within same phase should be detected")
    }

    @Test
    fun independentFromPrevSnapshot() {
        val snap = DiffSnapshotter(InstanceIdRegistry())

        // Set prevSnapshot to Main1 (for diff computation)
        val main1Gsm = gsm(turnInfo(Phase.Main1_a549, Step.None_a2cb))
        snap.snapshotState(main1Gsm)

        // Set lastSentTurnInfo to Combat (what client last saw)
        val combatTi = turnInfo(Phase.Combat_a549, Step.BeginCombat_a2cb)
        snap.updateLastSentTurnInfo(gsm(combatTi))

        // They should be independent
        assertEquals(snap.getPreviousState()?.turnInfo?.phase, Phase.Main1_a549, "prevSnapshot should be Main1")
        assertEquals(snap.getLastSentTurnInfo()?.phase, Phase.Combat_a549, "lastSentTurnInfo should be Combat")
    }
}
