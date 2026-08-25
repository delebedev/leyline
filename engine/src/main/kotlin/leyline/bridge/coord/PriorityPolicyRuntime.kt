package leyline.bridge.coord

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.handoff.SynchronizationContinuation
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.PhaseStopProfile
import leyline.bridge.types.PriorityDecision
import leyline.game.mapping.StopTypeMapping
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AutoPassPriority
import wotc.mtgo.gre.external.messaging.Messages.SettingScope
import wotc.mtgo.gre.external.messaging.Messages.SettingStatus
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage
import wotc.mtgo.gre.external.messaging.Messages.Stop

/** Engine facts required for one atomic priority-policy classification. */
internal data class PriorityWindowObservation(
    val isOwnTurn: Boolean,
    val phase: PhaseType?,
    val smartPhaseSkip: Boolean,
    val promptJustResolved: Boolean,
    val stackEmpty: Boolean,
    val forceVisible: Boolean,
    val continuation: SynchronizationContinuation,
    val hasMeaningfulAction: Boolean,
)

/** Runtime result consumed by the engine priority loop. */
internal sealed interface PriorityWindowDecision {
    data class Present(
        val mode: PriorityWindowMode,
        val autoResolve: Boolean,
    ) : PriorityWindowDecision

    data class Skip(
        val reason: AutoPassReason,
    ) : PriorityWindowDecision
}

/**
 * The sole owner of client priority policy and its mutable settings state.
 *
 * Protocol heads submit immutable [SettingsMessage] values. The engine reads
 * decisions from this runtime; it never reconstructs policy from client
 * messages or maintains a second settings state.
 */
