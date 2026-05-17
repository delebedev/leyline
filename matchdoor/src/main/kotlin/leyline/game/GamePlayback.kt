package leyline.game

import com.google.common.eventbus.Subscribe
import forge.game.ability.ApiType
import forge.game.cost.CostPartMana
import forge.game.event.*
import forge.game.keyword.Keyword
import forge.game.phase.PhaseType
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step
import java.util.concurrent.ConcurrentLinkedQueue
import leyline.game.event.GameEvent as LeylineGameEvent

/**
 * Captures per-action GRE state diffs for the client, pacing remote turns
 * by sleeping the game thread at key events.
 *
 * Subscribes to the engine's Guava EventBus. Events fire synchronously on
 * the game thread -- sleeping here freezes engine progress and state, making
 * it safe to snapshot and diff. Mirrors [leyline.bridge.WebGamePlayback].
 *
 * Uses the shared [leyline.game.bundle.MessageCounter] for protocol sequencing. Both the session
 * thread and this (engine thread) call `counter.nextMsgId()`/`counter.nextGsId()`
 * on the same atomic — no seeding or syncing needed.
 *
 * Shares [leyline.game.bundle.BundleCursor] with the session-layer `BundleBuilder` via
 * [leyline.game.state.GameBridge.bundleCursor]: the two builders must agree on the diff baseline
 * or `buildDiff` produces a Full when the client expects a Diff. See
 * [leyline.game.bundle.BundleCursor] KDoc for the sharing invariant.
 *
 * The [MatchHandler][leyline.match.MatchHandler] drains the queue
 * via [drainQueue] and sends messages to the TCP socket.
 *
 * @param bridge the GameBridge for state mapping and zone tracking
 * @param matchId match identifier for GRE messages
 * @param seatId the human player's seat (messages are from their perspective)
 * @param counter shared protocol counter (same instance used by MatchSession)
 */
