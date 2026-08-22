package leyline.tooling.headless

import leyline.bridge.types.SeatId
import leyline.copilot.ConsultResponse
import leyline.copilot.CopilotProposalService
import leyline.copilot.ForgeAiPolicy
import leyline.copilot.SnapshotConsult
import leyline.copilot.SnapshotFidelityReport
import leyline.game.event.FrameEventLog
import leyline.game.projectSnapshotForTest
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** Implementation-owned capabilities that cannot be expressed as client state. */
internal object HeadlessMatchRuntime {
    /** Collect queued output and run configured automatic responses at an explicit boundary. */
    fun drain(match: HeadlessMatch) {
        (match as? MatchFlowHarness)?.drainSink()
            ?: error("Output draining requires the standard headless runtime")
    }

    fun diagnostics(
        match: HeadlessMatch,
        label: String,
        messageTail: Int,
    ): String =
        (match as? MatchFlowHarness)?.diagnostics(label, messageTail)
            ?: error("Diagnostics require the standard headless runtime")

    fun forgeAiPolicy(
        match: HeadlessMatch,
        seat: SeatId,
    ): ForgeAiPolicy {
        val harness = match as? MatchFlowHarness ?: error("Forge AI requires the standard headless runtime")
        return ForgeAiPolicy({ harness.bridge }, seat)
    }

    fun liveConsult(
        match: HeadlessMatch,
        prompt: GREToClientMessage,
        seat: SeatId,
    ): ConsultResponse {
        val harness = match as? MatchFlowHarness ?: error("Copilot consult requires the standard headless runtime")
        return ConsultResponse(
            proposal = CopilotProposalService(harness.bridge, seat).propose(prompt),
            fidelity = SnapshotFidelityReport(grade = "live", features = emptyList()),
        )
    }

    fun snapshotConsult(
        match: HeadlessMatch,
        prompt: GREToClientMessage,
        seat: SeatId,
    ): ConsultResponse {
        val harness = match as? MatchFlowHarness ?: error("Snapshot consult requires the standard headless runtime")
        val game = harness.bridge.getGame() ?: error("no live game")
        val bridge = harness.bridge
        val prior = bridge.projectionStateSnapshot()
        val (snap, capturedProjection) =
            bridge.editProjection(prior) {
                GsmSnapshot.capture(game, bridge, "shadow", 0)
            }
        val gsm =
            bridge
                .projectSnapshotForTest(
                    snap = snap,
                    viewingSeatId = seat.value,
                    events = FrameEventLog.EMPTY,
                    projectionState = capturedProjection.copy(revision = prior.revision),
                ).gsm
        return SnapshotConsult.consult(gsm, prompt, seat.value, bridge.cardRepository)
    }

    fun query(
        match: HeadlessMatch,
        query: MatchQuery,
    ): MatchQueryResult {
        val harness = match as? MatchFlowHarness ?: error("Headless queries require the standard headless runtime")
        return when (query) {
            is MatchQuery.CardName -> MatchQueryResult.CardName(harness.bridge.cardRepository.findNameByGrpId(query.grpId))
            is MatchQuery.CardGrpId -> MatchQueryResult.CardGrpId(harness.bridge.cardRepository.findGrpIdByName(query.cardName))
            is MatchQuery.KeywordAbilityGrpId ->
                MatchQueryResult.KeywordAbilityGrpId(
                    harness.bridge.cardRepository.findKeywordAbilityGrpId(query.cardGrpId, query.keywordAbilityId),
                )
            is MatchQuery.ActionMatchesAlternative -> {
                val cardGrpId =
                    query.grpId.takeIf { it != 0 }
                        ?: harness.bridge
                            .getForgeCardId(leyline.bridge.types.InstanceId(query.instanceId))
                            ?.let { harness.bridge.getGame()?.findById(it.value) }
                            ?.let { harness.bridge.resolveGrpId(it, query.instanceId) }
                val abilityGrpId =
                    cardGrpId?.let {
                        harness.bridge.cardRepository.findKeywordAbilityGrpId(it, query.keywordAbilityId)
                    }
                MatchQueryResult.ActionMatchesAlternative(
                    query.alternativeGrpId == query.keywordAbilityId ||
                        query.abilityGrpId == query.keywordAbilityId ||
                        (
                            abilityGrpId != null &&
                                (query.alternativeGrpId == abilityGrpId || query.abilityGrpId == abilityGrpId)
                        ),
                )
            }
        }
    }
}
