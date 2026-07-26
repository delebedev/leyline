package leyline.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import forge.ai.simulation.SpellAbilityPicker
import kotlinx.serialization.Serializable
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.domain.json.productionJson
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.GsmBuilder
import leyline.game.bundle.GsmFrame
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.GameBridge
import leyline.match.MatchSession
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded HTTP server for local engine diagnostics and puzzle control.
 * Zero-dep JDK [HttpServer] on the given port (default 8090).
 *
 * Only engine-specific endpoints live here. Read-only state inspection lives in separate analysis tooling.
 *
 * Endpoints:
 * - `GET /api/best-play`   → engine simulation recommendation for current board state
 * - `POST /api/inject-full` → rebuild and push a full state update to the client
 * - `GET /api/puzzle`       → current puzzle state
 * - `POST /api/puzzle`      → set/clear/hot-swap puzzle
 *
 * Binds loopback-only by default. Set `LEYLINE_DEBUG_BIND=0.0.0.0` to expose
 * these local controls on all interfaces.
 */
@Suppress("LargeClass") // Debug routes share the same local server and session providers.
class DebugServer(
    private val port: Int = 8090,
    private val sessionProvider: (() -> MatchSession?)? = null,
    /** Runtime puzzle holder — set/cleared by POST /api/puzzle. */
    private val runtimePuzzle: AtomicReference<String?>? = null,
) {
    private val log = LoggerFactory.getLogger(DebugServer::class.java)
    private var server: HttpServer? = null

    private val json =
        productionJson {
            prettyPrint = false
            encodeDefaults = true
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun start() {
        val srv = HttpServer.create(InetSocketAddress(resolveBindAddress(), port), 0)

        mapOf(
            "/api/best-play" to ::serveBestPlay,
        ).forEach { (path, handler) ->
            srv.createContext(path) { ex -> safe(ex) { handler(ex) } }
        }

        srv.postContext("/api/inject-full", ::serveInjectFull)
        srv.createContext("/api/puzzle") { ex ->
            try {
                when (ex.requestMethod) {
                    "GET" -> serveGetPuzzle(ex)
                    "POST" -> servePuzzle(ex)
                    else -> {
                        ex.sendResponseHeaders(405, -1)
                        ex.close()
                    }
                }
            } catch (t: Throwable) {
                log.error("/api/puzzle error: {}", t.message, t)
                try {
                    respond(ex, 500, "text/plain", "Error: ${t.message}")
                } catch (_: Throwable) {
                    try {
                        ex.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        srv.executor =
            Executors.newCachedThreadPool { r ->
                Thread(r, "debug-http").apply { isDaemon = true }
            }
        srv.start()
        server = srv
        log.info("Debug controls listening on http://localhost:{}", port)
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** Loopback by default; `LEYLINE_DEBUG_BIND=0.0.0.0` opts into binding all interfaces. */
    private fun resolveBindAddress(): String = System.getenv("LEYLINE_DEBUG_BIND")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    /** Register a POST-only endpoint with standard error handling. */
    private fun HttpServer.postContext(
        path: String,
        handler: (HttpExchange) -> Unit,
    ) {
        createContext(path) { ex ->
            try {
                if (ex.requestMethod != "POST") {
                    ex.sendResponseHeaders(405, -1)
                    ex.close()
                    return@createContext
                }
                handler(ex)
            } catch (t: Throwable) {
                log.error("{} error: {}", path, t.message, t)
                try {
                    respond(ex, 500, "text/plain", "Error: ${t.message}")
                } catch (_: Throwable) {
                    try {
                        ex.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    // --- Engine recommendation ---

    /**
     * `GET /api/best-play` — asks the engine simulation what the best play is
     * for the current board state. Uses [SpellAbilityPicker], which runs
     * Monte Carlo simulations to evaluate legal plays.
     */
    private fun serveBestPlay(ex: HttpExchange) {
        val session = sessionProvider?.invoke()
        if (session == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no active session"}""")
            return
        }

        try {
            val response =
                session.withSessionAuthority {
                    val bridge = session.gameBridge
                    val game =
                        bridge.getGame()
                            ?: return@withSessionAuthority BestPlayResponse(
                                bestPlay = null,
                                phase = null,
                                reason = "no game",
                            )
                    val player =
                        bridge.getPlayer(session.seatId)
                            ?: return@withSessionAuthority BestPlayResponse(
                                bestPlay = null,
                                phase = null,
                                reason = "no player for seat ${session.seatId.value}",
                            )
                    val phaseHandler = game.phaseHandler
                    val phase = phaseHandler.phase?.toString()
                    val turn = phaseHandler.turn
                    if (phase == null) {
                        return@withSessionAuthority BestPlayResponse(
                            bestPlay = null,
                            phase = null,
                            turn = turn,
                            reason = "phase unavailable",
                        )
                    }

                    val picker = SpellAbilityPicker(game, player)
                    val bestSa = picker.chooseSpellAbilityToPlay(null)
                    val score = picker.getScoreForChosenAbility()
                    if (bestSa == null) {
                        return@withSessionAuthority BestPlayResponse(
                            bestPlay = null,
                            phase = phase,
                            turn = turn,
                            reason = "no beneficial play",
                        )
                    }

                    val card = bestSa.hostCard
                    val forgeCardId = card?.id ?: -1
                    val arenaInstanceId =
                        try {
                            bridge.getOrAllocInstanceId(ForgeCardId(forgeCardId)).value
                        } catch (_: Exception) {
                            -1
                        }
                    val actionType =
                        when {
                            card?.isLand == true -> "PlayLand"
                            bestSa.isSpell -> "CastSpell"
                            bestSa.isActivatedAbility -> "ActivateAbility"
                            else -> "Unknown"
                        }

                    BestPlayResponse(
                        bestPlay =
                            BestPlayEntry(
                                cardName = card?.name ?: "unknown",
                                forgeCardId = forgeCardId,
                                arenaInstanceId = arenaInstanceId,
                                actionType = actionType,
                                score = score.value,
                                description = SpellAbilityPicker.abilityToString(bestSa, true),
                                targets = buildBestPlayTargets(bestSa, bridge),
                            ),
                        phase = phase,
                        turn = turn,
                    )
                }
            respondJson(
                ex,
                json.encodeToString(
                    response,
                ),
            )
        } catch (t: Throwable) {
            log.warn("best-play simulation failed: {}", t.message, t)
            respondJson(ex, """{"bestPlay":null,"reason":"simulation error: ${t.message?.replace("\"", "'")}"}""")
        }
    }

    private fun buildBestPlayTargets(
        bestSa: forge.game.spellability.SpellAbility,
        bridge: GameBridge,
    ): List<BestPlayTargetEntry> {
        val result = mutableListOf<BestPlayTargetEntry>()
        var sa: forge.game.spellability.SpellAbility? = bestSa
        while (sa != null) {
            if (sa.usesTargeting()) {
                for (target in sa.targets) {
                    when (target) {
                        is forge.game.card.Card ->
                            result.add(
                                BestPlayTargetEntry(
                                    kind = "card",
                                    name = target.name,
                                    forgeCardId = target.id,
                                    arenaInstanceId = bridge.getOrAllocInstanceId(ForgeCardId(target.id)).value,
                                    seatId = null,
                                ),
                            )
                        is forge.game.player.Player -> {
                            val seatId =
                                when (target) {
                                    bridge.getPlayer(SeatId(1)) -> 1
                                    bridge.getPlayer(SeatId(2)) -> 2
                                    else -> null
                                }
                            result.add(
                                BestPlayTargetEntry(
                                    kind = "player",
                                    name = target.name,
                                    forgeCardId = null,
                                    arenaInstanceId = null,
                                    seatId = seatId,
                                ),
                            )
                        }
                    }
                }
            }
            sa = sa.subAbility
        }
        return result
    }

    @Serializable
    private data class BestPlayEntry(
        val cardName: String,
        val forgeCardId: Int,
        val arenaInstanceId: Int,
        val actionType: String,
        val score: Int,
        val description: String,
        val targets: List<BestPlayTargetEntry> = emptyList(),
    )

    @Serializable
    private data class BestPlayTargetEntry(
        val kind: String,
        val name: String,
        val forgeCardId: Int? = null,
        val arenaInstanceId: Int? = null,
        val seatId: Int? = null,
    )

    @Serializable
    private data class BestPlayResponse(
        val bestPlay: BestPlayEntry?,
        val phase: String?,
        val turn: Int = 0,
        val reason: String? = null,
    )

    // --- Push full state update ---

    /**
     * POST `/api/inject-full` — rebuild current engine state as a full update and
     * send it to the connected client. Tests whether the client accepts a
     * mid-game full-state replacement without glitching.
     */
    private fun serveInjectFull(ex: HttpExchange) {
        val session = sessionProvider?.invoke()
        if (session == null) {
            respond(ex, 404, "text/plain", "No active session")
            return
        }
        val bridge = session.gameBridge

        val info =
            session.withSessionAuthority {
                val game = bridge.getGame() ?: return@withSessionAuthority null
                val counter = session.counter
                val gsId = counter.nextGsId()
                val msgId = counter.nextMsgId()

                val snap = SnapshotCapture.run(game, bridge, session.matchId, gsId)
                val fullGsm =
                    StateMapper
                        .buildFromSnapshot(
                            snap,
                            gsId,
                            session.matchId,
                            bridge,
                            updateType = GameStateUpdate.SendAndRecord,
                            viewingSeatId = session.seatId.value,
                        ).gsm

                val actions = ActionMapper.buildFromSnapshot(session.seatId.value, snap, bridge)
                val fullGsmWithActions =
                    GsmBuilder.embedActions(fullGsm, actions, GsmFrame.from(snap), recipientSeatId = session.seatId.value)

                val greGsm =
                    GREToClientMessage
                        .newBuilder()
                        .setType(GREMessageType.GameStateMessage_695e)
                        .setMsgId(msgId)
                        .setGameStateId(gsId)
                        .addSystemSeatIds(session.seatId.value)
                        .setGameStateMessage(fullGsmWithActions)
                        .build()

                val greActions =
                    GREToClientMessage
                        .newBuilder()
                        .setType(GREMessageType.ActionsAvailableReq_695e)
                        .setMsgId(counter.nextMsgId())
                        .setGameStateId(gsId)
                        .addSystemSeatIds(session.seatId.value)
                        .setActionsAvailableReq(actions)
                        .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                        .build()

                session.sendBundledGRE(listOf(greGsm, greActions))
                bridge.bundleCursor.lastSent = snap

                "Pushed full state gsId=$gsId objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}"
            }
        if (info == null) {
            respond(ex, 404, "text/plain", "No game")
            return
        }
        log.info(info)
        respond(ex, 200, "text/plain", info)
    }

    // --- Puzzle ---

    private fun serveGetPuzzle(ex: HttpExchange) {
        val current = runtimePuzzle?.get()
        respondJson(ex, """{"puzzle":${if (current != null) "\"$current\"" else "null"}}""")
    }

    private fun servePuzzle(ex: HttpExchange) {
        val body =
            ex.requestBody
                .bufferedReader()
                .readText()
                .trim()
        val fileParam =
            ex.requestURI.query
                ?.split("&")
                ?.associate { it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") } }
                ?.get("file")

        if (fileParam == null && body.isEmpty()) {
            runtimePuzzle?.set(null)
            respond(ex, 200, "text/plain", "Puzzle cleared")
            return
        }

        val puzzlePath =
            if (fileParam != null) {
                resolvePuzzleFile(fileParam) ?: run {
                    respond(
                        ex,
                        404,
                        "text/plain",
                        "Puzzle not found: $fileParam (checked engine test resources, root puzzles/, and classpath)",
                    )
                    return
                }
            } else {
                null
            }

        if (puzzlePath != null) {
            runtimePuzzle?.set(puzzlePath)
        }

        val session = sessionProvider?.invoke()
        val bridge = session?.gameBridge

        if (session != null && bridge != null) {
            val label =
                session.withSessionAuthority {
                    hotSwapPuzzle(session, bridge, body, fileParam, puzzlePath, ex)
                } ?: return
            respond(ex, 200, "text/plain", label)
        } else {
            if (fileParam != null) {
                respond(ex, 200, "text/plain", "Puzzle set: $fileParam (will activate on next local AI match)")
            } else {
                respond(ex, 400, "text/plain", "No active session to inject body puzzle into")
            }
        }
    }

    /** Hot-swap puzzle into active session. Returns label string on success, null if error was sent. */
    private fun hotSwapPuzzle(
        session: MatchSession,
        bridge: GameBridge,
        body: String,
        fileParam: String?,
        puzzlePath: String?,
        ex: HttpExchange,
    ): String? {
        GameBootstrap.initializeLocalization()

        val puzzle =
            when {
                body.isNotEmpty() -> PuzzleSource.loadFromText(body, "injected")
                puzzlePath != null -> PuzzleSource.loadFromFile(puzzlePath)
                else -> {
                    respond(ex, 400, "text/plain", "Unexpected state")
                    return null
                }
            }

        val (newSession, deletedIds) = session.replaceForPuzzle { it.resetForPuzzle(puzzle) }
        bridge.awaitPriority()
        val actionBridge = newSession.gameBridge.seat(newSession.seatId).action
        val pending = checkNotNull(actionBridge.getPending()) { "Puzzle hot-swap has no pending priority window" }

        val counter = newSession.counter
        val gsId = counter.nextGsId()
        val msgId = counter.nextMsgId()

        val game = bridge.getGame()!!
        val snap = SnapshotCapture.run(game, bridge, newSession.matchId, gsId)
        val fullGsm =
            StateMapper
                .buildFromSnapshot(
                    snap,
                    gsId,
                    newSession.matchId,
                    bridge,
                    updateType = GameStateUpdate.SendAndRecord,
                    viewingSeatId = newSession.seatId.value,
                ).gsm

        val projection = ActionMapper.buildProjectionFromSnapshot(newSession.seatId.value, snap, bridge)
        val actions = projection.actions
        val fullGsmWithActions =
            GsmBuilder.embedActions(fullGsm, actions, GsmFrame.from(snap), recipientSeatId = newSession.seatId.value)

        val gsmWithDeletes =
            if (deletedIds.isNotEmpty()) {
                fullGsmWithActions.toBuilder().addAllDiffDeletedInstanceIds(deletedIds).build()
            } else {
                fullGsmWithActions
            }

        val greGsm =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(newSession.seatId.value)
                .setGameStateMessage(gsmWithDeletes)
                .build()

        val greActions =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .setMsgId(counter.nextMsgId())
                .setGameStateId(gsId)
                .addSystemSeatIds(newSession.seatId.value)
                .setActionsAvailableReq(actions)
                .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                .build()

        check(actionBridge.bindActionCatalog(pending.actionId, gsId, projection.offers)) {
            "Puzzle hot-swap could not bind priority actions"
        }

        newSession.sendBundledGRE(listOf(greGsm, greActions))
        bridge.bundleCursor.lastSent = snap
        val advanced = BundleBuilder.shouldAutoPass(actions)
        if (advanced) {
            newSession.triggerAutoPass()
        }
        val advancedSuffix = if (advanced) " + advanced" else ""

        return if (fileParam != null) {
            "Puzzle '$fileParam' set + injected gsId=$gsId " +
                "objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}$advancedSuffix"
                    .also { log.info(it) }
        } else {
            val meta = PuzzleSource.parseMetadata(body)
            "Injected puzzle '${meta.name}' gsId=$gsId " +
                "objects=${fullGsm.gameObjectsCount} zones=${fullGsm.zonesCount}$advancedSuffix"
                    .also { log.info(it) }
        }
    }

    /** Resolve a puzzle file name to an absolute path. Checks test resources, root puzzles/, then classpath. */
    private fun resolvePuzzleFile(name: String): String? {
        val testRes = File("engine/src/test/resources/puzzles", "$name.pzl")
        if (testRes.exists()) return testRes.absolutePath

        val rootPuzzles = File("puzzles", "$name.pzl")
        if (rootPuzzles.exists()) return rootPuzzles.absolutePath

        val resource = javaClass.classLoader.getResource("puzzles/$name.pzl")
        if (resource != null && resource.protocol == "file") return File(resource.toURI()).absolutePath

        return null
    }

    // --- Helpers ---

    private fun safe(
        ex: HttpExchange,
        block: () -> Unit,
    ) {
        try {
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, -1)
                ex.close()
                return
            }
            block()
        } catch (t: Throwable) {
            log.error("Debug server error on {}: {}", ex.requestURI, t.message, t)
            try {
                val trace = t.stackTrace.take(5).joinToString("\n  ") { it.toString() }
                respond(ex, 500, "text/plain", "Internal error [${t.javaClass.name}]: ${t.message}\n  $trace")
            } catch (_: Throwable) {
                try {
                    ex.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun respond(
        ex: HttpExchange,
        code: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun respondJson(
        ex: HttpExchange,
        body: String,
    ) = respond(ex, 200, "application/json; charset=utf-8", body)
}
