package leyline.game

import com.google.common.eventbus.Subscribe
import forge.ai.LobbyPlayerAi
import forge.game.event.*
import forge.game.event.GameEventManaAbilityActivated
import forge.game.event.GameEventSpellMovedToStack
import forge.game.zone.ZoneType
import leyline.game.mapper.PlayerMapper
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Subscribes to the Forge engine's Guava EventBus and converts rich Java
 * [GameEvent][forge.game.event.GameEvent] objects into protocol-oriented
 * [GameEvent] sealed variants.
 *
 * ## Drain contract
 *
 * Events accumulate in a thread-safe [ConcurrentLinkedQueue]. [drainEvents]
 * atomically empties the queue and returns events in firing order, wrapped in
 * [DrainedEvents] to make single-use visible in the type system.
 *
 * **Drain exactly once per GSM build.** [StateMapper.buildFromGame] drains in
 * the GATHER phase. Double-draining silently loses events — the second drain
 * returns an empty list with no error. The [DrainedEvents] wrapper prevents
 * accidental re-drain of the same result but cannot prevent calling
 * [drainEvents] twice on the collector itself.
 *
 * ## Event ordering
 *
 * Events fire in Forge engine execution order, which may differ from the
 * annotation ordering the client expects. The downstream annotation pipeline
 * ([AnnotationBuilder.categoryFromEvents]) re-prioritizes: specific events
 * (LandPlayed, CardSacrificed) take precedence over generic ZoneChanged when
 * both fire for the same card in the same GSM.
 *
 * ## Cross-class flag consumption
 *
 * Two helper methods consume single-use flags set by
 * [WebPlayerController][leyline.bridge.WebPlayerController] on the
 * [InteractivePromptBridge][leyline.bridge.InteractivePromptBridge]:
 *
 * - [isSearchedToHand]: consumes `searchedToHandCards` flag → emits
 *   [GameEvent.CardSearchedToHand] instead of generic ZoneChanged for
 *   Library→Hand tutors. Set by WPC's `chooseSingleEntityForEffect`.
 *
 * - [isLegendRuleVictim]: consumes `legendRuleVictims` flag → emits
 *   [GameEvent.LegendRuleDeath] instead of generic ZoneChanged for
 *   BF→GY legend rule deaths. Set by WPC's `chooseSingleEntityForEffect`.
 *
 * Both flags are written and consumed on the engine thread (events fire
 * synchronously during the engine operation that triggered the zone change).
 * Consumption removes the flag so it doesn't leak to subsequent zone events.
 *
 * ## Threading
 *
 * Events fire synchronously on the engine thread. Queue access is via
 * [ConcurrentLinkedQueue] so the Netty/handler thread can drain safely,
 * though in practice [drainEvents] is always called from the engine thread
 * via [StateMapper].
 *
 * **Adding new mechanics:** When upstream Forge events lack the granularity we need
 * (per-card IDs, zone-pair specificity), add a new event to our fork rather than
 * retroactively correlating events here. See [GameEventCardSurveiled] for the pattern:
 * fire per-card from `Player.surveil()`, handle with a simple visit override.
 *
 * @param bridge used only to resolve Player → seatId and access prompt bridge flags
 *   (never mutated beyond flag consumption)
 */
/** Wrapper making event drain's single-use nature visible in the type system. */
@JvmInline
value class DrainedEvents(val events: List<GameEvent>)

class GameEventCollector(private val bridge: GameBridge) : IGameEventVisitor.Base<Unit>() {

    private val log = LoggerFactory.getLogger(GameEventCollector::class.java)

    private val queue = ConcurrentLinkedQueue<GameEvent>()

    /** Last-seen P/T per card ID — used to detect deltas on GameEventCardStatsChanged. */
    private val lastPT = ConcurrentHashMap<Int, Pair<Int, Int>>()

    /**
     * Drain all queued events since last drain. Returns events in engine firing order.
     *
     * **Call exactly once per GSM build** — second call returns empty (events are gone).
     * [StateMapper.buildFromGame] calls this in the GATHER phase before any annotation
     * pipeline stages run.
     */
    fun drainEvents(): DrainedEvents = DrainedEvents(
        buildList {
            while (true) {
                add(queue.poll() ?: break)
            }
        },
    )

    /** Peek at queued events without draining (for tests). */
    fun peekEvents(): List<GameEvent> = queue.toList()

