package leyline.conformance

import forge.game.card.Card
import forge.game.zone.ZoneType
import forge.model.FModel
import leyline.game.CardDb
import leyline.game.GameBridge
import org.slf4j.LoggerFactory

/**
 * Injects cards into a running game and registers them in both Forge and Leyline layers.
 *
 * Wraps `Player.getZone().add()` and additionally:
 * - Registers in [CardDb] via [TestCardRegistry.ensureCardRegistered] (derives from CardRules)
 * - Registers in [leyline.game.InstanceIdRegistry] (forgeCardId → instanceId)
 *
 * Result: injected card is indistinguishable from a "real" deck card in proto output.
 */
object TestCardInjector {
    private val log = LoggerFactory.getLogger(TestCardInjector::class.java)

    /**
     * Result of injecting a card — contains all IDs needed for test assertions.
     */
    data class InjectedCard(
        val card: Card,
        val grpId: Int,
        val instanceId: Int,
        val forgeCardId: Int,
    )

    /**
     * Inject a card by name into a player's zone.
     *
     * @param bridge the active GameBridge
     * @param playerSeatId 1 for human, 2 for AI
     * @param cardName Forge card name (e.g. "Serra Angel")
     * @param zone target zone (Hand, Battlefield, Library, Graveyard, etc.)
     * @param tapped if true and zone is Battlefield, enters tapped
     * @param sick if true (default), creature has summoning sickness
     * @return [InjectedCard] with all IDs for assertions
     */
    fun inject(
        bridge: GameBridge,
        playerSeatId: Int,
        cardName: String,
        zone: ZoneType,
        tapped: Boolean = false,
        sick: Boolean = true,
    ): InjectedCard {
        val player = bridge.getPlayer(playerSeatId)
            ?: error("No player for seatId=$playerSeatId")

        // 1. Create Forge Card object from paper card DB
        val db = FModel.getMagicDb().commonCards
        val paperCard = db.getCard(cardName)
            ?: run {
                forge.StaticData.instance().attemptToLoadCard(cardName)
                db.getCard(cardName)
            }
            ?: error("Card not found in Forge DB: $cardName")

        val game = bridge.getGame() ?: error("GameBridge not started")
        val card = Card.fromPaperCard(paperCard, player)
        card.setGameTimestamp(game.nextTimestamp)
        player.getZone(zone).add(card)

        if (zone == ZoneType.Battlefield) {
            if (tapped) card.tap(true, true, null, null)
            if (!sick) card.setSickness(false)
        }

        // 2. Register in CardDb (idempotent — delegates to CardDataDeriver)
        val grpId = TestCardRegistry.ensureCardRegistered(cardName)

        // 3. Allocate instanceId in InstanceIdRegistry
        val instanceId = bridge.ids.getOrAlloc(card.id)

        log.info(
            "Injected '{}' → {} (grpId={}, instanceId={}, forgeId={})",
            cardName,
            zone,
            grpId,
            instanceId,
            card.id,
        )

        return InjectedCard(
            card = card,
            grpId = grpId,
            instanceId = instanceId,
            forgeCardId = card.id,
        )
    }
}
