package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.toWireId
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.Step
import kotlin.collections.iterator

/**
 * Result of combat damage annotation generation.
 * [hasCombatDamage] signals that turnInfo should be overridden to the combat damage step.
 * [damagedThisTurnPersistent] is a single-element list (or empty) containing the
 * `DamagedThisTurn` persistent annotation for this GSM's new victims; the store
 * merges it with any existing per-turn annotation rather than allocating a new
 * one. [clearDamagedThisTurn] signals that the store should delete the active
 * per-turn `DamagedThisTurn` at the start of the next turn's Upkeep.
 */
data class CombatAnnotationResult(
    val annotations: List<AnnotationInfo>,
    val hasCombatDamage: Boolean = false,
    val damageStep: Step = Step.CombatDamage_a2cb,
    val damagedThisTurnPersistent: List<AnnotationInfo> = emptyList(),
    val clearDamagedThisTurn: Boolean = false,
)

/**
 * Stage 3 of the annotation pipeline: generate combat damage annotations.
 *
 * Pure functions — no shared mutable state.
 */
@Suppress("MemberNameEqualsClassName")
object CombatAnnotations {
    /**
     * Generate combat damage annotations from events.
     *
     * Uses [GameEvent.DamageDealtToCard] and [GameEvent.DamageDealtToPlayer] events
     * captured synchronously on the engine thread (before Forge clears combat state).
     * The combat object's attackers list is empty by the time we build the GSM,
     * so we cannot query it here.
     *
     * Annotation ordering matches expected protocol shape: PhaseOrStepModified → DamageDealt(s)
     * → SyntheticEvent → ModifiedLife → (ObjectIdChanged/ZoneTransfer handled by Stage 1).
     *
     * Delegates to the pure overload, adapting [GameBridge] calls to function parameters.
     */
    internal fun combatAnnotations(
        events: List<GameEvent>,
        bridge: GameBridge,
        prev: GsmSnapshot?,
        transferredIds: Map<ForgeCardId, Int> = emptyMap(),
    ): CombatAnnotationResult {
        val previousLifeTotals = prev?.seats?.associate { it.seatId.value to it.life } ?: emptyMap()
        val currentLifeTotals =
            previousLifeTotals.keys.associateWith { seat ->
                bridge.getPlayer(SeatId(seat))?.life ?: 0
            }
        return combatAnnotations(
            events = events,
            idResolver = { fid ->
                val transferred = transferredIds[fid]
                if (transferred != null) InstanceId(transferred) else bridge.getOrAllocInstanceId(fid)
            },
            previousLifeTotals = previousLifeTotals,
            currentLifeTotals = currentLifeTotals,
        )
    }

    /**
     * Generate combat damage annotations — pure overload.
     * Takes function parameters instead of [GameBridge] for independent testability.
     *
     * [idResolver] maps forgeCardId → instanceId.
     * [previousLifeTotals] is seatId → life total from previous GSM baseline.
     * [currentLifeTotals] is seatId → current life total from engine.
     */
    internal fun combatAnnotations(
        events: List<GameEvent>,
        idResolver: (ForgeCardId) -> InstanceId,
        previousLifeTotals: Map<Int, Int>,
        currentLifeTotals: Map<Int, Int>,
    ): CombatAnnotationResult {
        val cardDamage = events.filterIsInstance<GameEvent.DamageDealtToCard>()
        val playerDamage = events.filterIsInstance<GameEvent.DamageDealtToPlayer>()
        val clearOnUpkeep =
            events.any { ev ->
                ev is GameEvent.PhaseChanged && ev.step == Step.Upkeep_a2cb.number
            }
        if (cardDamage.isEmpty() && playerDamage.isEmpty()) {
            return CombatAnnotationResult(
                annotations = emptyList(),
                clearDamagedThisTurn = clearOnUpkeep,
            )
        }

        val annotations = mutableListOf<AnnotationInfo>()

        // PhaseOrStepModified is now emitted from GameEvent.PhaseChanged in Stage 2b.
        // CombatDamage phase fires via GameEventTurnPhase before damage events.

        // --- DamageDealt: creature → creature ---
        for (ev in cardDamage) {
            val sourceIid = idResolver(ev.sourceCardId)
            val targetIid = idResolver(ev.targetCardId)
            annotations.add(AnnotationBuilder.damageDealt(sourceIid, targetId = targetIid.toWireId(), ev.amount))
        }

        // --- DamageDealt: creature → player ---
        var firstPlayerDamageAttacker: InstanceId? = null
        var playerDamageSeat: SeatId? = null
        for (ev in playerDamage) {
            val sourceIid = idResolver(ev.sourceCardId)
            annotations.add(AnnotationBuilder.damageDealt(sourceIid, targetId = ev.targetSeatId.toWireId(), ev.amount))
            if (firstPlayerDamageAttacker == null) firstPlayerDamageAttacker = sourceIid
            playerDamageSeat = ev.targetSeatId
        }

        // --- SyntheticEvent when player takes combat damage ---
        if (playerDamageSeat != null && firstPlayerDamageAttacker != null) {
            annotations.add(AnnotationBuilder.syntheticEvent(firstPlayerDamageAttacker, playerDamageSeat))
        }

        // --- ModifiedLife from combat damage in this frame ---
        val playerDamageBySeat = playerDamage.groupBy { it.targetSeatId.value }
        for ((seat, eventsForSeat) in playerDamageBySeat) {
            val delta = -eventsForSeat.sumOf { it.amount }
            if (delta != 0) {
                annotations.add(
                    AnnotationBuilder.modifiedLife(
                        SeatId(seat),
                        delta,
                        affectorId = firstPlayerDamageAttacker,
                    ),
                )
            }
        }

        val damagedThisTurnPersistent =
            if (cardDamage.isNotEmpty()) {
                val victims = cardDamage.map { idResolver(it.targetCardId) }.distinct()
                listOf(AnnotationBuilder.damagedThisTurn(affectedIds = victims))
            } else {
                emptyList()
            }

        return CombatAnnotationResult(
            annotations = annotations,
            hasCombatDamage = true,
            damageStep = events.combatDamageStep(),
            damagedThisTurnPersistent = damagedThisTurnPersistent,
            clearDamagedThisTurn = clearOnUpkeep,
        )
    }

    private fun List<GameEvent>.combatDamageStep(): Step {
        var currentDamageStep = Step.CombatDamage_a2cb
        for (event in this) {
            if (event is GameEvent.PhaseChanged) {
                if (event.step == Step.FirstStrikeDamage_a2cb.number) currentDamageStep = Step.FirstStrikeDamage_a2cb
                if (event.step == Step.CombatDamage_a2cb.number) currentDamageStep = Step.CombatDamage_a2cb
            }
            if (event is GameEvent.DamageDealtToCard || event is GameEvent.DamageDealtToPlayer) {
                return currentDamageStep
            }
        }
        return Step.CombatDamage_a2cb
    }
}