    /** True if there are events waiting. */
    fun hasEvents(): Boolean = queue.isNotEmpty()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        val seat = seatOf(ev.player()) ?: return
        val colorBitmasks = bridge.getGame()
            ?.findById(ev.land().id)
            ?.let(::computeColorBitmasks)
            ?: emptyList()
        queue.add(GameEvent.LandPlayed(ev.land().id, seat, colorBitmasks))
        log.debug("event: LandPlayed card={} seat={} colors={}", ev.land().name, seat, colorBitmasks)
    }

    override fun visit(ev: GameEventSpellAbilityCast) {
        val card = ev.sa().hostCard ?: return
        val seat = seatOf(card.controller) ?: return
        val payments = ev.manaPayments().map { mp ->
            GameEvent.ManaPayment(
                sourceForgeCardId = mp.sourceCardId(),
                color = mp.color().toInt() and 0xFF,
            )
        }
        queue.add(GameEvent.SpellCast(card.id, seat, payments))
        log.debug("event: SpellCast card={} seat={} manaPayments={}", card.name, seat, payments.size)
    }

    override fun visit(ev: GameEventSpellMovedToStack) {
        val card = ev.card()
        val seat = seatOf(card.controller) ?: return
        queue.add(GameEvent.SpellMovedToStack(card.id, seat))
        log.debug("event: SpellMovedToStack card={} seat={}", card.name, seat)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val card = ev.spell().hostCard ?: return
        queue.add(GameEvent.SpellResolved(card.id, ev.hasFizzled()))
        log.debug("event: SpellResolved card={} fizzled={}", card.name, ev.hasFizzled())
    }

    @Suppress("CyclomaticComplexMethod") // zone-change routing inherently branchy
    override fun visit(ev: GameEventCardChangeZone) {
        val card = ev.card()
        val from = ev.from()?.zoneType
        val to = ev.to()?.zoneType ?: return
        val seat = seatOf(card.controller)

        // Emit the most specific variant possible based on zone pair.
        // When seat is unavailable or source zone is null (e.g. token entering
        // Command zone from nowhere), fall back to generic ZoneChanged.
        val event = if (seat != null && from != null) {
            when {
                from == ZoneType.Battlefield && to == ZoneType.Graveyard && isLegendRuleVictim(card.id) ->
                    GameEvent.LegendRuleDeath(card.id, seat)
                // BF→GY without legend rule: fall through to ZoneChanged.
                // CardDestroyed is emitted from GameEventCardDestroyed (with activator).
                from == ZoneType.Battlefield && (to == ZoneType.Hand || to == ZoneType.Library) ->
                    GameEvent.CardBounced(card.id, seat)
                to == ZoneType.Exile -> {
                    val sourceId = card.exiledWith?.id
                    GameEvent.CardExiled(card.id, seat, sourceId)
                }
                from == ZoneType.Hand && to == ZoneType.Graveyard ->
                    GameEvent.CardDiscarded(card.id, seat)
                from == ZoneType.Library && to == ZoneType.Graveyard ->
                    GameEvent.CardMilled(card.id, seat)
                from == ZoneType.Library && to == ZoneType.Hand && isSearchedToHand(card.id) ->
                    GameEvent.CardSearchedToHand(card.id)
                else -> GameEvent.ZoneChanged(card.id, Zone.fromForge(from), Zone.fromForge(to))
            }
        } else {
            GameEvent.ZoneChanged(card.id, from?.let { Zone.fromForge(it) } ?: Zone.Other, Zone.fromForge(to))
        }

        // Clear cached P/T when a card leaves the battlefield so re-entering
        // cards diff against fresh values instead of stale prior-lifetime stats.
        if (from == ZoneType.Battlefield) {
            lastPT.remove(card.id)
        }

        queue.add(event)
        log.debug("event: {} card={} {} → {}", event::class.simpleName, card.name, from, to)

        // Emit TokenDestroyed when a token leaves the battlefield
        if (card.isToken && from == ZoneType.Battlefield && seat != null) {
            queue.add(GameEvent.TokenDestroyed(card.id, seat))
            log.debug("event: TokenDestroyed card={} seat={}", card.name, seat)
        }
    }

    override fun visit(ev: GameEventCardTapped) {
        queue.add(GameEvent.CardTapped(ev.card().id, ev.tapped()))
        log.debug("event: CardTapped card={} tapped={}", ev.card().name, ev.tapped())
    }

    override fun visit(ev: GameEventManaAbilityActivated) {
        val card = ev.source()
        val seat = seatOf(card.controller) ?: return
        queue.add(GameEvent.ManaAbilityActivated(card.id, seat, ev.produced()))
        log.debug("event: ManaAbilityActivated card={} seat={} produced={}", card.name, seat, ev.produced())
    }

    override fun visit(ev: GameEventCardDamaged) {
        queue.add(
            GameEvent.DamageDealtToCard(
                sourceForgeId = ev.source().id,
                targetForgeId = ev.card().id,
                amount = ev.amount(),
            ),
        )
    }

    override fun visit(ev: GameEventPlayerDamaged) {
        val seat = seatOf(ev.target()) ?: return
        val source = ev.source() ?: return
        queue.add(
            GameEvent.DamageDealtToPlayer(
                sourceForgeId = source.id,
                targetSeatId = seat,
                amount = ev.amount(),
                combat = ev.combat(),
            ),
        )
    }

    override fun visit(ev: GameEventPlayerLivesChanged) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(
            GameEvent.LifeChanged(
                seatId = seat,
                oldLife = ev.oldLives(),
                newLife = ev.newLives(),
            ),
        )
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        val seat = seatOf(ev.player()) ?: return
        val ids = ev.attackersMap().values().map { it.id }
        if (ids.isNotEmpty()) {
            queue.add(GameEvent.AttackersDeclared(ids, seat))
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        val seat = seatOf(ev.defendingPlayer()) ?: return
        // Flatten all blocking creatures from the nested map
        val ids = ev.blockers().values.flatMap { multimap ->
            multimap.keys().map { it.id }
        }
        if (ids.isNotEmpty()) {
            queue.add(GameEvent.BlockersDeclared(ids, seat))
        }
    }

    // -- Group A: zone-transition disambiguation --

    override fun visit(ev: GameEventCardSacrificed) {
        val card = ev.card()
        val seat = seatOf(card.controller) ?: return
        queue.add(GameEvent.CardSacrificed(card.id, seat))
        log.debug("event: CardSacrificed card={} seat={}", card.name, seat)
    }

    override fun visit(ev: GameEventCardDestroyed) {
        val card = ev.card() ?: return
        val seat = seatOf(card.controller) ?: return
        val sourceId = ev.activator()?.id
        queue.add(GameEvent.CardDestroyed(card.id, seat, sourceId))
        log.debug("event: CardDestroyed card={} seat={} source={}", card.name, seat, sourceId)
    }

    // -- Group A+: attachment events --

    override fun visit(ev: GameEventCardAttachment) {
        val card = ev.equipment()
        val seat = seatOf(card.controller) ?: return
        val newTarget = ev.newTarget()
        if (newTarget != null) {
            queue.add(GameEvent.CardAttached(card.id, newTarget.id, seat))
            log.debug("event: CardAttached card={} target={} seat={}", card.name, newTarget.name, seat)
        } else {
            queue.add(GameEvent.CardDetached(card.id, seat))
            log.debug("event: CardDetached card={} seat={}", card.name, seat)
        }
    }

    // -- Group B: annotation-producing events --

    override fun visit(ev: GameEventCardCounters) {
        queue.add(
            GameEvent.CountersChanged(
                forgeCardId = ev.card().id,
                counterType = ev.type().name,
                oldCount = ev.oldValue(),
                newCount = ev.newValue(),
            ),
        )
        log.debug("event: CountersChanged card={} {} {}→{}", ev.card().name, ev.type(), ev.oldValue(), ev.newValue())
    }

    override fun visit(ev: GameEventCardStatsChanged) {
        for (card in ev.cards()) {
            val id = card.id
            val newPower = card.currentState.power
            val newTough = card.currentState.toughness
            val prev = lastPT.put(id, Pair(newPower, newTough))
            val oldPower = prev?.first ?: newPower
            val oldTough = prev?.second ?: newTough
            if (oldPower != newPower || oldTough != newTough) {
                queue.add(
                    GameEvent.PowerToughnessChanged(
                        forgeCardId = id,
                        oldPower = oldPower,
                        newPower = newPower,
                        oldToughness = oldTough,
                        newToughness = newTough,
                    ),
                )
                log.debug("event: P/T changed card={} {}/{}→{}/{}", card.name, oldPower, oldTough, newPower, newTough)
            }
        }
    }

    override fun visit(ev: GameEventShuffle) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(GameEvent.LibraryShuffled(seat))
        log.debug("event: LibraryShuffled seat={}", seat)
    }

    override fun visit(ev: GameEventScry) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(GameEvent.Scry(seat, ev.toTop(), ev.toBottom()))
        log.debug("event: Scry seat={} top={} bottom={}", seat, ev.toTop(), ev.toBottom())
    }

    override fun visit(ev: GameEventSurveil) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(GameEvent.Surveil(seat, ev.toLibrary(), ev.toGraveyard()))
        log.debug("event: Surveil seat={} lib={} gy={}", seat, ev.toLibrary(), ev.toGraveyard())
    }

    // Per-card surveil event — fired from Player.surveil() in our Forge fork
    // for each card moved to graveyard. Allows categoryFromEvents to distinguish
    // surveil (Library→GY) from mill (Library→GY).
    override fun visit(ev: GameEventCardSurveiled) {
        val seat = seatOf(ev.card().controller) ?: return
        val sourceId = ev.causeCard()?.id
        queue.add(GameEvent.CardSurveiled(ev.card().id, seat, sourceId))
        log.debug("event: CardSurveiled card={} seat={} source={}", ev.card().name, seat, sourceId)
    }

    override fun visit(ev: GameEventTokenCreated) {
        for (card in ev.tokens()) {
            val seat = seatOf(card.controller) ?: continue
            val sourceId = card.tokenSpawningAbility?.hostCard?.id
            queue.add(GameEvent.TokenCreated(card.id, seat, sourceId))
            log.debug("event: TokenCreated card={} seat={} source={}", card.name, seat, sourceId)
        }
    }

    override fun visit(ev: GameEventControllerChanged) {
        val card = ev.card()
        val oldSeat = seatOf(ev.oldController()) ?: return
        val newSeat = seatOf(ev.newController()) ?: return
        queue.add(GameEvent.ControllerChanged(card.id, oldSeat, newSeat))
        log.debug("event: ControllerChanged card={} {} -> {}", card.name, oldSeat, newSeat)
    }

    // -- Group C: combat enrichment --

    override fun visit(ev: GameEventCombatEnded) {
        queue.add(GameEvent.CombatEnded)
        log.debug("event: CombatEnded")
    }

    // -- Group D: phase/turn events --

    override fun visit(ev: GameEventTurnPhase) {
        val seat = seatOf(ev.playerTurn()) ?: return
        val phase = PlayerMapper.mapPhase(ev.phase()).number
        val step = PlayerMapper.mapStep(ev.phase()).number
        queue.add(GameEvent.PhaseChanged(seat, phase, step))
        log.debug("event: PhaseChanged seat={} phase={} step={}", seat, phase, step)
    }

    // -- helpers --

    private fun seatOf(player: forge.game.player.Player?): Int? {
        if (player == null) return null
        return if (player.lobbyPlayer is LobbyPlayerAi) 2 else 1
    }

    private fun seatOf(player: forge.game.player.PlayerView?): Int? {
        if (player == null) return null
        return if (player.isAI) 2 else 1
    }

    /**
     * Check if a card was chosen via a search effect (ChangeZone tutor) and is moving
     * Library→Hand. Consumes the flag so it doesn't fire again for subsequent zone events.
     */
    private fun isSearchedToHand(forgeCardId: Int): Boolean {
        for (seat in bridge.allSeatIds()) {
            val searched = bridge.promptBridge(seat).searchedToHandCards
            if (searched.remove(forgeCardId)) return true
        }
        return false
    }

    /**
     * Check if a card is marked as a legend rule SBA victim.
     *
     * [WebPlayerController.autoResolveLegendRule] populates
     * [InteractivePromptBridge.legendRuleVictims] with forge card IDs of
     * legendaries that will die. We check all seats' prompt bridges.
     * Consuming on match (remove) so the flag doesn't leak to future SBAs.
     */
    private fun isLegendRuleVictim(forgeCardId: Int): Boolean {
        for (seat in bridge.allSeatIds()) {
            val victims = bridge.promptBridge(seat).legendRuleVictims
            if (victims.remove(forgeCardId)) return true
        }
        return false
    }

    /**
     * Compute color bitmask(s) from a land's mana abilities.
     * Each mana ability produces one bitmask entry (OR of its color chars).
     * Basic lands → single entry (e.g. [2] for Island).
     * Dual/multi-lands → multiple entries (e.g. [4, 5] for Orzhov Gate).
     * Uses Forge's MagicColor bitmask which matches Arena's (1=W, 2=U, 4=B, 8=R, 16=G).
     */
    private fun computeColorBitmasks(card: forge.game.card.Card): List<Int> =
        card.getManaAbilities()
            .mapNotNull { sa ->
                val produced = sa.manaPart?.origProduced ?: return@mapNotNull null
                var bitmask = 0
                for (ch in produced) {
                    bitmask = bitmask or forge.card.MagicColor.fromName(ch).toInt()
                }
                if (bitmask != 0) bitmask else null
            }
}
