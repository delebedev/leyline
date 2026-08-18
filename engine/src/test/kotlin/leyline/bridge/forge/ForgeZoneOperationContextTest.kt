package leyline.bridge.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.cost.Cost
import forge.game.cost.CostPayment
import forge.game.cost.CostSacrifice
import forge.game.event.GameEventCardChangeZone
import forge.game.event.GameEventCardDestroyed
import forge.game.event.GameEventCardSacrificed
import forge.game.event.GameEventSpellAbilityCast
import forge.game.event.GameEventSpellResolved
import forge.game.spellability.SpellAbility
import forge.game.zone.CostPaymentStack
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import forge.game.event.GameEvent as ForgeGameEvent

/** Executable contract for the Forge zone-operation facts consumed by Leyline. */
class ForgeZoneOperationContextTest :
    BoardTest({
        test("caused zone change freezes serializable source and ability identity") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human)
                    addCard("Grizzly Bears", human)
                }
            val probe = game.subscribeProbe()
            val source =
                game.humanPlayer.battlefield.card("Goblin Fireslinger")
            val moved =
                game.humanPlayer.battlefield.card("Grizzly Bears")
            val cause = source.spellAbilities.first().also { it.activatingPlayer = game.humanPlayer }

            game.action.moveToGraveyard(moved, cause)

            val event = probe.zoneChanges().single()
            val causeView = event.cause()!!
            assertSoftly {
                event.card().id shouldBe moved.id
                event.from().zoneType() shouldBe ZoneType.Battlefield
                event.to().zoneType() shouldBe ZoneType.Graveyard
                GameEventCardChangeZone::class.java.recordComponents.map { it.name } shouldContainExactly
                    listOf("card", "from", "to", "cause")
                causeView.sourceCardId() shouldBe source.id
                causeView.abilityId() shouldBe cause.id
                causeView.rootAbilityId() shouldBe cause.rootAbility.id
                causeView.api() shouldBe cause.api
                causeView.costPayment() shouldBe false
                roundTrip(event).cause() shouldBe causeView
            }
        }

        test("causeless zone change preserves explicit unknown context") {
            val (_, game, _) =
                startWithBoard { _, human, _ -> addCard("Grizzly Bears", human) }
            val probe = game.subscribeProbe()
            val moved =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()

            game.action.moveToGraveyard(moved, null)

            probe.zoneChanges().single().cause() shouldBe null
        }

        test("destroy and sacrifice facts precede their zone moves") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human)
                    addCard("Grizzly Bears", human)
                    addCard("Runeclaw Bear", human)
                }
            val probe = game.subscribeProbe()
            val source =
                game.humanPlayer.battlefield.card("Goblin Fireslinger")
            val cause = source.spellAbilities.first().also { it.activatingPlayer = game.humanPlayer }
            val destroyed =
                game.humanPlayer.battlefield.card("Grizzly Bears")

            game.action.destroy(destroyed, cause, false, AbilityKey.newMap())
            probe.operationEventsFor(destroyed) shouldContainExactly
                listOf(GameEventCardDestroyed::class.java, GameEventCardChangeZone::class.java)
            assertSoftly {
                probe
                    .destroyed()
                    .single()
                    .card()
                    .id shouldBe destroyed.id
                probe
                    .destroyed()
                    .single()
                    .cause()
                    ?.sourceCardId() shouldBe source.id
                probe
                    .destroyed()
                    .single()
                    .cause()
                    ?.abilityId() shouldBe cause.id
            }

            probe.clear()
            val sacrificed =
                game.humanPlayer.battlefield.card("Runeclaw Bear")
            game.copyLastState()
            game.action.sacrifice(CardCollection(sacrificed), cause, true, game.lastStateParams())
            probe.operationEventsFor(sacrificed) shouldContainExactly
                listOf(GameEventCardSacrificed::class.java, GameEventCardChangeZone::class.java)
            assertSoftly {
                probe
                    .sacrificed()
                    .single()
                    .cause()
                    ?.sourceCardId() shouldBe source.id
                probe
                    .sacrificed()
                    .single()
                    .cause()
                    ?.costPayment() shouldBe false
            }
        }

        test("cost payment context is live while the zone event fires") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human)
                    addCard("Grizzly Bears", human)
                }
            val probe = game.subscribeProbe()
            val source =
                game.humanPlayer.battlefield.card("Goblin Fireslinger")
            val sacrificed =
                game.humanPlayer.battlefield.card("Grizzly Bears")
            val cause = source.spellAbilities.first().also { it.activatingPlayer = game.humanPlayer }
            val costPart = CostSacrifice("1", "Creature", "a creature")
            val payment = CostPayment(Cost("0", true), cause)
            game.copyLastState()

            game.costPaymentStack.push(costPart, payment)
            try {
                game.action.sacrifice(CardCollection(sacrificed), cause, false, game.lastStateParams())
            } finally {
                game.costPaymentStack.pop()
            }

            assertSoftly {
                probe.paymentAtZoneChange?.cost() shouldBe costPart
                probe.paymentAtZoneChange?.payment()?.ability shouldBe cause
                probe
                    .zoneChanges()
                    .single()
                    .cause()
                    ?.sourceCardId() shouldBe source.id
                probe
                    .zoneChanges()
                    .single()
                    .cause()
                    ?.abilityId() shouldBe cause.id
                probe
                    .zoneChanges()
                    .single()
                    .cause()
                    ?.costPayment() shouldBe true
                probe
                    .sacrificed()
                    .single()
                    .cause()
                    ?.costPayment() shouldBe true
            }
        }

        test("same-frame multi-hop moves remain distinct and ordered") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val probe = game.subscribeProbe()
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val moved =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val cause = source.spellAbilities.first().also { it.activatingPlayer = game.humanPlayer }

            game.action.moveToStack(moved, cause)
            game.action.exile(moved, cause, AbilityKey.newMap())

            val moves = probe.zoneChanges()
            assertSoftly {
                moves.map { it.card().id } shouldContainExactly listOf(moved.id, moved.id)
                moves.map { it.from().zoneType() } shouldContainExactly listOf(ZoneType.Hand, ZoneType.Stack)
                moves.map { it.to().zoneType() } shouldContainExactly listOf(ZoneType.Stack, ZoneType.Exile)
            }
        }

        test("instant resolution precedes its final stack move") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Library)
                    addCard("Grizzly Bears", human, ZoneType.Library)
                    addCard("Divination", human, ZoneType.Hand)
                }
            val probe = game.subscribeProbe()
            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val ability = spell.firstSpellAbility.also { it.activatingPlayer = game.humanPlayer }

            game.putSpellOnStack(spell, ability)
            probe.castEventsFor(spell) shouldContainExactly
                listOf(GameEventCardChangeZone::class.java, GameEventSpellAbilityCast::class.java)
            probe
                .casts()
                .single()
                .cause()
                ?.abilityId() shouldBe ability.id

            probe.clear()
            game.stack.resolveStack()
            assertSoftly {
                probe.resolveEventsFor(spell) shouldContainExactly
                    listOf(GameEventSpellResolved::class.java, GameEventCardChangeZone::class.java)
                probe
                    .resolved()
                    .single()
                    .cause()
                    ?.abilityId() shouldBe ability.id
                probe
                    .zoneChanges()
                    .single { it.card().id == spell.id }
                    .cause()
                    ?.abilityId() shouldBe ability.id
            }
        }

        test("permanent stack move precedes its resolution fact") {
            val (_, game, _) =
                startWithBoard { _, human, _ -> addCard("Grizzly Bears", human, ZoneType.Hand) }
            val probe = game.subscribeProbe()
            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val ability = spell.firstSpellAbility.also { it.activatingPlayer = game.humanPlayer }

            game.putSpellOnStack(spell, ability)
            probe.clear()
            game.stack.resolveStack()

            probe.resolveEventsFor(spell) shouldContainExactly
                listOf(GameEventCardChangeZone::class.java, GameEventSpellResolved::class.java)
            probe
                .resolved()
                .single()
                .cause()
                ?.abilityId() shouldBe ability.id
        }
    })

