package forge.nexus.game

import com.google.common.eventbus.Subscribe
import forge.ai.LobbyPlayerAi
import forge.game.event.*
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
        queue.add(NexusGameEvent.ZoneChanged(card.id, ev.from().zoneType, ev.to().zoneType))
        log.debug("event: ZoneChanged card={} {} → {}", card.name, ev.from().zoneType, ev.to().zoneType)
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

    // -- helpers --

    private fun seatOf(player: forge.game.player.Player?): Int? {
        if (player == null) return null
        return if (player.lobbyPlayer is LobbyPlayerAi) 2 else 1
    }
}