class GamePlayback(
    private val bridge: GameBridge,
    private val matchId: String,
    private val seatId: Int,
    private val counter: MessageCounter,
    /** Delay multiplier (1.0 = default, 0.5 = 2x speed, 0 = instant). Derived from config ai.speed. */
    private val delayMultiplier: Double = 1.0,
) : IGameEventVisitor.Base<Unit>() {
    private val bundleBuilder = BundleBuilder(bridge, matchId, seatId)

    private val log = LoggerFactory.getLogger(GamePlayback::class.java)

    /** Dedup: last turn+phase captured by TurnBegan, so TurnPhase can skip the duplicate. */
    private var lastCapturedTurn = 0
    private var lastCapturedPhase: PhaseType? = null

    /** Thread-safe queue of GRE message batches for the handler to drain. */
    private val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isRemoteActing()) return
        captureAndPause(LAND_DELAY)
    }

    /**
     * SpellAbility ids of trigger casts seen on the local turn that haven't yet
     * resolved. Used to recognise the matching `GameEventSpellResolved` so we
     * can split the trigger lifecycle into its own GSMs even when the player
     * is acting (the canonical Mobilize wire ships announcement, resolution
     * + tokens, and combat damage in three separate diffs at Combat/DeclareAttack
     * → Combat/CombatDamage). Independent of [GameEventCollector.pendingTriggers]
     * because the EventBus drains both subscribers in registration order and
     * the collector consumes its map on the resolve event.
     */
    private val pendingLocalTriggers = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    override fun visit(ev: GameEventSpellAbilityCast) {
        val isTrigger = ev.si()?.isTrigger == true
        val splitForLocalTrigger = isTrigger && shouldSplitOnLocalTurn(ev.sa()?.hostCard?.id, liveStackSpellAbility(ev))
        if (splitForLocalTrigger) {
            val saId = ev.sa()?.id ?: 0
            if (saId != 0) pendingLocalTriggers[saId] = true
        }
        // Verified local-turn triggers need their own diff so the client sees
        // the stack entry before the resolution diff. Other triggers keep the
        // legacy single-GSM-per-action bundling until their shape is verified.
        if (!isRemoteActing() && !splitForLocalTrigger) return
        captureAndPause(CAST_DELAY)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val saId = ev.spell()?.id ?: 0
        val splitForLocalTrigger = saId != 0 && pendingLocalTriggers.remove(saId) != null
        // Mirror the cast hook above: trigger resolutions on a local turn need
        // their own diff so trigger effects land before the next unrelated diff.
        if (!isRemoteActing() && !splitForLocalTrigger) return
        captureAndPause(RESOLVE_DELAY)
    }

    /** Decide whether to split this trigger's lifecycle into its own diff on
     *  the local turn. Today: known keyword trigger shapes plus mandatory
     *  non-interactive trigger chains such as Ajani's Pridemate.
     *
     *  Widening to other keyword triggers (other combat triggers, ETB
     *  mechanics with delayed-trigger tokens, etc.) inserts an extra Diff
     *  GSM per trigger fire. Any [MatchFlowHarness]-based integration test
     *  asserting a single-GSM-per-action wire shape will need to update its
     *  assertions before the keyword can be added to the predicate. Audit
     *  before extending the list. */
    private fun shouldSplitOnLocalTurn(
        hostCardForgeId: Int?,
        sa: SpellAbility?,
    ): Boolean {
        if (hostCardForgeId == null) return false
        if (sa == null) return false
        if (hasLocalTurnSplitKeyword(hostCardForgeId, sa)) return true
        return isNonInteractiveLocalTrigger(sa)
    }

    private fun liveStackSpellAbility(ev: GameEventSpellAbilityCast): SpellAbility? {
        val eventSa = ev.sa() ?: return null
        val eventHostCardId = eventSa.hostCard?.id ?: return null
        return bridge.getGame()?.stack?.peek()?.spellAbility?.takeIf { topSa ->
            topSa.id == eventSa.id && topSa.hostCard?.id == eventHostCardId
        } ?: findLiveSaOnCard(eventHostCardId, eventSa.id)
    }

    private fun findLiveSaOnCard(
        cardId: Int,
        evSaId: Int,
    ): SpellAbility? {
        if (evSaId == 0) return null
        val card = bridge.findCard(ForgeCardId(cardId)) ?: return null
        return card.spellAbilities.firstOrNull { it.id == evSaId }
            ?: card.allSpellAbilities?.firstOrNull { it.id == evSaId }
    }

    private fun hasLocalTurnSplitKeyword(
        hostCardForgeId: Int,
        sa: SpellAbility,
    ): Boolean {
        val card = bridge.findCard(ForgeCardId(hostCardForgeId)) ?: return false
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: return false
        return when {
            hasKeywordGrpId(grpId, KeywordAbilityIds.MOBILIZE) ->
                sa.api == ApiType.Token && sa.trigger?.getParam("Mode") == "Attacks"
            hasKeywordGrpId(grpId, KeywordAbilityIds.TRAINING) ->
                (sa.isKeyword(Keyword.TRAINING) || sa.hasParam("Training")) && sa.api == ApiType.PutCounter
            hasKeywordGrpId(grpId, KeywordAbilityIds.DECAYED) ->
                (sa.api == ApiType.DelayedTrigger && sa.trigger?.getParam("Mode") == "Attacks") ||
                    (sa.api == ApiType.Sacrifice && sa.trigger?.getParam("Phase") == "EndCombat")
            else -> false
        }
    }

    private fun hasKeywordGrpId(
        grpId: Int,
        keywordId: Int,
    ): Boolean = bridge.cardRepository.findKeywordAbilityGrpId(grpId, keywordId) != null

    private fun isNonInteractiveLocalTrigger(sa: SpellAbility): Boolean {
        if (sa.isOptionalTrigger) return false
        var ability: SpellAbility? = sa
        while (ability != null) {
            // Pure trigger lane: no target/select/cost prompt before resolution.
            if (ability.usesTargeting()) return false
            if (!hasNoPromptCost(ability)) return false
            if (ability.api !in localTurnSplitSafeApis) return false
            ability = ability.subAbility
        }
        return true
    }

    private fun hasNoPromptCost(ability: SpellAbility): Boolean =
        ability.payCosts.costParts.all { it is CostPartMana } && ability.payCosts.totalMana.isZero

    override fun visit(ev: GameEventTurnBegan) {
        if (!isRemoteActing()) return
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: TurnBegan during teardown (game null), dropping event")
                return
            }
        lastCapturedTurn = game.phaseHandler.turn
        lastCapturedPhase = game.phaseHandler.phase
        captureAndPause(PHASE_DELAY, turnStarted = true)
    }

    override fun visit(ev: GameEventTurnPhase) {
        if (!isRemoteActing()) return
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: TurnPhase during teardown (game null), dropping event")
                return
            }
        val turn = game.phaseHandler.turn
        val phase = game.phaseHandler.phase
        // Skip if TurnBegan already captured this exact turn+phase
        if (turn == lastCapturedTurn && phase == lastCapturedPhase) return
        lastCapturedTurn = turn
        lastCapturedPhase = phase
        val delay =
            when (ev.phase()) {
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.COMBAT_END,
                -> COMBAT_DELAY
                else -> PHASE_DELAY
            }
        captureAndPause(delay)
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        // Capture for BOTH local and remote attackers. The client expects a
        // combat-state diff (tapped creatures + attackState=Attacking) after
        // attackers are declared regardless of whose turn it is. Without this,
        // the human-seat auto-pass loop overshoots past combat before building
        // a diff, and the client never sees attackers tapped (leyline-o2q).
        if (isRemoteActing()) {
            captureAndPause(COMBAT_DELAY)
        } else {
            captureAndPause(0) // no pacing delay on own turn
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isRemoteActing()) return
        captureAndPause(COMBAT_DELAY)
    }

    override fun visit(ev: GameEventCombatEnded) {
        // Local turn needs a post-damage combat snapshot too. Without this,
        // damage/life/death annotations can sit in the collector queue until the
        // next later priority stop and get folded into a post-combat action GSM.
        if (isRemoteActing()) return
        captureAndPause(0)
    }

    // -- Queue access (called from MatchHandler / Netty thread) --

    /** Drain all queued message batches. Returns empty list if nothing queued. */
    fun drainQueue(): List<List<GREToClientMessage>> =
        buildList {
            while (true) {
                add(queue.poll() ?: break)
            }
        }

    /** True if there are messages waiting to be sent. */
    fun hasPendingMessages(): Boolean = queue.isNotEmpty()

    // -- Internal --

    /**
     * Snapshot current game state as a diff, queue the GRE messages,
     * update the bridge snapshot, then sleep for animation pacing.
     *
     * Called on the engine thread -- state is frozen, safe to serialize.
     * Uses the shared [counter] — no seeding needed.
     */
    private fun captureAndPause(
        delayMs: Int,
        turnStarted: Boolean = false,
        gameOverride: forge.game.Game? = null,
        eventsOverride: FrameEventLog? = null,
    ) {
        val game =
            gameOverride ?: bridge.getGame() ?: run {
                log.debug("GamePlayback: captureAndPause during teardown (game null), skipping")
                return
            }

        try {
            val events = eventsOverride ?: bridge.closeBundleFrame(seatId)
            val messageCount =
                if (eventsOverride == null && events.events.hasCombatDamage()) {
                    captureSplitCombatDamage(game, events.events)
                    2
                } else {
                    val messages = buildDiffMessages(game, turnStarted, events)
                    queue.add(messages)
                    messages.size
                }

            // No need to advance the cursor here — buildDiff (called by remoteActionDiff)
            // writes cursor.lastSent after computing the diff. A redundant
            // buildFromSnapshot with the same gsId creates a self-referential snapshot.

            log.debug(
                "action captured: phase={} turn={} queued={} msgs={}",
                game.phaseHandler.phase,
                game.phaseHandler.turn,
                queue.size,
                messageCount,
            )
        } catch (ex: Exception) {
            log.warn("Failed to capture AI action state: {}", ex.message, ex)
        }

        // Pacing: sleep engine thread so client can animate
        val adjustedDelay = (delayMs * delayMultiplier).toLong()
        if (adjustedDelay > 0) {
            try {
                Thread.sleep(adjustedDelay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun captureSplitCombatDamage(
        game: forge.game.Game,
        events: List<LeylineGameEvent>,
    ) {
        val damageEvents =
            events
                .filterNot { event ->
                    event is LeylineGameEvent.PhaseChanged
                }.prependCombatDamagePhase(game, events)
        val endCombatEvents =
            events.filter { event ->
                event is LeylineGameEvent.PhaseChanged && event.step == Step.EndCombat_a2cb.number
            }

        queue.add(buildDiffMessages(game, turnStarted = false, events = FrameEventLog(damageEvents)))
        if (endCombatEvents.isNotEmpty()) {
            queue.add(buildDiffMessages(game, turnStarted = false, events = FrameEventLog(endCombatEvents)))
        }
    }

    private fun buildDiffMessages(
        game: forge.game.Game,
        turnStarted: Boolean,
        events: FrameEventLog,
    ): List<GREToClientMessage> =
        bundleBuilder
            .remoteActionDiff(
                game,
                counter,
                turnStarted = turnStarted,
                eventsOverride = events,
            ).messages

    private fun List<LeylineGameEvent>.hasCombatDamage(): Boolean =
        any { it is LeylineGameEvent.DamageDealtToCard || it is LeylineGameEvent.DamageDealtToPlayer }

    private fun List<LeylineGameEvent>.prependCombatDamagePhase(
        game: forge.game.Game,
        sourceEvents: List<LeylineGameEvent>,
    ): List<LeylineGameEvent> {
        val activeSeat = combatDamageSourceSeat(sourceEvents) ?: currentTurnSeat(game) ?: seatId
        return listOf(
            LeylineGameEvent.PhaseChanged(
                SeatId(activeSeat),
                Phase.Combat_a549.number,
                Step.CombatDamage_a2cb.number,
            ),
        ) + this
    }

    private fun combatDamageSourceSeat(events: List<LeylineGameEvent>): Int? {
        events
            .firstNotNullOfOrNull { event ->
                (event as? LeylineGameEvent.DamageDealtToPlayer)?.targetSeatId?.value
            }?.let { defenderSeat ->
                val otherSeats = bridge.allSeatIds() - defenderSeat
                if (otherSeats.size == 1) return otherSeats.single()
                return if (defenderSeat == 1) 2 else 1
            }
        val sourceId =
            events.firstNotNullOfOrNull { event ->
                when (event) {
                    is LeylineGameEvent.DamageDealtToCard -> event.sourceCardId
                    is LeylineGameEvent.DamageDealtToPlayer -> event.sourceCardId
                    else -> null
                }
            } ?: return null
        val controller = bridge.findCard(sourceId)?.controller ?: return null
        return bridge.allSeatIds().firstOrNull { seat -> bridge.getPlayer(SeatId(seat)) == controller }
    }

    private fun currentTurnSeat(game: forge.game.Game): Int? {
        val turnPlayer = game.phaseHandler.playerTurn ?: return null
        return bridge.allSeatIds().firstOrNull { seat -> bridge.getPlayer(SeatId(seat)) == turnPlayer }
    }

    /**
     * True when the current turn's active player is not this playback's seat.
     * Fires for AI turns and remote-seat turns uniformly.
     */
    private fun isRemoteActing(): Boolean {
        // No log here — called from every event listener; logging would
        // duplicate the teardown messages emitted by the listeners themselves.
        val game = bridge.getGame() ?: return false
        val turnPlayer = game.phaseHandler.playerTurn ?: return false
        val myPlayer = bridge.getPlayer(SeatId(seatId)) ?: return false
        return turnPlayer != myPlayer
    }

    companion object {
        const val PHASE_DELAY = 200 // ms
        const val COMBAT_DELAY = 400
        const val CAST_DELAY = 400
        const val RESOLVE_DELAY = 400
        const val LAND_DELAY = 300

        // APIs that can resolve without prompts in the local trigger split path.
        private val localTurnSplitSafeApis =
            setOf(
                ApiType.Draw,
                ApiType.GainLife,
                ApiType.Investigate,
                ApiType.LoseLife,
                ApiType.Pump,
                ApiType.PutCounter,
                ApiType.Token,
            )
    }
}
