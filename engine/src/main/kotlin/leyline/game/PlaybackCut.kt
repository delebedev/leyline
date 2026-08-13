package leyline.game

import leyline.game.bundle.BundleBuilder
import leyline.game.event.FrameEventLog

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

internal class PlaybackTerminalFailure(
    val pendingCut: PendingCut?,
    val diagnostic: MaterializationDiagnostic?,
    cause: Throwable,
) : IllegalStateException("Playback projection terminated", cause)