class PriorityPolicyRuntime {
    private val log = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java)
    private val stateLock = Any()
    private val autoPassState = ClientAutoPassState()

    @Volatile
    private var phaseStopProfile: PhaseStopProfile? = null

    private var humanPlayerId: Int? = null
    private var opponentPlayerId: Int? = null

    /** Install engine defaults for the current Forge game. Client auto-pass choices survive game replacement. */
    fun installPhaseStops(
        humanPlayerId: Int,
        opponentPlayerId: Int,
    ) {
        synchronized(stateLock) {
            this.humanPlayerId = humanPlayerId
            this.opponentPlayerId = opponentPlayerId
            phaseStopProfile = PhaseStopProfile.createDefaults(humanPlayerId, opponentPlayerId)
        }
    }

    /** Enabled own-turn stops for diagnostics without exposing mutable policy state. */
    fun enabledPhaseStops(playerId: Int) = synchronized(stateLock) { phaseStopProfile?.getEnabled(playerId) ?: emptySet() }

    /** Apply one immutable settings delta. Only this owner mutates policy state. */
    fun submit(settings: SettingsMessage) {
        synchronized(stateLock) {
            autoPassState.update(settings)
            val profile = phaseStopProfile
            val humanId = humanPlayerId
            val opponentId = opponentPlayerId

            if (settings.clearAllStops == SettingStatus.Set ||
                settings.clearAllYields == SettingStatus.Set
            ) {
                humanId?.let { profile?.clearAll(it) }
                opponentId?.let { profile?.clearAll(it) }
                autoPassState.clearOpponentStops()
            }

            val allStops = settings.stopsList + settings.transientStopsList
            if (profile != null) {
                if (humanId != null) applyStopsForPlayer(allStops, SettingScope.Team_ac6e, humanId, profile)
                if (opponentId != null) applyStopsForPlayer(allStops, SettingScope.Opponents, opponentId, profile)
            }

            val opponentEnabled = StopTypeMapping.parseStops(allStops, SettingScope.Opponents)
            val opponentDisabled =
                allStops
                    .filter { it.status == SettingStatus.Clear_a3fe }
                    .filter { it.appliesTo == SettingScope.Opponents || it.appliesTo == SettingScope.AnyPlayer }
                    .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
                    .toSet()
            opponentEnabled.forEach { autoPassState.setOpponentStop(it, true) }
            opponentDisabled.forEach { autoPassState.setOpponentStop(it, false) }
        }
    }

    /** Install the client full-control value submitted with a priority response. */
    fun submitAutoPassPriority(priority: AutoPassPriority) {
        synchronized(stateLock) { autoPassState.updateAutoPassPriority(priority) }
    }

    fun isFullControl(): Boolean = autoPassState.isFullControl

    fun shouldAutoPass(): Boolean = autoPassState.shouldAutoPass()

    fun hasOpponentStop(phase: PhaseType): Boolean = autoPassState.hasOpponentStop(phase)

    fun shouldStopForOpponent(
        isAiTurn: Boolean,
        phase: PhaseType?,
    ): Boolean = isAiTurn && phase != null && hasOpponentStop(phase)

    fun isPhaseStopped(
        playerId: Int,
        phase: PhaseType,
    ): Boolean = synchronized(stateLock) { phaseStopProfile?.isEnabled(playerId, phase) == true }

    /** Sole source of phase-stop gating and Visible, SyncOnly, and Skip classification. */
    internal fun classifyPriorityWindow(observation: PriorityWindowObservation): PriorityWindowDecision =
        synchronized(stateLock) {
            val fullControl = autoPassState.isFullControl
            val profile = phaseStopProfile
            val ownTurnStopped =
                observation.phase?.let { phase ->
                    humanPlayerId?.let { profile?.isEnabled(it, phase) }
                } == true
            if (!fullControl && observation.isOwnTurn && !ownTurnStopped) {
                return@synchronized PriorityWindowDecision.Skip(
                    AutoPassReason.PhaseNotStopped(observation.phase?.name ?: "UNKNOWN"),
                )
            }

            val opponentStop =
                !observation.isOwnTurn &&
                    observation.phase?.let(autoPassState::hasOpponentStop) == true
            val mode =
                priorityWindowMode(
                    fullControl = fullControl,
                    smartPhaseSkip = observation.smartPhaseSkip,
                    promptJustResolved = observation.promptJustResolved,
                    stackEmpty = observation.stackEmpty,
                    opponentStop = opponentStop,
                    hasMeaningfulAction = observation.hasMeaningfulAction,
                    forceVisible = observation.forceVisible,
                    continuation = observation.continuation,
                )
            if (mode == PriorityWindowMode.Skip) {
                PriorityWindowDecision.Skip(AutoPassReason.SmartPhaseSkip)
            } else {
                PriorityWindowDecision.Present(mode, autoPassState.shouldAutoPass())
            }
        }

    /** Sole source of auto-pass/Grant/Skip classification used by the pump. */
    internal fun decideHumanActions(
        game: Game,
        hasLegalAction: Boolean,
    ): PriorityDecision {
        val decision =
            synchronized(stateLock) {
                classifyActions(game.phaseHandler.phase?.name, hasLegalAction)
            }
        recordDecision(game, decision)
        return decision
    }

    private fun classifyActions(
        phase: String?,
        hasLegalAction: Boolean,
    ): PriorityDecision =
        when {
            autoPassState.isFullControl ->
                PriorityDecision.Grant(
                    phase = phase ?: "UNKNOWN",
                    actionCount = if (hasLegalAction) 1 else 0,
                )
            autoPassState.shouldAutoPass() && !hasLegalAction -> PriorityDecision.Skip(AutoPassReason.ClientAutoPass)
            !hasLegalAction -> PriorityDecision.Skip(AutoPassReason.OnlyPassActions)
            else -> PriorityDecision.Grant(phase = phase ?: "UNKNOWN", actionCount = 1)
        }

    /** The pump may suppress only a runtime-classified opponent pass-only window. */
    internal fun shouldSuppressOpponentPresentation(
        game: Game,
        isAiTurn: Boolean,
        hasLegalAction: Boolean,
    ): Boolean =
        synchronized(stateLock) {
            isAiTurn &&
                !autoPassState.isFullControl &&
                !autoPassState.hasOpponentStop(game.phaseHandler.phase ?: return false) &&
                classifyActions(null, hasLegalAction) is PriorityDecision.Skip
        }

    private fun priorityWindowMode(
        fullControl: Boolean,
        smartPhaseSkip: Boolean,
        promptJustResolved: Boolean,
        stackEmpty: Boolean,
        opponentStop: Boolean,
        hasMeaningfulAction: Boolean,
        forceVisible: Boolean,
        continuation: SynchronizationContinuation,
    ): PriorityWindowMode =
        when {
            fullControl ||
                forceVisible ||
                continuation == SynchronizationContinuation.RequireVisible ||
                opponentStop ||
                hasMeaningfulAction ->
                PriorityWindowMode.Visible
            promptJustResolved || continuation == SynchronizationContinuation.AllowSyncOnly || !stackEmpty || !smartPhaseSkip ->
                PriorityWindowMode.SyncOnly
            else -> PriorityWindowMode.Skip
        }

    /** One diagnostic trail for every runtime priority classification. */
    internal fun recordDecision(
        game: Game,
        decision: PriorityDecision,
    ) {
        log.info(
            "event=priority_decision source=runtime phase={} turn={} decision={}",
            game.phaseHandler.phase?.name,
            game.phaseHandler.turn,
            decision,
        )
    }

    private fun applyStopsForPlayer(
        stops: List<Stop>,
        scope: SettingScope,
        playerId: Int,
        profile: PhaseStopProfile,
    ) {
        val enabled = StopTypeMapping.parseStops(stops, scope)
        val disabled =
            stops
                .filter { it.status == SettingStatus.Clear_a3fe }
                .filter { it.appliesTo == scope || it.appliesTo == SettingScope.AnyPlayer }
                .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
                .toSet()
        enabled.forEach { profile.setEnabled(playerId, it, true) }
        disabled.forEach { profile.setEnabled(playerId, it, false) }
    }
}
