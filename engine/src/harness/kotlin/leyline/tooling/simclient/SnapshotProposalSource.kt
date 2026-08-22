package leyline.tooling.simclient

import leyline.copilot.CopilotProposal
import leyline.copilot.SnapshotConsult
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
    fun propose(prompt: GREToClientMessage): CopilotProposal {
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
        return SnapshotConsult.consult(gsm, prompt, seat, bridge.cardRepository).proposal
    }
}
