package leyline.game.mapping

import leyline.game.event.FrameEventLog
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Immutable, cut-scoped input for state projection.
 *
 * The shell captures the snapshot and cut-specific facts once, normalizes
 * ordered events, then reuses this value for every retry of the same projection
 * attempt.
 */
data class StateFrameInput(
    val gameStateId: Int,
    val snapshot: GsmSnapshot,
    val previousSnapshot: GsmSnapshot?,
    val events: FrameEventLog,
    val promptFacts: PromptProjectionFacts,
    val updateType: GameStateUpdate,
    val viewingSeatId: Int,
    val revealForSeat: Int?,
    /** One immutable synthetic-effect observation for this frame cut. */
    val effectFacts: EffectProjectionFacts,
    /** Event-relevant source attribution observed at this frame cut. */
    val mechanicSourceFacts: MechanicSourceFacts,
    /** Final ordered display rows for exhausted abilities at this frame cut. */
    val abilityExhaustionFacts: AbilityExhaustionFacts,
)
