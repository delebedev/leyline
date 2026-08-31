package leyline.bridge.coord

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.handoff.SynchronizationContinuation
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.PriorityDecision
import leyline.game.mapping.StopTypeMapping
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
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
class PriorityPolicyRuntime(
    private val matchId: String? = null,
) {
    private val log = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java)
    private val stateLock = Any()
    private var authoritativeSettings: SettingsMessage? = null
    private var autoPassOption = AutoPassOption.None_a465
    private var autoPassPriority = AutoPassPriority.None_a099
    private val opponentStops = mutableSetOf<PhaseType>()
    private val phaseStops = mutableMapOf<Int, MutableSet<PhaseType>>()
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
            phaseStops.clear()
            phaseStops[humanPlayerId] = HUMAN_DEFAULTS.toMutableSet()
            phaseStops[opponentPlayerId] = AI_DEFAULTS.toMutableSet()
        }
    }

    /** Enabled own-turn stops for diagnostics without exposing mutable policy state. */
    fun enabledPhaseStops(playerId: Int): Set<PhaseType> = synchronized(stateLock) { phaseStops[playerId]?.toSet() ?: emptySet() }

    /** Apply one immutable settings delta and return the authoritative accumulated settings. */
    fun submit(settings: SettingsMessage): SettingsMessage =
        synchronized(stateLock) {
            authoritativeSettings = mergeSettings(authoritativeSettings, settings)
            val humanId = humanPlayerId
            val opponentId = opponentPlayerId

            if (settings.autoPassOption != AutoPassOption.None_a465) autoPassOption = settings.autoPassOption

            if (settings.clearAllStops == SettingStatus.Set ||
                settings.clearAllYields == SettingStatus.Set
            ) {
                humanId?.let { phaseStops[it]?.clear() }
                opponentId?.let { phaseStops[it]?.clear() }
                opponentStops.clear()
            }

            val allStops = settings.stopsList + settings.transientStopsList
            if (humanId != null) applyStopsForPlayer(allStops, SettingScope.Team_ac6e, humanId)
            if (opponentId != null) applyStopsForPlayer(allStops, SettingScope.Opponents, opponentId)

            val opponentEnabled = StopTypeMapping.parseStops(allStops, SettingScope.Opponents)
            val opponentDisabled =
                allStops
                    .filter { it.status == SettingStatus.Clear_a3fe }
                    .filter { it.appliesTo == SettingScope.Opponents || it.appliesTo == SettingScope.AnyPlayer }
                    .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
                    .toSet()
            opponentEnabled.forEach { opponentStops.add(it) }
            opponentDisabled.forEach { opponentStops.remove(it) }
            checkNotNull(authoritativeSettings)
        }

    /** Install the client full-control value submitted with a priority response. */
    fun submitAutoPassPriority(priority: AutoPassPriority) {
        synchronized(stateLock) {
            if (priority != AutoPassPriority.None_a099) autoPassPriority = priority
        }
    }

    fun isFullControl(): Boolean = synchronized(stateLock) { autoPassPriority == AutoPassPriority.No_a099 }

    fun shouldAutoPass(): Boolean = synchronized(stateLock) { shouldAutoPassLocked() }

    fun hasOpponentStop(phase: PhaseType): Boolean = synchronized(stateLock) { phase in opponentStops }

    fun shouldStopForOpponent(
        isAiTurn: Boolean,
        phase: PhaseType?,
    ): Boolean = synchronized(stateLock) { isAiTurn && phase != null && phase in opponentStops }

    fun isPhaseStopped(
        playerId: Int,
        phase: PhaseType,
    ): Boolean = synchronized(stateLock) { phaseStops[playerId]?.contains(phase) == true }

    /** Sole source of phase-stop gating and Visible, SyncOnly, and Skip classification. */
    internal fun classifyPriorityWindow(observation: PriorityWindowObservation): PriorityWindowDecision =
        synchronized(stateLock) {
            val fullControl = autoPassPriority == AutoPassPriority.No_a099
            val ownTurnStopped =
                observation.isOwnTurn &&
                    observation.phase?.let { phase ->
                        humanPlayerId?.let { phaseStops[it]?.contains(phase) }
                    } == true
            if (!fullControl && observation.isOwnTurn && !ownTurnStopped) {
                return@synchronized PriorityWindowDecision.Skip(
                    AutoPassReason.PhaseNotStopped(observation.phase?.name ?: "UNKNOWN"),
                )
            }

            val opponentStop =
                !observation.isOwnTurn &&
                    observation.phase?.let { it in opponentStops } == true
            val intentionalPhaseStop =
                ownTurnStopped &&
                    observation.stackEmpty
            val mode =
                priorityWindowMode(
                    fullControl = fullControl,
                    phaseStop = intentionalPhaseStop,
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
                PriorityWindowDecision.Present(mode, shouldAutoPassLocked())
            }
        }

    private fun priorityWindowMode(
        fullControl: Boolean,
        phaseStop: Boolean,
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
                opponentStop ||
                hasMeaningfulAction ||
                (phaseStop && !promptJustResolved && smartPhaseSkip) ||
                (continuation == SynchronizationContinuation.RequireVisible && phaseStop) ->
                PriorityWindowMode.Visible
            promptJustResolved ||
                continuation == SynchronizationContinuation.AllowSyncOnly ||
                !stackEmpty ||
                !smartPhaseSkip ->
                PriorityWindowMode.SyncOnly
            else -> PriorityWindowMode.Skip
        }

    /** One diagnostic trail for every runtime priority classification. */
    internal fun recordDecision(
        game: Game,
        decision: PriorityDecision,
    ) {
        val skipped = decision as? PriorityDecision.Skip ?: return
        val event =
            log
                .atDebug()
                .addKeyValue("event", "match.priority_skipped")
                .addKeyValue("reason", skipped.reason.toString())
                .addKeyValue("phase", game.phaseHandler.phase?.name ?: "UNKNOWN")
                .addKeyValue("turn", game.phaseHandler.turn)
        val correlatedEvent = matchId?.let { event.addKeyValue("match_id", it) } ?: event
        correlatedEvent.log("Priority skipped")
    }

    private fun applyStopsForPlayer(
        stops: List<Stop>,
        scope: SettingScope,
        playerId: Int,
    ) {
        val enabled = StopTypeMapping.parseStops(stops, scope)
        val disabled =
            stops
                .filter { it.status == SettingStatus.Clear_a3fe }
                .filter { it.appliesTo == scope || it.appliesTo == SettingScope.AnyPlayer }
                .mapNotNull { StopTypeMapping.toPhaseType(it.stopType) }
                .toSet()
        val playerStops = phaseStops.getOrPut(playerId) { mutableSetOf() }
        enabled.forEach { playerStops.add(it) }
        disabled.forEach { playerStops.remove(it) }
    }

    private fun shouldAutoPassLocked(): Boolean {
        if (autoPassPriority == AutoPassPriority.No_a099) return false
        return autoPassOption == AutoPassOption.ResolveAll || autoPassOption == AutoPassOption.ResolveMyStackEffects
    }

    private fun mergeSettings(
        existing: SettingsMessage?,
        incoming: SettingsMessage,
    ): SettingsMessage {
        if (existing == null) return incoming
        val merged = existing.toBuilder()
        val stops = linkedMapOf<Pair<Int, Int>, Stop>()
        existing.stopsList.forEach { stops[it.stopType.number to it.appliesTo.number] = it }
        incoming.stopsList.forEach { stops[it.stopType.number to it.appliesTo.number] = it }
        merged.clearStops().addAllStops(stops.values)

        val transientStops = linkedMapOf<Pair<Int, Int>, Stop>()
        existing.transientStopsList.forEach { transientStops[it.stopType.number to it.appliesTo.number] = it }
        incoming.transientStopsList.forEach { transientStops[it.stopType.number to it.appliesTo.number] = it }
        merged.clearTransientStops().addAllTransientStops(transientStops.values)

        if (incoming.autoPassOption != AutoPassOption.None_a465) merged.autoPassOption = incoming.autoPassOption
        if (incoming.stackAutoPassOption != AutoPassOption.None_a465) merged.stackAutoPassOption = incoming.stackAutoPassOption
        return merged.build()
    }

    private companion object {
        val HUMAN_DEFAULTS =
            setOf(
                PhaseType.MAIN1,
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.MAIN2,
            )
        val AI_DEFAULTS =
            setOf(
                PhaseType.COMBAT_BEGIN,
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.END_OF_TURN,
            )
    }
}
