package leyline.testkit

import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.StateProjectionEnvironment
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/** Shell adapter for tests that materialize projection inputs from a live bridge. */
object StateMapperShell {
    @Suppress("LongParameterList", "UnusedParameter")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = bridge.stateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        events: FrameEventLog = FrameEventLog.EMPTY,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        persistentFeedFacts: PersistentFeedFacts = PersistentFeedFacts(),
        effectFacts: EffectProjectionFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        projectionState: ProjectionState = bridge.projectionStateSnapshot(),
    ): StateProjectionCompiler.Result =
        buildFromSnapshot(
            snap = snap,
            gameStateId = gameStateId,
            matchId = matchId,
            bridge = bridge,
            environment = environment,
            actions = actions,
            updateType = updateType,
            viewingSeatId = viewingSeatId,
            revealForSeat = revealForSeat,
            prev = prev,
            events = events,
            promptFacts = promptFacts,
            persistentFeedFacts = persistentFeedFacts,
            effectFacts = effectFacts,
            mechanicSourceFacts = emptyMechanicSourceFactsFor(events),
            abilityExhaustionFacts = abilityExhaustionFacts,
            projectionState = projectionState,
        )

    @Suppress("LongParameterList", "UnusedParameter")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = bridge.stateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        prev: GsmSnapshot? = null,
        events: FrameEventLog = FrameEventLog.EMPTY,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        persistentFeedFacts: PersistentFeedFacts = PersistentFeedFacts(),
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        projectionState: ProjectionState = bridge.projectionStateSnapshot(),
    ): StateProjectionCompiler.Result =
        compile(
            environment = environment,
            input =
                StateFrameInput(
                    gameStateId = gameStateId,
                    snapshot = snap,
                    previousSnapshot = prev,
                    events = events,
                    promptFacts = promptFacts,
                    updateType = updateType,
                    viewingSeatId = viewingSeatId,
                    revealForSeat = revealForSeat,
                    effectFacts = effectFacts,
                    mechanicSourceFacts = mechanicSourceFacts,
                    abilityExhaustionFacts = abilityExhaustionFacts,
                    persistentFeedFacts = persistentFeedFacts,
                ),
            prior = projectionState,
            actions = actions,
        )

    @Suppress("UnusedParameter")
    fun buildDiff(
        input: StateFrameInput,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = bridge.stateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
    ): StateProjectionCompiler.Result = compile(environment, input, bridge.projectionStateSnapshot(), actions)

    @Suppress("LongParameterList", "UnusedParameter")
    fun buildDiff(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = bridge.stateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        effectFacts: EffectProjectionFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        persistentFeedFacts: PersistentFeedFacts = PersistentFeedFacts(),
    ): StateProjectionCompiler.Result =
        buildDiff(
            prev = prev,
            cur = cur,
            events = events,
            gameStateId = gameStateId,
            matchId = matchId,
            bridge = bridge,
            environment = environment,
            actions = actions,
            updateType = updateType,
            viewingSeatId = viewingSeatId,
            revealForSeat = revealForSeat,
            promptFacts = promptFacts,
            effectFacts = effectFacts,
            mechanicSourceFacts = emptyMechanicSourceFactsFor(events),
            abilityExhaustionFacts = abilityExhaustionFacts,
            persistentFeedFacts = persistentFeedFacts,
        )

    @Suppress("LongParameterList", "UnusedParameter")
    fun buildDiff(
        prev: GsmSnapshot?,
        cur: GsmSnapshot,
        events: FrameEventLog,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        environment: StateProjectionEnvironment = bridge.stateProjectionEnvironment,
        actions: ActionsAvailableReq? = null,
        updateType: GameStateUpdate = GameStateUpdate.SendAndRecord,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        effectFacts: EffectProjectionFacts,
        mechanicSourceFacts: MechanicSourceFacts,
        abilityExhaustionFacts: AbilityExhaustionFacts,
        persistentFeedFacts: PersistentFeedFacts = PersistentFeedFacts(),
        projectionState: ProjectionState = bridge.projectionStateSnapshot(),
    ): StateProjectionCompiler.Result =
        compile(
            environment,
            StateFrameInput(
                gameStateId = gameStateId,
                snapshot = cur,
                previousSnapshot = prev,
                events = events,
                promptFacts = promptFacts,
                updateType = updateType,
                viewingSeatId = viewingSeatId,
                revealForSeat = revealForSeat,
                effectFacts = effectFacts,
                mechanicSourceFacts = mechanicSourceFacts,
                abilityExhaustionFacts = abilityExhaustionFacts,
                persistentFeedFacts = persistentFeedFacts,
            ),
            projectionState,
            actions,
        )

    private fun compile(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        actions: ActionsAvailableReq?,
    ): StateProjectionCompiler.Result =
        if (actions == null) {
            StateProjectionCompiler.compileOneViewer(environment, input, prior)
        } else {
            StateProjectionCompiler.compileOneViewerWithActions(environment, input, prior, actions = actions)
        }

    private fun emptyMechanicSourceFactsFor(events: FrameEventLog): MechanicSourceFacts {
        require(events.events.isEmpty()) {
            "Event-bearing projection requires explicit MechanicSourceFacts"
        }
        return MechanicSourceFacts()
    }
}
