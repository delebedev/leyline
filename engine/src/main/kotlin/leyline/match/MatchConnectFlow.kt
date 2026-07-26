package leyline.match

import leyline.config.MatchConfig
import leyline.domain.service.MatchCoordinator
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val onFamiliarConnected: (MessageCounter) -> Unit,
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

        registry.ownerFor(attempt.matchId).reduce {
            if (puzzleHandler.isPuzzleMatch(attempt.matchId)) {
                connectPuzzle(attempt)
            } else {
                connectConstructed(attempt)
            }
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
                        bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                        promptFailsafeMs = matchConfig.server.promptFailsafeMs,
                        matchConfig = matchConfig,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(attempt.matchId, bridge).also { newMatch ->
                    if (!isSpectatorMode()) {
                        val decks = resolveSeatDecks()
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
            connectSpectator(attempt, match, gameVariant)
        } else if (attempt.familiar) {
            sendRoomState()
            onFamiliarConnected(bridge.messageCounter)
        } else {
            onLocalPlayerConnected(bridge)
        }
    }

    private fun connectSpectator(
        attempt: ConnectAttempt,
        match: Match,
        gameVariant: String?,
    ) {
        if (attempt.familiar) {
            sendRoomState()
            log.info("Match Door: spectator familiar connected, room-state-only no-op")
            return
        }
        val spectator = createSpectatorSession(match.bridge)
        sendRoomState()
        if (match.state == MatchState.WAITING) {
            if (!startSpectatorMatch(match, gameVariant)) return
        } else {
            sendInitialBundle()
        }
        spectator.startPump()
    }

    private fun startSpectatorMatch(
        match: Match,
        gameVariant: String?,
    ): Boolean {
        val decks = resolveSeatDecks()
        val readyForInitialBundle = CountDownLatch(1)
        val initialBundleSent = CountDownLatch(1)
        match.startAiVsAi(
            seed = matchConfig.game.seed,
            deckList1 = decks.first,
            deckList2 = decks.second,
            variant = gameVariant,
            startGameHook =
                Runnable {
                    readyForInitialBundle.countDown()
                    if (!initialBundleSent.await(10, TimeUnit.SECONDS)) {
                        log.warn("Match Door: spectator initial bundle timed out, resuming AI loop")
                    }
                },
        )
        if (!readyForInitialBundle.await(10, TimeUnit.SECONDS)) {
            log.warn("Match Door: spectator game did not reach initial bundle barrier")
            initialBundleSent.countDown()
            return false
        }
        try {
            sendInitialBundle()
        } finally {
            initialBundleSent.countDown()
        }
        return true
    }
}
