package leyline.bridge.handoff

import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.keyword.Keyword
import leyline.DevCheck
import org.slf4j.LoggerFactory

/**
 * Owns the [PlayerController.pendingDamageAssignment] future lifecycle for
 * [leyline.bridge.coord.PriorityLoopCoordinator.promptForCombatDamage].
 *
 * Mirrors [OptionalActionGate] / [NumericInputGate] on top of the shared
 * [PendingGate] core. Two divergences the caller supplies rather than the
 * gate itself: the timeout falls back to [GameActionBridge.DEFAULT_TIMEOUT_MS]
 * (not [PendingGate.DEFAULT_TIMEOUT_MS]) when no timeout is configured, and
 * the batched [OwnerContext.damageAssignCache] is cleared both before
 * publishing (stale entries from a previous damage step) and on completion
 * via [PendingGate.await]'s `onClear` hook.
 *
 * Threading: [await] runs on the Forge engine thread. It blocks until the
 * Netty session thread completes the future via `CombatHandler.onAssignDamage`.
 */
class DamageAssignmentGate(
    private val owner: OwnerContext,
    private val actionBridge: GameActionBridge,
) {
    private val log = LoggerFactory.getLogger(DamageAssignmentGate::class.java)

    fun await(
        attacker: Card,
        blockers: CardCollectionView,
        damageDealt: Int,
        defender: GameEntity?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>? {
        // Clear stale cache entries from a previous damage step.
        owner.damageAssignCache.clear()

        log.info(
            "assignCombatDamage: prompting for {} (id={}, damage={}, blockers={})",
            attacker.name,
            attacker.id,
            damageDealt,
            blockers.size,
        )

        return PendingGate.await(
            publish = { owner.pendingDamageAssignment = it },
            prompt = { future ->
                PendingDamageAssignment(
                    prompt =
                        DamageAssignmentPrompt(
                            attacker = attacker.toCombatDamageCard(),
                            blockers = blockers.map { it.toCombatDamageCard() },
                            damageDealt = damageDealt,
                            hasDefender = defender != null,
                            hasDeathtouch = attacker.hasKeyword(Keyword.DEATHTOUCH),
                            hasTrample = attacker.hasKeyword(Keyword.TRAMPLE),
                        ),
                    future = future,
                )
            },
            signal = { actionBridge.prioritySignal?.signal() },
            timeoutMs = { actionBridge.getTimeoutMs() ?: GameActionBridge.DEFAULT_TIMEOUT_MS },
            defaultOnTimeout = {
                DevCheck.failOnAutoPass { "assignCombatDamage timed out/error for ${attacker.name}" }
                fallback()
            },
            log = log,
            logContext = "assignCombatDamage",
            subject = attacker.name,
            timeoutDetail = "auto-assigning",
            onClear = { owner.damageAssignCache.clear() },
        )
    }
}

private fun Card.toCombatDamageCard() =
    CombatDamageCard(
        id = leyline.bridge.types.ForgeCardId(id),
        name = name,
        netToughness = netToughness,
        damage = damage,
    )
