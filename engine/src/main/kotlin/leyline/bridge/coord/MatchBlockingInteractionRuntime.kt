package leyline.bridge.coord

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.keyword.Keyword
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.DamageAssignmentCommand
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.opponent
import leyline.game.PendingPromptCut
import leyline.game.bundle.BlockingInteractionMaterializer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class PublishedBlockingInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: BlockingInteraction,
)

/** Blocking prompt handles and value answers beneath [MatchCutCoordinator]. */
internal class MatchBlockingInteractionRuntime(
    private val owner: MatchCutCoordinator,
) : BlockingInteractionRuntime,
    PromptTerminalCutOwner {
    override val terminalPriority = PromptTerminalPriority.Blocking

    private sealed interface Answer {
        data class Optional(
            val accepted: Boolean,
        ) : Answer

        data class Numeric(
            val value: Int,
        ) : Answer

        data class Damage(
            val assignments: List<DamageAssignmentValue>,
        ) : Answer
    }

    private data class DamageAssignmentValue(
        val attackerId: ForgeCardId,
        val assignments: Map<ForgeCardId?, Int>,
    )

    private data class DamageRules(
        val expectedTotalByAttacker: Map<Int, Int?>,
        val hasTrampleByAttacker: Map<Int, Boolean>,
        val hasDefenderByAttacker: Map<Int, Boolean>,
    )

    private data class Window(
        val published: PublishedBlockingInteraction,
        val cut: PendingPromptCut<BlockingInteraction>,
        val future: CompletableFuture<Answer>,
        val damageCards: Map<ForgeCardId, Card> = emptyMap(),
        val damageAttackerByInstanceId: Map<Int, ForgeCardId> = emptyMap(),
        val damageBlockerByInstanceId: Map<Int, ForgeCardId> = emptyMap(),
        val damageBlockerInstanceIdsByAttacker: Map<Int, Set<Int>> = emptyMap(),
        val damageDefenderInstanceIds: Set<Int> = emptySet(),
        val damageExpectedTotalByAttacker: Map<Int, Int?> = emptyMap(),
        val damageHasTrampleByAttacker: Map<Int, Boolean> = emptyMap(),
        val damageHasDefenderByAttacker: Map<Int, Boolean> = emptyMap(),
    )

    private var window: Window? = null
    private val damageCache = mutableMapOf<ForgeCardId, DamageAssignmentValue>()

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterMaterialization: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean {
        val pending = publish(interaction) { feed, game -> feed.builder.optionalInteractionBundle(game, owner.counter, interaction) }
        return try {
            (await(pending, timeoutMs ?: 45_000L) as Answer.Optional).accepted
        } catch (_: TimeoutException) {
            defaultOnTimeout
        } finally {
            clear(pending)
        }
    }

    override fun awaitNumeric(
        interaction: BlockingInteraction.Numeric,
        timeoutMs: Long?,
    ): Int {
        val pending = publish(interaction) { feed, _ -> feed.builder.numericInteractionBundle(owner.counter, interaction) }
        return try {
            (await(pending, timeoutMs ?: 45_000L) as Answer.Numeric).value
        } catch (_: TimeoutException) {
            interaction.defaultValue
        } finally {
            clear(pending)
        }
    }

    override fun awaitDamage(
        interaction: BlockingInteraction.Damage,
        attacker: Card,
        blockers: CardCollectionView,
        defender: GameEntity?,
        timeoutMs: Long?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>? {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            damageCache.clear()
        }
        val cards = (listOf(attacker) + blockers).associateBy { ForgeCardId(it.id) }
        val toughness = blockers.associate { ForgeCardId(it.id) to maxOf(0, it.netToughness - it.damage) }
        val pending =
            publish(interaction, cards, ForgeCardId(attacker.id)) { feed, _ ->
                feed.builder.damageInteractionBundle(owner.counter, interaction, toughness)
            }
        return try {
            val answer = await(pending, timeoutMs ?: 45_000L) as Answer.Damage
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val first = answer.assignments.firstOrNull { it.attackerId == interaction.attackerId } ?: return mutableMapOf()
                answer.assignments.filter { it.attackerId != interaction.attackerId }.forEach { assignment ->
                    damageCache[assignment.attackerId] = assignment
                }
                resolveDamageMap(pending.damageCards, first.assignments)
            }
        } catch (_: TimeoutException) {
            fallback()
        } finally {
            clear(pending)
        }
    }

    override fun takeCachedDamage(
        attacker: Card,
        blockers: CardCollectionView,
    ): MutableMap<Card?, Int>? =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val assignment = damageCache.remove(ForgeCardId(attacker.id)) ?: return@synchronized null
            val cards = (listOf(attacker) + blockers).associateBy { ForgeCardId(it.id) }
            resolveDamageMap(cards, assignment.assignments)
        }

    override fun current(): PublishedBlockingInteraction? =
        synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone }?.published }

    override fun claimTerminalCutLocked(): PendingPromptCut<BlockingInteraction>? = window?.takeUnless { it.future.isDone }?.cut

    fun submitOptional(
        interactionId: String,
        gameStateId: Int,
        accepted: Boolean,
    ): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    val pending = matching(interactionId, gameStateId) ?: return false
                    val optional = pending.published.interaction as? BlockingInteraction.Optional ?: return false
                    optional.commanderReturn?.let { context ->
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val prepared =
                            try {
                                owner.feed(owner.humanSeat).builder.commanderPromptCleanup(
                                    game,
                                    owner.counter,
                                    context,
                                    owner.beforeCommanderCleanupMaterialization,
                                )
                            } catch (ex: Exception) {
                                owner.fail(ex)
                            }
                        owner.cutInstaller.install(
                            owner.feed(owner.humanSeat),
                            PreparedCut(prepared.bundle.messages, prepared.transition, prepared.closesPlaybackFrame),
                            CutInstallHooks(beforeInstall = owner.beforeCommanderCleanupInstall),
                        ) { ex -> owner.fail(ex) }
                    }
                    pending.future.complete(Answer.Optional(accepted))
                }
            }
        }

    fun submitNumeric(
        interactionId: String,
        gameStateId: Int,
        value: Int,
    ): Boolean = complete(interactionId, gameStateId, Answer.Numeric(value))

    fun submitDamageCommand(
        interactionId: String,
        gameStateId: Int,
        commands: List<DamageAssignmentCommand>,
    ): Boolean =
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    val pending = matching(interactionId, gameStateId) ?: return false
                    if (commands.map { it.attackerInstanceId }.distinct().size != commands.size) return false
                    val assignments =
                        commands.map { command ->
                            val attacker = pending.damageAttackerByInstanceId[command.attackerInstanceId] ?: return false
                            val expectedTotal = pending.damageExpectedTotalByAttacker[command.attackerInstanceId]
                            if (expectedTotal != null &&
                                command.totalDamage != 0 &&
                                command.totalDamage != expectedTotal
                            ) {
                                return false
                            }
                            if (command.assignments
                                    .map { it.targetInstanceId }
                                    .distinct()
                                    .size != command.assignments.size
                            ) {
                                return false
                            }
                            val resolved =
                                command.assignments
                                    .map { row ->
                                        val instanceId = row.targetInstanceId
                                        val amount = row.assignedDamage
                                        if (amount < 0) return false
                                        val blocker =
                                            if (instanceId in pending.damageDefenderInstanceIds) {
                                                null
                                            } else {
                                                if (
                                                    instanceId !in
                                                    pending.damageBlockerInstanceIdsByAttacker[command.attackerInstanceId].orEmpty()
                                                ) {
                                                    return false
                                                }
                                                pending.damageBlockerByInstanceId[instanceId] ?: return false
                                            }
                                        blocker to amount
                                    }.toMap()
                                    .toMutableMap()
                            val assigned = resolved.values.sum()
                            val expected = expectedTotal?.takeIf { command.totalDamage == 0 || command.totalDamage == it }
                            if (expected != null && assigned > expected) return false
                            if (expected != null && assigned < expected) {
                                if (pending.damageHasTrampleByAttacker[command.attackerInstanceId] != true ||
                                    pending.damageHasDefenderByAttacker[command.attackerInstanceId] != true
                                ) {
                                    return false
                                }
                                val overflow = expected - assigned
                                if (resolved.containsKey(null)) return false
                                resolved[null] = overflow
                            }
                            DamageAssignmentValue(attacker, resolved)
                        }
                    val feed = owner.feed(owner.humanSeat)
                    feed.queue.add(feed.builder.damageAssignmentConfirmation(owner.counter).messages)
                    owner.signalDelivery()
                    pending.future.complete(Answer.Damage(assignments))
                }
            }
        }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
            damageCache.clear()
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            window = null
            damageCache.clear()
        }
    }

    private fun publish(
        interaction: BlockingInteraction,
        damageCards: Map<ForgeCardId, Card> = emptyMap(),
        damageAttackerId: ForgeCardId? = null,
        build: (MatchCutCoordinator.ViewerFeed, Game) -> BlockingInteractionMaterializer.Prepared,
    ): Window {
        owner.beforePublicationLock?.invoke()
        val pending =
            synchronized(owner.counter) {
                synchronized(owner.bridge.projectionBuildLock) {
                    synchronized(owner.feedLock) {
                        owner.ensureOpen()
                        check(window == null) { "A blocking interaction is already pending" }
                        val feed = owner.feed(owner.humanSeat)
                        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                        val prepared =
                            try {
                                build(feed, game).also { afterMaterialization?.invoke() }
                            } catch (ex: Exception) {
                                owner.fail(ex)
                            }
                        val published =
                            PublishedBlockingInteraction(
                                UUID.randomUUID().toString(),
                                checkNotNull(prepared.bundle.actionGameStateId),
                                interaction,
                            )
                        val exact =
                            PendingPromptCut(
                                published.interactionId,
                                published.gameStateId,
                                interaction,
                                prepared.bundle.messages,
                                prepared.transition,
                            )
                        val damage = interaction as? BlockingInteraction.Damage
                        val combat = game.phaseHandler.combat
                        val combatAttackers = combat?.attackers?.toList().orEmpty()
                        val combatBlockers = combat?.getAllBlockers()?.toList().orEmpty()
                        val combatCards =
                            (combatAttackers + combatBlockers).associateBy { ForgeCardId(it.id) }
                        val allCards = combatCards + damageCards
                        val attackerForgeIds =
                            (combatAttackers.map { ForgeCardId(it.id) } + damageAttackerId).filterNotNull().distinct()
                        val blockersByForgeId =
                            (combatBlockers.map { ForgeCardId(it.id) } + damageCards.keys)
                                .filter { it !in attackerForgeIds }
                                .distinct()
                        val attackerInstanceIds =
                            attackerForgeIds.associate { forgeId -> owner.bridge.getOrAllocInstanceId(forgeId).value to forgeId }
                        val damageBlockerInstanceIdsByAttacker =
                            blockerInstanceIdsByAttacker(
                                combat,
                                combatCards,
                                damageCards,
                                damageAttackerId,
                                attackerInstanceIds,
                            )
                        val damageRules = damageRules(attackerInstanceIds, damageAttackerId, damage, combat, combatCards)
                        val defenderInstanceIds =
                            if (damage?.hasDefender == true) setOf(owner.humanSeat.opponent.value) else emptySet()
                        val created =
                            Window(
                                published,
                                exact,
                                CompletableFuture(),
                                allCards,
                                attackerInstanceIds,
                                blockersByForgeId.associate { forgeId -> owner.bridge.getOrAllocInstanceId(forgeId).value to forgeId },
                                damageBlockerInstanceIdsByAttacker,
                                defenderInstanceIds,
                                damageRules.expectedTotalByAttacker,
                                damageRules.hasTrampleByAttacker,
                                damageRules.hasDefenderByAttacker,
                            )
                        owner.cutInstaller.install(
                            feed,
                            PreparedCut(prepared.bundle.messages, prepared.transition, prepared.closesPlaybackFrame),
                            CutInstallHooks(beforeInstall = beforeInstall),
                        ) { ex -> owner.fail(ex, exact) }
                        window = created
                        created
                    }
                }
            }
        owner.bridge.prioritySignal.signal()
        return pending
    }

    private fun await(
        pending: Window,
        timeoutMs: Long,
    ): Answer =
        try {
            pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (pending.future.completeExceptionally(timeout)) {
                    if (window === pending) window = null
                    damageCache.clear()
                    throw timeout
                }
                owner.ensureOpen()
                try {
                    checkNotNull(pending.future.getNow(null)) { "Completed interaction has no answer" }
                } catch (ex: java.util.concurrent.CompletionException) {
                    throw ex.cause ?: ex
                }
            }
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun clear(pending: Window) {
        synchronized(owner.feedLock) {
            if (window === pending) window = null
        }
    }

    private fun blockerInstanceIdsByAttacker(
        combat: forge.game.combat.Combat?,
        combatCards: Map<ForgeCardId, Card>,
        damageCards: Map<ForgeCardId, Card>,
        damageAttackerId: ForgeCardId?,
        attackerInstanceIds: Map<Int, ForgeCardId>,
    ): Map<Int, Set<Int>> =
        attackerInstanceIds.entries.associate { (instanceId, forgeId) ->
            val blockerForgeIds =
                when {
                    combatCards[forgeId] != null ->
                        combat
                            ?.getBlockers(combatCards.getValue(forgeId))
                            ?.map { ForgeCardId(it.id) }
                            .orEmpty()
                    forgeId == damageAttackerId -> damageCards.keys.filter { it != forgeId }
                    else -> emptyList()
                }
            instanceId to blockerForgeIds.map { owner.bridge.getOrAllocInstanceId(it).value }.toSet()
        }

    private fun damageRules(
        attackerInstanceIds: Map<Int, ForgeCardId>,
        damageAttackerId: ForgeCardId?,
        damage: BlockingInteraction.Damage?,
        combat: forge.game.combat.Combat?,
        combatCards: Map<ForgeCardId, Card>,
    ): DamageRules =
        DamageRules(
            attackerInstanceIds.entries.associate { (instanceId, forgeId) ->
                instanceId to
                    when {
                        forgeId == damageAttackerId -> damage?.damageDealt
                        else -> combatCards[forgeId]?.netCombatDamage
                    }
            },
            attackerInstanceIds.entries.associate { (instanceId, forgeId) ->
                instanceId to
                    if (forgeId == damageAttackerId) {
                        damage?.hasTrample == true
                    } else {
                        combatCards[forgeId]?.hasKeyword(Keyword.TRAMPLE) == true
                    }
            },
            attackerInstanceIds.entries.associate { (instanceId, forgeId) ->
                instanceId to
                    if (forgeId == damageAttackerId) {
                        damage?.hasDefender == true
                    } else {
                        combatCards[forgeId]?.let { combat?.getDefenderByAttacker(it) != null } == true
                    }
            },
        )

    private fun complete(
        interactionId: String,
        gameStateId: Int,
        answer: Answer,
    ): Boolean =
        synchronized(owner.feedLock) {
            matching(interactionId, gameStateId)?.future?.complete(answer) ?: false
        }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
    ): Window? {
        val pending = window ?: return null
        if (pending.future.isDone) return null
        if (pending.published.interactionId != interactionId || pending.published.gameStateId != gameStateId) {
            return null
        }
        return pending
    }

    private fun resolveDamageMap(
        cards: Map<ForgeCardId, Card>,
        assignments: Map<ForgeCardId?, Int>,
    ): MutableMap<Card?, Int> = assignments.entries.associateTo(linkedMapOf()) { (id, amount) -> id?.let(cards::get) to amount }
}
