package leyline.match

import forge.gamemodes.puzzle.Puzzle
import io.netty.channel.ChannelHandlerContext
import leyline.bridge.GameBootstrap
import leyline.config.MatchConfig
import leyline.game.CardRepository
import leyline.game.GameBridge
import leyline.game.InMemoryCardRepository
import leyline.game.PuzzleSource
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Puzzle mode delegate — routed by `puzzle-<name>` match IDs. When
 * [puzzlePath] returns a non-null path, that file is loaded for routed puzzle
 * matches; otherwise the matchId naming convention resolves `puzzles/<name>.pzl`.
 *
 * **Ordering constraint:** [GameBootstrap.initializeLocalization] must be called
 * before any [Puzzle] constructor — Forge's `GameState.<clinit>` reads localized
 * card data. This is enforced inside [loadPuzzleForMatch], not at construction time,
 * so the handler can be created eagerly without triggering Forge class loading.
 */
class PuzzleHandler(
    private val puzzlePath: () -> String?,
    private val cards: CardRepository?,
    private val registry: MatchRegistry,
    private val matchConfig: MatchConfig = MatchConfig(),
) {
    private val log = LoggerFactory.getLogger(PuzzleHandler::class.java)

    /** Puzzle mode when a puzzle file is configured — matchId is irrelevant. */
    @Suppress("UnusedParameter") // matchId kept for call-site clarity
    fun isPuzzleMatch(matchId: String): Boolean =
        puzzlePath() != null

    /**
     * Handle ConnectReq in puzzle mode: create bridge with puzzle game, send initial bundle.
     *
     * @return the created [GameBridge]
     */
    fun onPuzzleConnect(
        ctx: ChannelHandlerContext,
        session: MatchSession,
        matchId: String,
        seatId: Int,
    ): GameBridge {
        val match = registry.getOrCreateMatch(matchId) {
            val bridge = GameBridge(
                matchConfig = matchConfig,
                messageCounter = session.counter,
                cards = cards ?: InMemoryCardRepository(),
            )
            Match(matchId, bridge).also {
                val puzzle = loadPuzzleForMatch(matchId)
                bridge.startPuzzle(puzzle)
            }
        }
        val bridge = match.bridge
        session.connectBridge(bridge)
        log.info("Match Door: puzzle mode, seat {} connected", seatId)
        sendPuzzleInitialBundle(ctx, session, matchId, seatId)
        return bridge
    }

    /** Send puzzle initial bundle: ConnectResp + Full GSM (stage=Play) + ActionsAvailableReq. */
    private fun sendPuzzleInitialBundle(
        ctx: ChannelHandlerContext,
        session: MatchSession,
        matchId: String,
        seatId: Int,
    ) {
        val bridge = session.gameBridge ?: return
        val gsId = session.counter.nextGsId()

        val (bundleMsg, nextMsgId) = HandshakeMessages.puzzleInitialBundle(
            seatId,
            matchId,
            session.counter.currentMsgId(),
            gsId,
            bridge,
        )
        session.counter.setMsgId(nextMsgId)
        Tap.outboundTemplate("PuzzleInitialBundle seat=$seatId")
        ProtoDump.dump(bundleMsg, "PuzzleInitialBundle-seat$seatId")
        ctx.writeAndFlush(bundleMsg)

        // Send ActionsAvailableReq immediately after
        val (actionsMsg, nextMsgId2) = HandshakeMessages.puzzleActionsReq(
            session.counter.currentMsgId(),
            gsId,
            seatId,
            bridge,
        )
        session.counter.setMsgId(nextMsgId2)
        Tap.outboundTemplate("PuzzleActionsReq seat=$seatId")
        ProtoDump.dump(actionsMsg, "PuzzleActionsReq-seat$seatId")
        ctx.writeAndFlush(actionsMsg)

        // Enter the game loop — same as onMulliganKeep but without mulligan
        session.onPuzzleStart()
    }

    /** Load puzzle: prefer config file path, fall back to matchId convention. */
    private fun loadPuzzleForMatch(matchId: String): Puzzle {
        // Puzzle constructor triggers GameState.<clinit> which needs localization
        GameBootstrap.initializeLocalization()

        val configuredPuzzle = puzzlePath()
        if (configuredPuzzle != null) {
            val file = File(configuredPuzzle).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), configuredPuzzle) }
            require(file.exists()) { "Puzzle file not found: ${file.absolutePath}" }
            return PuzzleSource.loadFromFile(file.absolutePath)
        }

        val puzzleName = matchId.removePrefix("puzzle-")
        val leylineDir = findLeylineDir()
        val puzzlesDir = File(leylineDir, "puzzles")
        val pzlFile = File(puzzlesDir, "$puzzleName.pzl")
        if (pzlFile.exists()) {
            return PuzzleSource.loadFromFile(pzlFile.absolutePath)
        }
        val pzlFile2 = File(puzzlesDir, puzzleName)
        if (pzlFile2.exists()) {
            return PuzzleSource.loadFromFile(pzlFile2.absolutePath)
        }
        error("Puzzle not found: $puzzleName (looked in ${puzzlesDir.absolutePath})")
    }
}
