package leyline.game

import com.google.common.eventbus.Subscribe
import forge.ai.LobbyPlayerAi
import forge.game.event.*
import forge.game.zone.ZoneType
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Subscribes to the Forge engine's Guava EventBus and converts rich Java
 * [GameEvent] objects into protocol-oriented [GameEvent] instances.
 *
 * Events accumulate in a thread-safe queue. The annotation builder drains
 * them via [drainEvents] when building a state diff — this replaces the
 * zone-pair heuristic in [StateMapper.inferCategory].
 *
 * Threading: events fire synchronously on the engine thread. Queue access
 * is via [ConcurrentLinkedQueue] so the Netty/handler thread can drain safely.
 *
 * @param bridge used only to resolve Player → seatId (never mutated)
 */
class GameEventCollector(private val bridge: GameBridge) : IGameEventVisitor.Base<Unit>() {

    private val log = LoggerFactory.getLogger(GameEventCollector::class.java)

    private val queue = ConcurrentLinkedQueue<GameEvent>()

    /** Last-seen P/T per card ID — used to detect deltas on GameEventCardStatsChanged. */
    private val lastPT = ConcurrentHashMap<Int, Pair<Int, Int>>()

    /** Drain all queued events since last drain. Returns events in firing order. */
    fun drainEvents(): List<GameEvent> = buildList {
        while (true) {
            add(queue.poll() ?: break)
        }
    }

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
        queue.add(GameEvent.LandPlayed(ev.land().id, seat))
        log.debug("event: LandPlayed card={} seat={}", ev.land().name, seat)
    }

    override fun visit(ev: GameEventSpellAbilityCast) {
        val card = ev.sa().hostCard ?: return
        val seat = seatOf(card.controller) ?: return
        queue.add(GameEvent.SpellCast(card.id, seat))
        log.debug("event: SpellCast card={} seat={}", card.name, seat)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val card = ev.spell().hostCard ?: return
        queue.add(GameEvent.SpellResolved(card.id, ev.hasFizzled()))
        log.debug("event: SpellResolved card={} fizzled={}", card.name, ev.hasFizzled())
    }

    override fun visit(ev: GameEventCardChangeZone) {
        val card = ev.card()
        val from = ev.from().zoneType
        val to = ev.to().zoneType
        val seat = seatOf(card.controller)

        // Emit the most specific variant possible based on zone pair.
        // When seat is unavailable, fall back to generic ZoneChanged.
        val event = if (seat != null) {
            when {
                from == ZoneType.Battlefield && to == ZoneType.Graveyard ->
                    GameEvent.CardDestroyed(card.id, seat)
                from == ZoneType.Battlefield && (to == ZoneType.Hand || to == ZoneType.Library) ->
                    GameEvent.CardBounced(card.id, seat)
                to == ZoneType.Exile ->
                    GameEvent.CardExiled(card.id, seat)
                from == ZoneType.Hand && to == ZoneType.Graveyard ->
                    GameEvent.CardDiscarded(card.id, seat)
                from == ZoneType.Library && to == ZoneType.Graveyard ->
                    GameEvent.CardMilled(card.id, seat)
                else -> GameEvent.ZoneChanged(card.id, from, to)
            }
        } else {
            GameEvent.ZoneChanged(card.id, from, to)
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

    // -- Group A+: attachment events --

    override fun visit(ev: GameEventCardAttachment) {
        val card = ev.equipment()
        val seat = seatOf(card.controller) ?: return
        val newTarget = ev.newTarget()
        if (newTarget != null && newTarget is forge.game.card.Card) {
            queue.add(GameEvent.CardAttached(card.id, newTarget.id, seat))
            log.debug("event: CardAttached card={} target={} seat={}", card.name, newTarget, seat)
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
            val newPower = card.getNetPower()
            val newTough = card.getNetToughness()
            val prev = lastPT.put(id, newPower to newTough)
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

    override fun visit(ev: GameEventTokenCreated) {
        for (card in ev.tokens()) {
            val seat = seatOf(card.controller) ?: continue
            val sourceId = card.tokenSpawningAbility?.hostCard?.id
            queue.add(GameEvent.TokenCreated(card.id, seat, sourceId))
            log.debug("event: TokenCreated card={} seat={} source={}", card.name, seat, sourceId)
        }
    }

    // -- Group C: combat enrichment --

    override fun visit(ev: GameEventCombatEnded) {
        queue.add(GameEvent.CombatEnded)
        log.debug("event: CombatEnded")
    }

    // -- helpers --

    private fun seatOf(player: forge.game.player.Player?): Int? {
        if (player == null) return null
        return if (player.lobbyPlayer is LobbyPlayerAi) 2 else 1
    }
}
