package leyline.match

import forge.gamemodes.puzzle.Puzzle
import io.netty.channel.ChannelHandlerContext
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.MatchConfig
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Puzzle mode delegate — routed by per-match runtime config or `puzzle-<name>`
 * match IDs. When [puzzlePath] returns a non-null path for a matchId, that file
 * is loaded; otherwise the matchId naming convention resolves `puzzles/<name>.pzl`.
 *
 * **Ordering constraint:** [GameBootstrap.initializeLocalization] must be called
 * before any [Puzzle] constructor — Forge's `GameState.<clinit>` reads localized
 * card data. This is enforced inside [loadPuzzleForMatch], not at construction time,
 * so the handler can be created eagerly without triggering Forge class loading.
 */
class PuzzleHandler(
    private val puzzlePath: (String) -> String?,
    private val cardRepository: CardRepository,
    private val registry: MatchRegistry,
    private val matchConfig: MatchConfig = MatchConfig(),
) {
    private val log = LoggerFactory.getLogger(PuzzleHandler::class.java)

    fun isPuzzleMatch(matchId: String): Boolean = puzzlePath(matchId) != null || matchId.startsWith("puzzle-")

    /**
     * Get or create the [GameBridge] for a puzzle match. Loads the puzzle file
     * on first call; subsequent calls for the same matchId return the existing
     * bridge.
     *
     * Callers then construct a [MatchSession] bound to this bridge and invoke
     * [sendPuzzleInitialBundle] to send the opening GRE bundle.
     */
    fun getOrCreatePuzzleBridge(matchId: String): GameBridge {
        val match =
            registry.getOrCreateMatch(matchId) {
                val bridge =
                    GameBridge(
                        bridgeTimeoutMs = matchConfig.server.bridgeTimeoutMs,
                        promptFailsafeMs = matchConfig.server.promptFailsafeMs,
                        matchConfig = matchConfig,
                        messageCounter = MessageCounter(),
                        cardRepository = cardRepository,
                    )
                Match(matchId, bridge).also {
                    val puzzle = loadPuzzleForMatch(matchId)
                    bridge.startPuzzle(puzzle)
                }
            }
        return match.bridge
    }

    /** Send puzzle initial bundle: ConnectResp + Full GSM (stage=Play) + ActionsAvailableReq. */
    fun sendPuzzleInitialBundle(
        ctx: ChannelHandlerContext,
        session: MatchSession,
        matchId: String,
        seatId: Int,
    ) {
        val bridge = session.gameBridge
        log.info("Match Door: puzzle mode, seat {} connected", seatId)
        val gsId = session.counter.nextGsId()

        val (bundleMsg, nextMsgId) =
            HandshakeMessages.puzzleInitialBundle(
                SeatId(seatId),
                matchId,
                session.counter.currentMsgId(),
                gsId,
                bridge,
            )
        session.counter.setMsgId(nextMsgId)
        session.counter.markGameStateGsId(gsId)
        Tap.outboundTemplate("PuzzleInitialBundle seat=$seatId")
        ProtoDump.dump(bundleMsg, "PuzzleInitialBundle-seat$seatId")
        ctx.writeAndFlush(bundleMsg)

        // Send ActionsAvailableReq immediately after
        val (actionsMsg, nextMsgId2) =
            HandshakeMessages.puzzleActionsReq(
                session.counter.currentMsgId(),
                gsId,
                SeatId(seatId),
                bridge,
            )
        session.counter.setMsgId(nextMsgId2)
        session.counter.markPromptGsId(gsId)
        bridge.seat(SeatId(seatId)).action.markCurrentPromptEmitted(gsId)
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

        val configuredPuzzle = puzzlePath(matchId)
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
