package leyline.game.bundle

import forge.game.Game
import leyline.game.event.FrameEventLog
import leyline.game.event.SnapDeltaSynthesizer
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionEnvironment
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionState
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Materializes the immutable observation passed to one projection cut.
 *
 * The module owns safe-point reads and fact assembly. Callers retain ownership
 * of cut intent, compilation, commit, and delivery. [Events] makes journal
 * ownership explicit so a playback cut can supply a closed log without
 * closing the bridge journal a second time.
 */
internal class StateFrameInputCapture(
    private val bridge: GameBridge,
    private val matchId: String,
    private val viewingSeatId: Int,
    private val environmentOverride: StateProjectionEnvironment? = null,
) {
    /** One immutable observation and the projection baseline used with it. */
    internal data class Materialized(
        val state: StateFrameInput,
        val priorProjection: ProjectionState,
        val closesPlaybackFrame: Boolean,
    )

    /** Source of ordered events for a safe-point observation. */
    internal sealed interface Events {
        /** Close the bridge journal exactly once at this ordinary cut. */
        data object CloseBundleFrame : Events

        /** Use an already closed journal, as playback does. */
        data class Supplied(
            val log: FrameEventLog,
        ) : Events
    }

    /**
     * Materialize one frame at [gameStateId]. The returned values are immutable
     * and can be retried without reading the live Forge graph again.
     *
     * [includePreviousSnapshot] is false for full-state and puzzle-initial
     * cuts, whose first frame deliberately has no prior baseline.
     */
    fun capture(
        game: Game,
        gameStateId: Int,
        revealForSeat: Int?,
        events: Events,
        priorProjectionOverride: ProjectionState? = null,
        previousSnapshotOverride: GsmSnapshot? = null,
        includePreviousSnapshot: Boolean = true,
        promptFactsOverride: PromptProjectionFacts? = null,
        effectFactsOverride: EffectProjectionFacts? = null,
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): Materialized {
        val priorProjection = priorProjectionOverride ?: bridge.projectionStateSnapshot()
        val (snapshot, projectionBaseline) =
            bridge.editProjection(priorProjection) {
                GsmSnapshot.capture(game, bridge, matchId, gameStateId)
            }
        val closedEvents =
            when (events) {
                Events.CloseBundleFrame -> bridge.closeBundleFrame(viewingSeatId)
                is Events.Supplied -> events.log
            }
        val previousSnapshot =
            if (!includePreviousSnapshot) {
                null
            } else {
                previousSnapshotOverride ?: priorProjection.viewerCursors[0]?.previousSnapshot
            }
        val normalizedEvents =
            FrameEventLog(
                events = closedEvents.events + previousSnapshot?.let { SnapDeltaSynthesizer.synthesize(it, snapshot) }.orEmpty(),
                zoneMoves = closedEvents.zoneMoves,
            )
        bridge.invalidateAbilityRegistries(normalizedEvents.events)
        val effectFacts = effectFactsOverride ?: bridge.materializeEffectProjectionFacts()
        val mechanicSourceFacts = MechanicSourceFactsCapture.capture(bridge, normalizedEvents.events)
        val abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snapshot, bridge)
        val promptFacts = promptFactsOverride ?: bridge.materializePromptProjectionFacts()
        val persistentFeedFacts =
            PersistentFeedFactsCapture.capture(
                snapshot,
                promptFacts,
                bridge,
                environmentOverride ?: bridge.stateProjectionEnvironment,
            )
        return Materialized(
            state =
                StateFrameInput(
                    gameStateId = gameStateId,
                    snapshot = snapshot,
                    previousSnapshot = previousSnapshot,
                    events = normalizedEvents,
                    promptFacts = promptFacts,
                    updateType = updateType(snapshot, normalizedEvents),
                    viewingSeatId = viewingSeatId,
                    revealForSeat = revealForSeat,
                    effectFacts = effectFacts,
                    mechanicSourceFacts = mechanicSourceFacts,
                    abilityExhaustionFacts = abilityExhaustionFacts,
                    persistentFeedFacts = persistentFeedFacts,
                ),
            priorProjection = projectionBaseline.copy(revision = priorProjection.revision),
            closesPlaybackFrame = events is Events.CloseBundleFrame,
        )
    }
}
