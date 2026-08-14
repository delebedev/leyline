package leyline.bridge.handoff

import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.keyword.Keyword
import leyline.bridge.types.ForgeCardId
import org.slf4j.LoggerFactory

/**
 * Publishes coordinator-owned damage assignment interactions for
 * [leyline.bridge.coord.PriorityLoopCoordinator.promptForCombatDamage].
 *
 * Threading: [await] runs on the Forge engine thread. It blocks until the
 * session submits value assignments through the match coordinator.
 */
class DamageAssignmentGate(
    private val actionBridge: GameActionBridge,
    private val interactionRuntime: BlockingInteractionRuntime,
) {
    private val log = LoggerFactory.getLogger(DamageAssignmentGate::class.java)

    fun await(
        attacker: Card,
        blockers: CardCollectionView,
        damageDealt: Int,
        defender: GameEntity?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>? {
        interactionRuntime.takeCachedDamage(attacker, blockers)?.let { return it }

        log.info(
            "assignCombatDamage: prompting for {} (id={}, damage={}, blockers={})",
            attacker.name,
            attacker.id,
            damageDealt,
            blockers.size,
        )

        return interactionRuntime.awaitDamage(
            BlockingInteraction.Damage.of(
                attackerId = ForgeCardId(attacker.id),
                blockerIds = blockers.map { ForgeCardId(it.id) },
                damageDealt = damageDealt,
                hasDeathtouch = attacker.hasKeyword(Keyword.DEATHTOUCH),
                hasTrample = attacker.hasKeyword(Keyword.TRAMPLE),
                hasDefender = defender != null,
            ),
            attacker = attacker,
            blockers = blockers,
            defender = defender,
            timeoutMs = damageAssignmentTimeout(actionBridge.getTimeoutMs()),
            fallback = fallback,
        )
    }
}

internal fun damageAssignmentTimeout(configured: Long?): Long = configured ?: GameActionBridge.DEFAULT_TIMEOUT_MS
