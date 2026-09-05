package leyline.tooling.simclient

import leyline.copilot.SnapshotConsult
import leyline.copilot.SnapshotDecisionConsult
import leyline.game.event.FrameEventLog
import leyline.game.projectSnapshotForTest
import leyline.game.snapshot.GsmSnapshot
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** Rebuild the current game from its projected state and consult that isolated copy. */
internal class SnapshotProposalSource(
    private val harness: MatchFlowHarness,
    private val seat: Int = 1,
) {
    fun decide(prompt: GREToClientMessage): SnapshotDecisionConsult {
        val gsm = currentGsm()
        return SnapshotConsult.decide(gsm, prompt, seat, harness.bridge.cardRepository)
    }

    private fun currentGsm(): wotc.mtgo.gre.external.messaging.Messages.GameStateMessage {
        val game = harness.bridge.getGame() ?: error("no active game")
        val bridge = harness.bridge
        val prior = bridge.projectionStateSnapshot()
        val (snapshot, capturedProjection) =
            bridge.editProjection(prior) {
                GsmSnapshot.capture(game, bridge, "simclient-consult", 0)
            }
        val gsm =
            bridge
                .projectSnapshotForTest(
                    snap = snapshot,
                    viewingSeatId = seat,
                    events = FrameEventLog.EMPTY,
                    projectionState = capturedProjection.copy(revision = prior.revision),
                ).gsm
        return gsm
    }
}
