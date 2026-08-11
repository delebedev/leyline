package leyline.match

import leyline.config.MatchConfig
import leyline.domain.service.MatchCoordinator
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import org.slf4j.LoggerFactory

internal data class ConnectAttempt(
    val matchId: String,
    val seatId: Int,
    val familiar: Boolean,
)

@Suppress("LongParameterList")
internal class MatchConnectFlow(
    private val registry: MatchRegistry,
    private val matchConfig: MatchConfig,
    private val coordinator: MatchCoordinator?,
    private val cardRepository: CardRepository,
    private val puzzleHandler: PuzzleHandler,
    private val output: MatchOutput,
    private val createMatchSession: (GameBridge) -> MatchSession,
    private val createFamiliarSession: (MessageCounter) -> FamiliarSession,
    private val createSpectatorSession: (GameBridge) -> SpectatorSession,
    private val sendRoomState: () -> Unit,
    private val sendInitialBundle: () -> Unit,
    private val resolveSeatDecks: () -> Pair<String, String>,
    private val resolveGameVariant: () -> String?,
    private val isSpectatorMode: () -> Boolean,
    private val onLocalPlayerConnected: (GameBridge) -> Unit,
) {
    private val log = LoggerFactory.getLogger(MatchConnectFlow::class.java)

    fun onConnect(attempt: ConnectAttempt) {
        val eventName = coordinator?.selectedEventName
        if (eventName != null) log.info("Match Door: event={}", eventName)

        // Evict stale bridges from previous matches and reset debug collectors.
        val evicted = registry.evictStale(attempt.matchId)
        if (evicted.isNotEmpty()) {
            log.info("Match Door: evicted {} stale match(es)", evicted.size)
        }

        if (puzzleHandler.isPuzzleMatch(attempt.matchId)) {
            connectPuzzle(attempt)
        } else {
            connectConstructed(attempt)
        }
    }

    private fun connectPuzzle(attempt: ConnectAttempt) {
        sendRoomState()
        if (attempt.familiar) {
            log.info("Match Door: puzzle mode, familiar (seat {}) connected — no-op", attempt.seatId)
            return
        }
        val bridge = puzzleHandler.getOrCreatePuzzleBridge(attempt.matchId)
        val ms = createMatchSession(bridge)
        puzzleHandler.sendPuzzleInitialBundle(output, ms, attempt.matchId, attempt.seatId)
    }

    private fun connectConstructed(attempt: ConnectAttempt) {
        // Constructed mode: normal local player + built-in AI flow.
        val gameVariant = resolveGameVariant()
        val match =
            registry.getOrCreateMatch(attempt.matchId) {
                val bridge =
                    GameBridge(
                        matchId = attempt.matchId,
                        bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                        promptFailsafeMs = matchConfig.server.promptFailsafeMs,
                        matchConfig = matchConfig,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(attempt.matchId, bridge).also { newMatch ->
                    // Start the game at match creation (once — CAS-guarded) so both
                    // client connections see a running game and just send their
                    // bundles. Mirrors the non-spectator flow; avoids two connects
                    // racing to start it.
                    val decks = resolveSeatDecks()
                    if (isSpectatorMode()) {
                        newMatch.startAiVsAi(
                            seed = matchConfig.game.seed,
                            deckList1 = decks.first,
                            deckList2 = decks.second,
                            variant = gameVariant,
                        )
                    } else {
                        newMatch.start(
                            seed = matchConfig.game.seed,
                            deckList1 = decks.first,
                            deckList2 = decks.second,
                            variant = gameVariant,
                        )
                    }
                }
            }
        val bridge = match.bridge
        if (isSpectatorMode()) {
            connectSpectator(attempt, match)
        } else if (attempt.familiar) {
            createFamiliarSession(bridge.messageCounter)
            sendRoomState()
            sendInitialBundle()
        } else {
            onLocalPlayerConnected(bridge)
        }
    }

    private fun connectSpectator(
        attempt: ConnectAttempt,
        match: Match,
    ) {
        // The game is already running (started at match creation). Both connections
        // send their initial bundle: seat 2 (familiar) carries the
        // ChooseStartingPlayerReq handshake the client needs to leave the connecting
        // state and render — a bare room-state no-op left it on a blank board.
        // onChooseStartingPlayerResp is spectator-safe (deals hands;
        // SpectatorSession.onMulliganKeep is a no-op). Only the primary streams.
        val spectator = createSpectatorSession(match.bridge)
        sendRoomState()
        sendInitialBundle()
        if (!attempt.familiar) spectator.startPump()
    }
}
