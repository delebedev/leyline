package leyline.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.Format
import leyline.domain.SystemPlayers
import leyline.game.data.CardRepository

/** Public spectator-match routes and their deck conversion boundary. */
internal fun Route.installPublicSpectatorRoutes(services: WebServices) {
    post("/public/spectator/start") {
        val rotation = services.deckService.listForPlayer(SystemPlayers.SPECTATOR).sortedBy { it.name }
        if (rotation.size < 2) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val turn = services.spectatorRotationCursor.getAndIncrement()
        val seat1 = rotation[Math.floorMod(turn, rotation.size)]
        val seat2 = rotation[Math.floorMod(turn + 1, rotation.size)]
        if (seat1.format != seat2.format) {
            call.application.log.warn("Spectator rotation pair has incompatible formats: '{}' and '{}'", seat1.name, seat2.name)
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val seat1Deck = decklistText(seat1, services.cardRepository)
        val seat2Deck = decklistText(seat2, services.cardRepository)
        if (seat1Deck == null || seat2Deck == null) {
            call.application.log.warn("Spectator rotation: '{}' or '{}' has cards the repository cannot name", seat1.name, seat2.name)
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val launched =
            services.matchLauncher.launchGreMatch(
                null,
                GreStartRequest(
                    seat1Deck = seat1Deck,
                    seat2Deck = seat2Deck,
                    gameVariant = seat1.format.gameVariant,
                    spectatorMode = true,
                ),
            )
        call.respond(
            PublicSpectatorResponse(launched.matchId, launched.wireMatchId, PublicSeatView(seat1.name), PublicSeatView(seat2.name)),
        )
    }
    get("/public/spectate/viewers") {
        call.respond(ViewerCountView(1))
    }
}

private val Format.gameVariant: String?
    get() = if (this == Format.Brawl) "brawl" else null

/**
 * A stored deck as the decklist string the launcher takes, or null if any entry
 * fails to resolve. Null rather than a partial list: a blank or short decklist
 * is silently replaced with a default deck downstream, so the seat would report
 * one deck and play another.
 *
 * Sideboards are omitted — nothing sideboards in an unattended match.
 */
private fun decklistText(
    deck: Deck,
    cards: CardRepository,
): String? {
    fun lines(entries: List<DeckCard>): List<String>? =
        entries.map { entry ->
            val name = cards.findNameByGrpId(entry.grpId) ?: return null
            "${entry.quantity} $name"
        }

    val main = lines(deck.mainDeck)?.takeIf { it.isNotEmpty() } ?: return null
    val commanders = lines(deck.commandZone) ?: return null
    return buildString {
        if (commanders.isNotEmpty()) {
            appendLine("[Commander]")
            commanders.forEach(::appendLine)
        }
        main.forEach(::appendLine)
    }.trim()
}
