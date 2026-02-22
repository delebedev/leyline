package forge.nexus.game

import com.google.common.eventbus.Subscribe
import forge.ai.LobbyPlayerAi
import forge.game.event.*
import forge.game.zone.ZoneType
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Subscribes to the Forge engine's Guava EventBus and converts rich Java
 * [GameEvent] objects into protocol-oriented [NexusGameEvent] instances.
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

    private val queue = ConcurrentLinkedQueue<NexusGameEvent>()

    /** Drain all queued events since last drain. Returns events in firing order. */
    fun drainEvents(): List<NexusGameEvent> {
        val result = mutableListOf<NexusGameEvent>()
        while (true) {
            val ev = queue.poll() ?: break
            result.add(ev)
        }
        return result
    }

    /** Peek at queued events without draining (for tests). */
    fun peekEvents(): List<NexusGameEvent> = queue.toList()

    /** True if there are events waiting. */
    fun hasEvents(): Boolean = queue.isNotEmpty()

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(NexusGameEvent.LandPlayed(ev.land().id, seat))
        log.debug("event: LandPlayed card={} seat={}", ev.land().name, seat)
    }

    override fun visit(ev: GameEventSpellAbilityCast) {
        val card = ev.sa().hostCard ?: return
        val seat = seatOf(card.controller) ?: return
        queue.add(NexusGameEvent.SpellCast(card.id, seat))
        log.debug("event: SpellCast card={} seat={}", card.name, seat)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val card = ev.spell().hostCard ?: return
        queue.add(NexusGameEvent.SpellResolved(card.id, ev.hasFizzled()))
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
                    NexusGameEvent.CardDestroyed(card.id, seat)
                from == ZoneType.Battlefield && (to == ZoneType.Hand || to == ZoneType.Library) ->
                    NexusGameEvent.CardBounced(card.id, seat)
                to == ZoneType.Exile ->
                    NexusGameEvent.CardExiled(card.id, seat)
                from == ZoneType.Hand && to == ZoneType.Graveyard ->
                    NexusGameEvent.CardDiscarded(card.id, seat)
                from == ZoneType.Library && to == ZoneType.Graveyard ->
                    NexusGameEvent.CardMilled(card.id, seat)
                else -> NexusGameEvent.ZoneChanged(card.id, from, to)
            }
        } else {
            NexusGameEvent.ZoneChanged(card.id, from, to)
        }

        queue.add(event)
        log.debug("event: {} card={} {} → {}", event::class.simpleName, card.name, from, to)
    }

    override fun visit(ev: GameEventCardTapped) {
        queue.add(NexusGameEvent.CardTapped(ev.card().id, ev.tapped()))
        log.debug("event: CardTapped card={} tapped={}", ev.card().name, ev.tapped())
    }

    override fun visit(ev: GameEventCardDamaged) {
        queue.add(
            NexusGameEvent.DamageDealtToCard(
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
            NexusGameEvent.DamageDealtToPlayer(
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
            NexusGameEvent.LifeChanged(
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
            queue.add(NexusGameEvent.AttackersDeclared(ids, seat))
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        val seat = seatOf(ev.defendingPlayer()) ?: return
        // Flatten all blocking creatures from the nested map
        val ids = ev.blockers().values.flatMap { multimap ->
            multimap.keys().map { it.id }
        }
        if (ids.isNotEmpty()) {
            queue.add(NexusGameEvent.BlockersDeclared(ids, seat))
        }
    }

    // -- Group A: zone-transition disambiguation --

    override fun visit(ev: GameEventCardSacrificed) {
        val card = ev.card()
        val seat = seatOf(card.controller) ?: return
        queue.add(NexusGameEvent.CardSacrificed(card.id, seat))
        log.debug("event: CardSacrificed card={} seat={}", card.name, seat)
    }

    // -- Group B: annotation-producing events --

    override fun visit(ev: GameEventCardCounters) {
        queue.add(
            NexusGameEvent.CountersChanged(
                forgeCardId = ev.card().id,
                counterType = ev.type().name,
                oldCount = ev.oldValue(),
                newCount = ev.newValue(),
            ),
        )
        log.debug("event: CountersChanged card={} {} {}→{}", ev.card().name, ev.type(), ev.oldValue(), ev.newValue())
    }

    override fun visit(ev: GameEventShuffle) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(NexusGameEvent.LibraryShuffled(seat))
        log.debug("event: LibraryShuffled seat={}", seat)
    }

    override fun visit(ev: GameEventScry) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(NexusGameEvent.Scry(seat, ev.toTop(), ev.toBottom()))
        log.debug("event: Scry seat={} top={} bottom={}", seat, ev.toTop(), ev.toBottom())
    }

    override fun visit(ev: GameEventSurveil) {
        val seat = seatOf(ev.player()) ?: return
        queue.add(NexusGameEvent.Surveil(seat, ev.toLibrary(), ev.toGraveyard()))
        log.debug("event: Surveil seat={} lib={} gy={}", seat, ev.toLibrary(), ev.toGraveyard())
    }

    // -- Group C: combat enrichment --

    override fun visit(ev: GameEventCombatEnded) {
        queue.add(NexusGameEvent.CombatEnded)
        log.debug("event: CombatEnded")
    }

    // -- helpers --

    private fun seatOf(player: forge.game.player.Player?): Int? {
        if (player == null) return null
        return if (player.lobbyPlayer is LobbyPlayerAi) 2 else 1
    }
}
