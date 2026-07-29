package leyline.game

import forge.game.Game
import leyline.bridge.types.SeatId
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.combatDamageFact
import leyline.game.mapping.NaiveGsmActionCapture
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import leyline.game.event.GameEvent as LeylineGameEvent

/**
 * Engine-thread adapter for interactive playback cuts.
 *
 * It materializes one immutable yield and publishes it to the owner FIFO.
 * Protocol compilation and sequence allocation are deliberately absent.
 */
internal class InteractivePlaybackMaterializer(
    private val bridge: GameBridge,
    private val matchId: String,
    private val seatId: Int,
    private val combatFramePlanner: CombatFramePlanner,
) {
    private var pendingResolutionFrame: FrameEventLog? = null

    fun isAwaitingResolutionBoundary(): Boolean = pendingResolutionFrame != null

    /**
     * Materialize the current cut. Returns false while noncombat damage waits
     * for the matching stack-exit boundary.
     */
    fun materialize(
        game: Game,
        cutReason: PlaybackCutReason,
        turnStarted: Boolean,
    ): Boolean {
        val reservation = bridge.reserveBundleFrame(seatId)
        var published = false
        try {
            val events =
                pendingResolutionFrame?.mergeReservedInput(reservation.events)
                    ?: reservation.events
            if (events.shouldAwaitResolutionBoundary()) {
                pendingResolutionFrame = events
                bridge.releaseBundleFrame(reservation)
                return false
            }

            val combatFrames =
                if (events.events.shouldSplitCombatDamageWindow()) {
                    combatFramePlanner.materialize(game, events.events)
                } else {
                    emptyList()
                }
            bridge.publishPlaybackYield(
                PlaybackYield(
                    sourceGeneration = reservation.sourceGeneration,
                    cutReason = cutReason,
                    observation =
                        bridge.materializeEngineObservation(
                            game,
                            GsmSnapshot.captureForPlayback(game, bridge, matchId),
                        ),
                    events = events,
                    reservation = reservation,
                    turnStarted = turnStarted,
                    combatFrames = combatFrames,
                    naiveActions = NaiveGsmActionCapture.materialize(seatId, bridge),
                ),
            )
            published = true
            pendingResolutionFrame = null
            if (bridge.consumePromptTimeoutNeedsAutoAdvance()) {
                bridge.autoAdvanceRequester?.invoke("prompt timeout playback queued")
            }
            return true
        } catch (failure: Throwable) {
            if (!published) bridge.releaseBundleFrame(reservation)
            throw failure
        }
    }
}

