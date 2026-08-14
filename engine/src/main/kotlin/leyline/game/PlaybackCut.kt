package leyline.game

import leyline.bridge.handoff.BlockingInteraction
import leyline.game.bundle.BundleBuilder
import leyline.game.event.FrameEventLog
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal enum class PlaybackCutReason {
    LandPlayed,
    StackObjectCast,
    StackObjectResolved,
    ResolutionZoneCompleted,
    CountersChanged,
    PoisonChanged,
    TurnBegan,
    PhaseChanged,
    AttackersDeclared,
    BlockersDeclared,
    CombatEnded,
}

internal enum class PlaybackCutBoundary {
    MainLoopStep,
    AttackersDeclared,
    BlockersDeclared,
    CombatEnded,
}

internal data class PlaybackCutRequest(
    val reason: PlaybackCutReason,
    val delayMs: Int,
    val turnStarted: Boolean,
    val boundary: PlaybackCutBoundary = PlaybackCutBoundary.MainLoopStep,
) {
    fun aggregate(next: PlaybackCutRequest): PlaybackCutRequest =
        copy(
            delayMs = maxOf(delayMs, next.delayMs),
            turnStarted = turnStarted || next.turnStarted,
            boundary = if (next.boundary == PlaybackCutBoundary.MainLoopStep) boundary else next.boundary,
        )
}

internal data class PendingCut(
    val request: PlaybackCutRequest,
    val projection: BundleBuilder.PlaybackCut,
)

internal data class MaterializationDiagnostic(
    val request: PlaybackCutRequest,
    val events: FrameEventLog?,
)

/** Exact pre-publication interaction cut retained on terminal failure. */
internal data class PendingInteractionCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: BlockingInteraction,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition?,
)

/** Exact pre-publication Search cut retained on terminal failure. */
internal data class PendingSearchCut(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: leyline.bridge.handoff.SearchWindowValue,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition,
)

/** Frozen Search input retained when materialization itself fails. */
internal data class SearchMaterializationDiagnostic(
    val interactionId: String,
    val interaction: leyline.bridge.handoff.SearchWindowValue,
)

internal class PlaybackTerminalFailure(
    val pendingCut: PendingCut?,
    val diagnostic: MaterializationDiagnostic?,
    val pendingInteractionCut: PendingInteractionCut? = null,
    val pendingSearchCut: PendingSearchCut? = null,
    val searchDiagnostic: SearchMaterializationDiagnostic? = null,
    cause: Throwable,
) : IllegalStateException("Playback projection terminated", cause)
