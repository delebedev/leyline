package leyline.match

import io.netty.channel.ChannelHandlerContext
import leyline.config.MatchConfig
import leyline.domain.service.MatchCoordinator
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("LongParameterList")
internal class MatchConnectFlow(
    private val registry: MatchRegistry,
    private val matchConfig: MatchConfig,
    private val coordinator: MatchCoordinator?,
    private val cardRepository: CardRepository,
    private val puzzleHandler: PuzzleHandler,
    private val matchId: () -> String,
    private val seatId: () -> Int,
    private val isFamiliar: () -> Boolean,
    private val createMatchSession: (ChannelHandlerContext, GameBridge) -> MatchSession,
    private val createFamiliarSession: (ChannelHandlerContext, MessageCounter) -> FamiliarSession,
    private val createSpectatorSession: (ChannelHandlerContext, GameBridge) -> SpectatorSession,
    private val sendRoomState: (ChannelHandlerContext) -> Unit,
    private val sendInitialBundle: (ChannelHandlerContext) -> Unit,
    private val resolveSeatDecks: () -> Pair<String, String>,
    private val resolveGameVariant: () -> String?,
    private val isSpectatorMode: () -> Boolean,
    private val onLocalPlayerConnected: (ChannelHandlerContext, GameBridge) -> Unit,
) {
    private val log = LoggerFactory.getLogger(MatchHandler::class.java)

    fun onConnect(ctx: ChannelHandlerContext) {
        val eventName = coordinator?.selectedEventName
        if (eventName != null) log.info("Match Door: event={}", eventName)

        // Evict stale bridges from previous matches and reset debug collectors.
        val evicted = registry.evictStale(matchId())
        if (evicted.isNotEmpty()) {
            log.info("Match Door: evicted {} stale match(es)", evicted.size)
        }

        if (puzzleHandler.isPuzzleMatch(matchId())) {
            connectPuzzle(ctx)
        } else {
            connectConstructed(ctx)
        }
    }

    private fun connectPuzzle(ctx: ChannelHandlerContext) {
        sendRoomState(ctx)
        if (isFamiliar()) {
            log.info("Match Door: puzzle mode, familiar (seat {}) connected — no-op", seatId())
            return
        }
        val bridge = puzzleHandler.getOrCreatePuzzleBridge(matchId())
        val ms = createMatchSession(ctx, bridge)
        puzzleHandler.sendPuzzleInitialBundle(ctx, ms, matchId(), seatId())
    }

    private fun connectConstructed(ctx: ChannelHandlerContext) {
        // Constructed mode: normal local player + built-in AI flow.
        val gameVariant = resolveGameVariant()
        val match =
            registry.getOrCreateMatch(matchId()) {
                val bridge =
                    GameBridge(
                        bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                        promptFailsafeMs = matchConfig.server.promptFailsafeMs,
                        matchConfig = matchConfig,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(matchId(), bridge).also { newMatch ->
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
            connectSpectator(ctx, match, gameVariant)
        } else if (isFamiliar()) {
            createFamiliarSession(ctx, bridge.messageCounter)
            sendRoomState(ctx)
            sendInitialBundle(ctx)
        } else {
            onLocalPlayerConnected(ctx, bridge)
        }
    }

    private fun connectSpectator(
        ctx: ChannelHandlerContext,
        match: Match,
        gameVariant: String?,
    ) {
        if (isFamiliar()) {
            sendRoomState(ctx)
            log.info("Match Door: spectator familiar connected, room-state-only no-op")
            return
        }
        val spectator = createSpectatorSession(ctx, match.bridge)
        sendRoomState(ctx)
        if (match.state == MatchState.WAITING) {
            if (!startSpectatorMatch(ctx, match, gameVariant)) return
        } else {
            sendInitialBundle(ctx)
        }
        spectator.startPump()
    }

    private fun startSpectatorMatch(
        ctx: ChannelHandlerContext,
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
            ctx.close()
            return false
        }
        try {
            sendInitialBundle(ctx)
        } finally {
            initialBundleSent.countDown()
        }
        return true
    }
}