/** Materializes combat subframes without assigning protocol sequence numbers. */
internal class CombatFramePlanner(
    private val bridge: GameBridge,
    private val viewingSeatId: Int,
) {
    fun materialize(
        game: Game,
        events: List<LeylineGameEvent>,
    ): List<CombatYieldFrame> {
        val damageFrames = events.combatDamageFrames(game)
        val endCombatEvents =
            events.filter { event ->
                event is LeylineGameEvent.PhaseChanged && event.step == Step.EndCombat_a2cb.number
            }
        return damageFrames.map { damageFrame ->
            CombatYieldFrame(FrameEventLog(damageFrame.events), damageFrame.lifeTotals)
        } +
            if (endCombatEvents.isNotEmpty()) {
                listOf(CombatYieldFrame(FrameEventLog(endCombatEvents), emptyMap()))
            } else {
                emptyList()
            }
    }

    private data class CombatDamageFrame(
        val events: List<LeylineGameEvent>,
        val lifeTotals: Map<Int, Int> = emptyMap(),
    )

    private fun List<LeylineGameEvent>.combatDamageFrames(game: Game): List<CombatDamageFrame> {
        if (!canSafelySplitCombatDamage()) {
            return listOf(
                CombatDamageFrame(
                    filterNot { event -> event is LeylineGameEvent.PhaseChanged }
                        .prependCombatDamagePhase(game, this),
                ),
            )
        }

        val frames = mutableListOf<CombatDamageFrame>()
        val current = mutableListOf<LeylineGameEvent>()

        fun flushFrame() {
            if (current.hasCombatDamage()) {
                frames += CombatDamageFrame(current.toList(), current.lifeTotals())
            }
            current.clear()
        }

        for (event in this) {
            if (event is LeylineGameEvent.PhaseChanged) {
                if (event.isDamageStep()) {
                    if (current.hasCombatDamage()) {
                        flushFrame()
                    } else {
                        current.removeAll { pending -> pending is LeylineGameEvent.PhaseChanged && pending.isDamageStep() }
                    }
                    current += event
                }
                continue
            }
            current += event
        }

        flushFrame()
        if (frames.isNotEmpty()) return frames

        return listOf(
            CombatDamageFrame(
                filterNot { event -> event is LeylineGameEvent.PhaseChanged }
                    .prependCombatDamagePhase(game, this),
            ),
        )
    }

    private fun List<LeylineGameEvent>.canSafelySplitCombatDamage(): Boolean {
        var inDamageStep = false
        for (event in this) {
            val damageFact = event.combatDamageFact()
            if (event is LeylineGameEvent.PhaseChanged) {
                if (event.isDamageStep()) inDamageStep = true
            } else if (damageFact != null) {
                if (!damageFact || !inDamageStep) return false
                if (event is LeylineGameEvent.DamageDealtToCard) return false
            } else if (event is LeylineGameEvent.LifeChanged ||
                event == LeylineGameEvent.CombatEnded
            ) {
                if (!inDamageStep) return false
            } else {
                if (!inDamageStep && !event.isSafeBeforeDamageStep()) return false
            }
        }
        return true
    }

    private fun List<LeylineGameEvent>.hasCombatDamage(): Boolean = any { it.combatDamageFact() == true }

    private fun LeylineGameEvent.isSafeBeforeDamageStep(): Boolean =
        this is LeylineGameEvent.CardTapped ||
            this is LeylineGameEvent.AttackersDeclared ||
            this is LeylineGameEvent.BlockersDeclared

    private fun List<LeylineGameEvent>.lifeTotals(): Map<Int, Int> =
        filterIsInstance<LeylineGameEvent.LifeChanged>()
            .associate { event -> event.seatId.value to event.newLife }

    private fun LeylineGameEvent.PhaseChanged.isDamageStep(): Boolean =
        step == Step.FirstStrikeDamage_a2cb.number || step == Step.CombatDamage_a2cb.number

    private fun List<LeylineGameEvent>.prependCombatDamagePhase(
        game: Game,
        sourceEvents: List<LeylineGameEvent>,
    ): List<LeylineGameEvent> {
        val activeSeat = combatDamageSourceSeat(sourceEvents) ?: currentTurnSeat(game) ?: viewingSeatId
        return listOf(
            LeylineGameEvent.PhaseChanged(
                SeatId(activeSeat),
                Phase.Combat_a549.number,
                sourceEvents.combatDamageStep(),
            ),
        ) + this
    }

    private fun List<LeylineGameEvent>.combatDamageStep(): Int {
        var currentDamageStep = Step.CombatDamage_a2cb.number
        for (event in this) {
            if (event is LeylineGameEvent.PhaseChanged &&
                (event.step == Step.FirstStrikeDamage_a2cb.number || event.step == Step.CombatDamage_a2cb.number)
            ) {
                currentDamageStep = event.step
            }
            if (event.combatDamageFact() == true) return currentDamageStep
        }
        return Step.CombatDamage_a2cb.number
    }

    private fun combatDamageSourceSeat(events: List<LeylineGameEvent>): Int? {
        events
            .firstNotNullOfOrNull { event ->
                (event as? LeylineGameEvent.DamageDealtToPlayer)
                    ?.takeIf { it.sourceKind == DamageSourceKind.Combat }
                    ?.targetSeatId
                    ?.value
            }?.let { defenderSeat ->
                val otherSeats = bridge.gameSeatIds() - defenderSeat
                if (otherSeats.size == 1) return otherSeats.single()
                return if (defenderSeat == 1) 2 else 1
            }
        val sourceId =
            events.firstNotNullOfOrNull { event ->
                when {
                    event is LeylineGameEvent.DamageDealtToCard ->
                        event.sourceCardId.takeIf { event.sourceKind == DamageSourceKind.Combat }
                    event is LeylineGameEvent.DamageDealtToPlayer ->
                        event.sourceCardId.takeIf { event.sourceKind == DamageSourceKind.Combat }
                    else -> null
                }
            } ?: return null
        val controller = bridge.findCard(sourceId)?.controller ?: return null
        return bridge.gameSeatIds().firstOrNull { seat -> bridge.getPlayer(SeatId(seat)) == controller }
    }

    private fun currentTurnSeat(game: Game): Int? {
        val turnPlayer = game.phaseHandler.playerTurn ?: return null
        return bridge.gameSeatIds().firstOrNull { seat -> bridge.getPlayer(SeatId(seat)) == turnPlayer }
    }
}
