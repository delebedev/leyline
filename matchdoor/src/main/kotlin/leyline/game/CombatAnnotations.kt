package leyline.game

import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Result of combat damage annotation generation.
 * [hasCombatDamage] signals that turnInfo should be overridden to CombatDamage.
 */
data class CombatAnnotationResult(
    val annotations: List<AnnotationInfo>,
    val hasCombatDamage: Boolean = false,
)

/**
 * Stage 3 of the annotation pipeline: generate combat damage annotations.
 *
 * Pure functions — no shared mutable state.
 * Extracted from [AnnotationPipeline] for independent maintainability.
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
     * Annotation ordering matches real server: PhaseOrStepModified → DamageDealt(s)
     * → SyntheticEvent → ModifiedLife → (ObjectIdChanged/ZoneTransfer handled by Stage 1).
     *
     * Delegates to the pure overload, adapting [GameBridge] calls to function parameters.
     */
    internal fun combatAnnotations(
        events: List<GameEvent>,
        bridge: GameBridge,
    ): CombatAnnotationResult {
        val prev = bridge.getDiffBaselineState()
        val previousLifeTotals = prev?.playersList
            ?.associate { it.systemSeatNumber to it.lifeTotal } ?: emptyMap()
        val currentLifeTotals = previousLifeTotals.keys.associateWith { seat ->
            bridge.getPlayer(SeatId(seat))?.life ?: 0
        }
        return combatAnnotations(
            events = events,
            idResolver = { fid -> bridge.getOrAllocInstanceId(fid) },
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
        if (cardDamage.isEmpty() && playerDamage.isEmpty()) return CombatAnnotationResult(emptyList())

        val annotations = mutableListOf<AnnotationInfo>()

        // PhaseOrStepModified is now emitted from GameEvent.PhaseChanged in Stage 2b.
        // CombatDamage phase fires via GameEventTurnPhase before damage events.

        // --- DamageDealt: creature → creature ---
        for (ev in cardDamage) {
            val sourceIid = idResolver(ev.sourceCardId).value
            val targetIid = idResolver(ev.targetCardId).value
            annotations.add(AnnotationBuilder.damageDealt(sourceIid, targetId = targetIid, ev.amount))
        }

        // --- DamageDealt: creature → player ---
        var firstPlayerDamageAttackerIid: Int? = null
        var playerDamageSeat: Int? = null
        for (ev in playerDamage) {
            val sourceIid = idResolver(ev.sourceCardId).value
            annotations.add(AnnotationBuilder.damageDealt(sourceIid, targetId = ev.targetSeatId.value, ev.amount))
            if (firstPlayerDamageAttackerIid == null) firstPlayerDamageAttackerIid = sourceIid
            playerDamageSeat = ev.targetSeatId.value
        }

        // --- DamagedThisTurn badges ---
        for (ev in cardDamage) {
            val targetIid = idResolver(ev.targetCardId).value
            annotations.add(AnnotationBuilder.damagedThisTurn(targetIid))
        }

        // --- SyntheticEvent when player takes combat damage ---
        if (playerDamageSeat != null && firstPlayerDamageAttackerIid != null) {
            annotations.add(AnnotationBuilder.syntheticEvent(firstPlayerDamageAttackerIid, playerDamageSeat))
        }

        // --- ModifiedLife from baseline comparison ---
        for ((seat, prevLife) in previousLifeTotals) {
            val currentLife = currentLifeTotals[seat] ?: continue
            val delta = currentLife - prevLife
            if (delta != 0) {
                annotations.add(AnnotationBuilder.modifiedLife(seat, delta, affectorId = firstPlayerDamageAttackerIid ?: 0))
            }
        }

        return CombatAnnotationResult(annotations, hasCombatDamage = true)
    }
}
