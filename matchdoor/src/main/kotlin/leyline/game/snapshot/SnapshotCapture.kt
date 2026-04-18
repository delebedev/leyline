package leyline.game.snapshot

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.game.GameBridge
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Produces a [GsmSnapshot] by reading [Game] + [GameBridge]. This is the only
 * place in the pipeline (aside from [leyline.game.BundleBuilder]'s capture call)
 * that reads `forge.game.Game` directly. Each mapper migration grows the capture
 * to cover the newly-migrated stage's reads.
 *
 * Task 1: bare skeleton — matchId + empty collections.
 * Task 2: populates [GsmSnapshot.seats] for seats 1 and 2.
 * Task 4: populates [GsmSnapshot.zones] — hand/library/graveyard per seat +
 *   shared zones (battlefield/stack/exile/command).
 *   Later tasks populate each section as the corresponding mapper migrates.
 */
internal object SnapshotCapture {
    fun run(game: Game, bridge: GameBridge, matchId: String): GsmSnapshot {
        val seats = listOf(1, 2).mapNotNull { seatNum ->
            val player = bridge.getPlayer(SeatId(seatNum)) ?: return@mapNotNull null
            SeatSnapshot(
                seatId = SeatId(seatNum),
                life = player.life,
                startingLife = player.startingLife,
                maxHandSize = player.maxHandSize,
            )
        }
        val zones = captureZones(game, bridge)
        return GsmSnapshot.forTest(
            matchId = matchId,
            seats = seats,
            zones = zones,
            capturedAt = CaptureMarker(
                gsIdBeforeCapture = -1,
                wallClockMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun captureZones(game: Game, bridge: GameBridge): Map<Int, ZoneSnapshot> {
        val result = linkedMapOf<Int, ZoneSnapshot>()
        for (seatNum in listOf(1, 2)) {
            val player = bridge.getPlayer(SeatId(seatNum)) ?: continue
            capturePlayerZone(player, seatNum, ForgeZoneType.Hand, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Library, result)
            capturePlayerZone(player, seatNum, ForgeZoneType.Graveyard, result)
        }
        captureSharedZone(game, ForgeZoneType.Battlefield, result)
        captureSharedZone(game, ForgeZoneType.Stack, result)
        captureSharedZone(game, ForgeZoneType.Exile, result)
        captureSharedZone(game, ForgeZoneType.Command, result)
        return result
    }

    private fun capturePlayerZone(
        player: forge.game.player.Player,
        seatNum: Int,
        fz: ForgeZoneType,
        out: MutableMap<Int, ZoneSnapshot>,
    ) {
        val zone = player.getZone(fz) ?: return
        val arenaZoneId = playerZoneId(seatNum, fz) ?: return
        val arenaType = arenaTypeFor(fz)
        val visibility = visibilityFor(fz)
        out[arenaZoneId] = ZoneSnapshot(
            id = arenaZoneId,
            type = arenaType,
            owner = SeatId(seatNum),
            visibility = visibility,
            contents = zone.cards.map { ForgeCardId(it.id) },
        )
    }

    private fun captureSharedZone(
        game: Game,
        fz: ForgeZoneType,
        out: MutableMap<Int, ZoneSnapshot>,
    ) {
        val arenaZoneId = sharedZoneId(fz) ?: return
        val arenaType = arenaTypeFor(fz)
        out[arenaZoneId] = ZoneSnapshot(
            id = arenaZoneId,
            type = arenaType,
            owner = null,
            visibility = Visibility.Public,
            contents = game.getCardsIn(fz).map { ForgeCardId(it.id) },
        )
    }

    private fun playerZoneId(seat: Int, fz: ForgeZoneType): Int? = when (fz) {
        ForgeZoneType.Hand -> if (seat == 1) ZoneIds.P1_HAND else ZoneIds.P2_HAND
        ForgeZoneType.Library -> if (seat == 1) ZoneIds.P1_LIBRARY else ZoneIds.P2_LIBRARY
        ForgeZoneType.Graveyard -> if (seat == 1) ZoneIds.P1_GRAVEYARD else ZoneIds.P2_GRAVEYARD
        ForgeZoneType.Battlefield,
        ForgeZoneType.Exile,
        ForgeZoneType.Flashback,
        ForgeZoneType.Command,
        ForgeZoneType.Stack,
        ForgeZoneType.Sideboard,
        ForgeZoneType.Ante,
        ForgeZoneType.Merged,
        ForgeZoneType.SchemeDeck,
        ForgeZoneType.PlanarDeck,
        ForgeZoneType.AttractionDeck,
        ForgeZoneType.Junkyard,
        ForgeZoneType.ContraptionDeck,
        ForgeZoneType.Subgame,
        ForgeZoneType.ExtraHand,
        ForgeZoneType.None,
        -> null
    }

    private fun sharedZoneId(fz: ForgeZoneType): Int? = when (fz) {
        ForgeZoneType.Battlefield -> ZoneIds.BATTLEFIELD
        ForgeZoneType.Stack -> ZoneIds.STACK
        ForgeZoneType.Exile -> ZoneIds.EXILE
        ForgeZoneType.Command -> ZoneIds.COMMAND
        ForgeZoneType.Hand,
        ForgeZoneType.Library,
        ForgeZoneType.Graveyard,
        ForgeZoneType.Flashback,
        ForgeZoneType.Sideboard,
        ForgeZoneType.Ante,
        ForgeZoneType.Merged,
        ForgeZoneType.SchemeDeck,
        ForgeZoneType.PlanarDeck,
        ForgeZoneType.AttractionDeck,
        ForgeZoneType.Junkyard,
        ForgeZoneType.ContraptionDeck,
        ForgeZoneType.Subgame,
        ForgeZoneType.ExtraHand,
        ForgeZoneType.None,
        -> null
    }

    private fun arenaTypeFor(fz: ForgeZoneType): ZoneType = when (fz) {
        ForgeZoneType.Hand -> ZoneType.Hand
        ForgeZoneType.Library -> ZoneType.Library
        ForgeZoneType.Graveyard -> ZoneType.Graveyard
        ForgeZoneType.Sideboard -> ZoneType.Sideboard
        ForgeZoneType.Command -> ZoneType.Command
        ForgeZoneType.Battlefield -> ZoneType.Battlefield
        ForgeZoneType.Stack -> ZoneType.Stack
        ForgeZoneType.Exile -> ZoneType.Exile
        ForgeZoneType.Flashback,
        ForgeZoneType.Ante,
        ForgeZoneType.Merged,
        ForgeZoneType.SchemeDeck,
        ForgeZoneType.PlanarDeck,
        ForgeZoneType.AttractionDeck,
        ForgeZoneType.Junkyard,
        ForgeZoneType.ContraptionDeck,
        ForgeZoneType.Subgame,
        ForgeZoneType.ExtraHand,
        ForgeZoneType.None,
        -> ZoneType.UNRECOGNIZED
    }

    private fun visibilityFor(fz: ForgeZoneType): Visibility = when (fz) {
        ForgeZoneType.Hand,
        ForgeZoneType.Library,
        ForgeZoneType.Sideboard,
        -> Visibility.Private
        ForgeZoneType.Battlefield,
        ForgeZoneType.Exile,
        ForgeZoneType.Flashback,
        ForgeZoneType.Command,
        ForgeZoneType.Stack,
        ForgeZoneType.Graveyard,
        ForgeZoneType.Ante,
        ForgeZoneType.Merged,
        ForgeZoneType.SchemeDeck,
        ForgeZoneType.PlanarDeck,
        ForgeZoneType.AttractionDeck,
        ForgeZoneType.Junkyard,
        ForgeZoneType.ContraptionDeck,
        ForgeZoneType.Subgame,
        ForgeZoneType.ExtraHand,
        ForgeZoneType.None,
        -> Visibility.Public
    }
}