private fun Game.subscribeProbe(): ZoneEventProbe = ZoneEventProbe(this).also(::subscribeToEvents)

private fun roundTrip(event: GameEventCardChangeZone): GameEventCardChangeZone {
    val bytes =
        ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(event) }
            bytes.toByteArray()
        }
    return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as GameEventCardChangeZone }
}

private fun Game.lastStateParams(): MutableMap<AbilityKey, Any> =
    AbilityKey.newMap<Any>().also {
        it[AbilityKey.LastStateBattlefield] = lastStateBattlefield
        it[AbilityKey.LastStateGraveyard] = lastStateGraveyard
    }

private fun Game.putSpellOnStack(
    card: Card,
    ability: SpellAbility,
) {
    stack.freezeStack(ability)
    ability.hostCard = action.moveToStack(card, ability)
    stack.addAndUnfreeze(ability)
}

private class ZoneEventProbe(
    private val game: Game,
) {
    private val events = mutableListOf<ForgeGameEvent>()
    var paymentAtZoneChange: CostPaymentStack.Entry? = null
        private set

    @Subscribe
    fun receive(event: ForgeGameEvent) {
        events += event
        if (event is GameEventCardChangeZone) paymentAtZoneChange = game.costPaymentStack.peek()
    }

    fun clear() {
        events.clear()
        paymentAtZoneChange = null
    }

    fun zoneChanges(): List<GameEventCardChangeZone> = events.filterIsInstance<GameEventCardChangeZone>()

    fun destroyed(): List<GameEventCardDestroyed> = events.filterIsInstance<GameEventCardDestroyed>()

    fun sacrificed(): List<GameEventCardSacrificed> = events.filterIsInstance<GameEventCardSacrificed>()

    fun casts(): List<GameEventSpellAbilityCast> = events.filterIsInstance<GameEventSpellAbilityCast>()

    fun resolved(): List<GameEventSpellResolved> = events.filterIsInstance<GameEventSpellResolved>()

    fun operationEventsFor(card: Card): List<Class<out ForgeGameEvent>> =
        events
            .filter { it.eventCardId() == card.id }
            .filter { it is GameEventCardDestroyed || it is GameEventCardSacrificed || it is GameEventCardChangeZone }
            .map { it::class.java }

    fun castEventsFor(card: Card): List<Class<out ForgeGameEvent>> =
        events
            .filter { it.eventCardId() == card.id }
            .filter { it is GameEventCardChangeZone || it is GameEventSpellAbilityCast }
            .map { it::class.java }

    fun resolveEventsFor(card: Card): List<Class<out ForgeGameEvent>> =
        events
            .filter { it.eventCardId() == card.id }
            .filter { it is GameEventCardChangeZone || it is GameEventSpellResolved }
            .map { it::class.java }
}

private fun ForgeGameEvent.eventCardId(): Int =
    when (this) {
        is GameEventCardChangeZone -> card().id
        is GameEventCardDestroyed -> card().id
        is GameEventCardSacrificed -> card().id
        is GameEventSpellAbilityCast -> sa().hostCard?.id ?: -1
        is GameEventSpellResolved -> spell().hostCard?.id ?: -1
        else -> -1
    }
