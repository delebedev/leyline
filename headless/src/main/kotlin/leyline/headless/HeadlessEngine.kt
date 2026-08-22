package leyline.headless

import forge.game.card.Card
import forge.game.card.CounterType
import forge.game.zone.ZoneType
import forge.model.FModel
import leyline.bridge.types.SeatId
import leyline.match.MatchRegistry

enum class HeadlessZone {
    Hand,
    Battlefield,
    Library,
    Graveyard,
    Exile,
    Command,
    Sideboard,
}

data class HeadlessCard(
    val name: String,
    val stateName: String?,
    val counters: Map<String, Int>,
    val sVars: Map<String, String>,
)

/** Bounded engine-state reader and fixture command surface. */
class HeadlessEngine private constructor(
    private val driver: HeadlessEngineDriver,
) {
    fun cards(
        seatId: Int,
        zone: HeadlessZone,
    ): List<HeadlessCard> = driver.cards(seatId, zone).map(::value)

    fun card(
        seatId: Int,
        zone: HeadlessZone,
        name: String,
    ): HeadlessCard? = driver.cards(seatId, zone).firstOrNull { it.name == name }?.let(::value)

    fun libraryNames(seatId: Int): List<String> = driver.cards(seatId, HeadlessZone.Library).map(Card::getName)

    fun addFixtureCard(
        seatId: Int,
        zone: HeadlessZone,
        name: String,
        tapped: Boolean = false,
        sick: Boolean = true,
    ): HeadlessCard = value(driver.addCard(seatId, zone, name, tapped, sick))

    fun setSVar(
        seatId: Int,
        zone: HeadlessZone,
        cardName: String,
        key: String,
        value: String,
    ) {
        driver.requireCard(seatId, zone, cardName).setSVar(key, value)
    }

    fun setCounter(
        seatId: Int,
        zone: HeadlessZone,
        cardName: String,
        counterName: String,
        count: Int,
    ) {
        require(count >= 0) { "count must be non-negative" }
        driver.requireCard(seatId, zone, cardName).setCounters(CounterType.getType(counterName), count)
    }

    private fun value(card: Card): HeadlessCard =
        HeadlessCard(
            name = card.name,
            stateName = card.currentState?.stateName?.name,
            counters = card.counters.entrySet().associate { it.element.name to it.count },
            sVars = card.sVars.toMap(),
        )

    internal companion object {
        fun create(
            registry: MatchRegistry,
            matchId: String,
        ) = HeadlessEngine(HeadlessEngineDriver(registry, matchId))
    }
}

private class HeadlessEngineDriver(
    private val registry: MatchRegistry,
    private val matchId: String,
) {
    fun cards(
        seatId: Int,
        zone: HeadlessZone,
    ): List<Card> =
        bridge()
            .getPlayer(SeatId(seatId))
            ?.getZone(zone.forge)
            ?.cards
            ?.toList()
            .orEmpty()

    fun requireCard(
        seatId: Int,
        zone: HeadlessZone,
        name: String,
    ): Card = cards(seatId, zone).firstOrNull { it.name == name } ?: error("Card '$name' not found in seat $seatId $zone")

    fun addCard(
        seatId: Int,
        zone: HeadlessZone,
        name: String,
        tapped: Boolean,
        sick: Boolean,
    ): Card {
        val bridge = bridge()
        val player = bridge.getPlayer(SeatId(seatId)) ?: error("No player at seat $seatId")
        val cards = FModel.getMagicDb().commonCards
        val paperCard =
            cards.getCard(name)
                ?: run {
                    forge.StaticData.instance().attemptToLoadCard(name)
                    cards.getCard(name)
                }
                ?: error("Card not found: $name")
        val card = Card.fromPaperCard(paperCard, player)
        card.setGameTimestamp(player.game.nextTimestamp)
        player.getZone(zone.forge).add(card)
        if (zone == HeadlessZone.Battlefield) {
            if (tapped) card.tap(true, true, null, null)
            card.setSickness(sick)
        }
        checkNotNull(bridge.cardRepository.findGrpIdByName(name)) { "No client fixture for '$name'" }
        bridge.instanceId(card)
        return card
    }

    private fun bridge() = checkNotNull(registry.getMatch(matchId)) { "Connect the match before reading engine state" }.bridge
}

private val HeadlessZone.forge: ZoneType
    get() =
        when (this) {
            HeadlessZone.Hand -> ZoneType.Hand
            HeadlessZone.Battlefield -> ZoneType.Battlefield
            HeadlessZone.Library -> ZoneType.Library
            HeadlessZone.Graveyard -> ZoneType.Graveyard
            HeadlessZone.Exile -> ZoneType.Exile
            HeadlessZone.Command -> ZoneType.Command
            HeadlessZone.Sideboard -> ZoneType.Sideboard
        }
