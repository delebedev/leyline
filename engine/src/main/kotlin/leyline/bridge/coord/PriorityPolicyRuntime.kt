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

/** Immutable command submitted by a protocol adapter to the priority runtime. */
data class PrioritySettingsCommand(
    val settings: SettingsMessage,
    val humanPlayerId: Int?,
    val opponentPlayerId: Int?,
)

/**
 * The sole owner of client priority policy and its mutable settings state.
 *
 * Protocol heads submit [PrioritySettingsCommand] values. The engine reads
 * decisions from this runtime; it never reconstructs policy from client
 * messages or maintains a second settings state.
 */
class PriorityPolicyRuntime {
    private val log = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java)
    private val stateLock = Any()
    private val autoPassState = ClientAutoPassState()

    @Volatile
    private var phaseStopProfile: PhaseStopProfile? = null

    /** Install engine defaults for the current Forge game. Client auto-pass choices survive game replacement. */
    fun installPhaseStops(
        humanPlayerId: Int,
        opponentPlayerId: Int,
    ) {
        synchronized(stateLock) {
            phaseStopProfile = PhaseStopProfile.createDefaults(humanPlayerId, opponentPlayerId)
        }
    }

    /** Enabled own-turn stops for diagnostics without exposing mutable policy state. */
    fun enabledPhaseStops(playerId: Int) = synchronized(stateLock) { phaseStopProfile?.getEnabled(playerId) ?: emptySet() }

    /** Apply one immutable settings delta. Only this owner mutates policy state. */
    fun submit(command: PrioritySettingsCommand) {
        synchronized(stateLock) {
            autoPassState.update(command.settings)
            val profile = phaseStopProfile
            val humanPlayerId = command.humanPlayerId
            val opponentPlayerId = command.opponentPlayerId

            if (command.settings.clearAllStops == SettingStatus.Set ||
                command.settings.clearAllYields == SettingStatus.Set
            ) {
                humanPlayerId?.let { profile?.clearAll(it) }
                opponentPlayerId?.let { profile?.clearAll(it) }
                autoPassState.clearOpponentStops()
            }

            val allStops = command.settings.stopsList + command.settings.transientStopsList
            if (profile != null) {
                if (humanPlayerId != null) applyStopsForPlayer(allStops, SettingScope.Team_ac6e, humanPlayerId, profile)
                if (opponentPlayerId != null) applyStopsForPlayer(allStops, SettingScope.Opponents, opponentPlayerId, profile)
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

    /** Sole source of Visible, SyncOnly, and Skip presentation classification. */
    internal fun priorityWindowMode(
        fullControl: Boolean,
        smartPhaseSkip: Boolean,
        promptJustResolved: Boolean,
        stackEmpty: Boolean,
        opponentStop: Boolean,
        hasMeaningfulAction: Boolean,
        forceVisible: Boolean = false,
        continuation: SynchronizationContinuation = SynchronizationContinuation.Reevaluate,
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

    /** Sole source of auto-pass/Grant/Skip classification used by the pump. */
    fun decideHumanActions(
        game: Game,
        hasLegalAction: Boolean,
    ): PriorityDecision {
        val decision =
            when {
                isFullControl() ->
                    PriorityDecision.Grant(
                        phase = game.phaseHandler.phase?.name ?: "UNKNOWN",
                        actionCount = if (hasLegalAction) 1 else 0,
                    )
                shouldAutoPass() && !hasLegalAction -> PriorityDecision.Skip(AutoPassReason.ClientAutoPass)
                !hasLegalAction -> PriorityDecision.Skip(AutoPassReason.OnlyPassActions)
                else ->
                    PriorityDecision.Grant(
                        phase = game.phaseHandler.phase?.name ?: "UNKNOWN",
                        actionCount = 1,
                    )
            }
        recordDecision(game, decision)
        return decision
    }

    /** The pump may suppress only a runtime-classified opponent pass-only window. */
    fun shouldSuppressOpponentPresentation(
        game: Game,
        isAiTurn: Boolean,
        hasLegalAction: Boolean,
    ): Boolean =
        isAiTurn &&
            !isFullControl() &&
            !hasOpponentStop(game.phaseHandler.phase ?: return false) &&
            decideHumanActions(game, hasLegalAction).let { it is PriorityDecision.Skip }

    /** One diagnostic trail for every runtime priority classification. */
    fun recordDecision(
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
