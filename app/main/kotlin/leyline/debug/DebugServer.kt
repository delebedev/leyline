package leyline.debug

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import forge.ai.simulation.SpellAbilityPicker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.config.PuzzleDefinition
import leyline.copilot.CopilotProposalService
import leyline.domain.json.productionJson
import leyline.game.bundle.BundleBuilder
import leyline.game.generator.PuzzleLibrary
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.PromptIds
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
 * - `GET /api/copilot-proposal` → local decision view for the pending prompt
 * - `POST /api/copilot-consult` → stateless decision view for supplied state
 * - `POST /api/inject-full` → rebuild and deliver a full state update
 * - `GET /api/puzzle`       → current puzzle state
 * - `POST /api/puzzle`      → set/clear/hot-swap puzzle
 *
 * The native head supplies its listener bind address.
 */
@Suppress("LargeClass") // Debug routes share the same local server and session providers.
class DebugServer(
    private val port: Int = 8090,
    /** Bind address for local controls (loopback by default). */
    private val bindAddress: String = "127.0.0.1",
    private val sessionProvider: (() -> MatchSession?)? = null,
    /** Runtime puzzle holder — set/cleared by POST /api/puzzle. */
    private val runtimePuzzle: AtomicReference<String?>? = null,
    private val puzzleLibrary: PuzzleLibrary = PuzzleLibrary(File("data/puzzles")),
    /** Card repository for session-less consults (`POST /api/copilot-consult`). */
    private val cardRepositoryProvider: (() -> leyline.game.data.CardRepository)? = null,
    /** One-shot seat-2 (AI) deck override by name — set via POST /api/ai-deck, consumed per match. */
    private val aiDeckOverride: AtomicReference<String?>? = null,
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
            "/api/copilot-proposal" to ::serveCopilotProposal,
        ).forEach { (path, handler) ->
            srv.createContext(path) { ex -> safe(ex) { handler(ex) } }
        }

        srv.postContext("/api/inject-full", ::serveInjectFull)
        srv.postContext("/api/copilot-consult", ::serveCopilotConsult)
        srv.createContext("/api/ai-deck") { ex ->
            try {
                when (ex.requestMethod) {
                    "GET" -> respondJson(ex, json.encodeToString(AiDeckResponse(aiDeckOverride?.get())))
                    "POST" -> serveSetAiDeck(ex)
                    else -> {
                        ex.sendResponseHeaders(405, -1)
                        ex.close()
                    }
                }
            } catch (t: Throwable) {
                log.error("/api/ai-deck error: {}", t.message, t)
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

    private fun resolveBindAddress(): String = bindAddress

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
        val bridge = session.gameBridge
        val game = bridge.getGame()
        if (game == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no game"}""")
            return
        }
        val player = bridge.getPlayer(session.seatId)
        if (player == null) {
            respondJson(ex, """{"bestPlay":null,"reason":"no player for seat ${session.seatId.value}"}""")
            return
        }

        try {
            val phaseHandler = game.phaseHandler
            val phase = phaseHandler.phase?.toString()
            val turn = phaseHandler.turn
            if (phase == null) {
                respondJson(ex, """{"bestPlay":null,"phase":null,"turn":$turn,"reason":"phase unavailable"}""")
                return
            }

            val picker = SpellAbilityPicker(player)
            val bestSa = picker.chooseSpellAbilityToPlay(null)
            val score = picker.getScoreForChosenAbility()

            if (bestSa == null) {
                respondJson(ex, """{"bestPlay":null,"phase":"$phase","turn":$turn,"reason":"no beneficial play"}""")
                return
            }

            val card = bestSa.hostCard
            val cardName = card?.name ?: "unknown"
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

            val saDesc = SpellAbilityPicker.abilityToString(bestSa, true)
            val targets = buildBestPlayTargets(bestSa, bridge)

            respondJson(
                ex,
                json.encodeToString(
                    BestPlayResponse(
                        bestPlay =
                            BestPlayEntry(
                                cardName = cardName,
                                forgeCardId = forgeCardId,
                                arenaInstanceId = arenaInstanceId,
                                actionType = actionType,
                                score = score.value,
                                description = saDesc,
                                targets = targets,
                            ),
                        phase = phase,
                        turn = turn,
                        reason = null,
                    ),
                ),
            )
        } catch (t: Throwable) {
            log.warn("best-play simulation failed: {}", t.message, t)
            respondJson(ex, """{"bestPlay":null,"reason":"simulation error: ${t.message?.replace("\"", "'")}"}""")
        }
    }

    // --- Copilot proposal ---

    /**
     * `GET /api/copilot-proposal` returns a read-only proposal for the current prompt.
     */
    private fun serveCopilotProposal(ex: HttpExchange) {
        val session = sessionProvider?.invoke()
        if (session == null) {
            respondJson(ex, """{"intent":"unrealizable","reason":"no active session"}""")
            return
        }
        val service = CopilotProposalService(session.gameBridge, session.seatId)
        val proposal = service.propose(session.lastPromptMessage())
        respondJson(ex, json.encodeToString(proposal))
    }

    /**
     * `POST /api/copilot-consult` consults supplied state and returns `{proposal, eval}`.
     */
    private fun serveCopilotConsult(ex: HttpExchange) {
        val repo = cardRepositoryProvider?.invoke()
        if (repo == null) {
            respond(ex, 503, "application/json; charset=utf-8", """{"error":"no card repository configured"}""")
            return
        }
        val root =
            Json
                .parseToJsonElement(ex.requestBody.readBytes().decodeToString())
                .let { it as? kotlinx.serialization.json.JsonObject }
        val gameStateJson = root?.get("gameState")
        if (root == null || gameStateJson == null) {
            respond(ex, 400, "application/json; charset=utf-8", """{"error":"body must be an object with a gameState field"}""")
            return
        }
        val seat =
            (root["seat"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?.toIntOrNull() ?: 1
        // Normalize alternate enum spellings before protobuf JSON parsing.
        val parser =
            com.google.protobuf.util.JsonFormat
                .parser()
                .ignoringUnknownFields()

        fun protoJson(
            element: kotlinx.serialization.json.JsonElement,
            descriptor: com.google.protobuf.Descriptors.Descriptor,
        ): String =
            leyline.protocol.PlayerLogEnumJson
                .toGenerated(element, descriptor)
                .toString()
        val gsm =
            GameStateMessage
                .newBuilder()
                .also { parser.merge(protoJson(gameStateJson, GameStateMessage.getDescriptor()), it) }
                .build()
        val prompt =
            root["prompt"]?.let { promptJson ->
                GREToClientMessage
                    .newBuilder()
                    .also { parser.merge(protoJson(promptJson, GREToClientMessage.getDescriptor()), it) }
                    .build()
            }
        val result = leyline.copilot.SnapshotConsult.consult(gsm, prompt, seat, repo)
        respondJson(ex, json.encodeToString(result))
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
        val game = bridge.getGame()
        if (game == null) {
            respond(ex, 404, "text/plain", "No game")
            return
        }

        val counter = session.counter
        val gsId = counter.nextGsId()
        val msgId = counter.nextMsgId()

        val full = BundleBuilder(bridge, session.matchId, session.seatId.value).fullState(game, gsId)

        val greGsm =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(session.seatId.value)
                .setGameStateMessage(full.gsm)
                .build()

        val greActions =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .setMsgId(counter.nextMsgId())
                .setGameStateId(gsId)
                .addSystemSeatIds(session.seatId.value)
                .setActionsAvailableReq(full.actions)
                .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                .build()

        session.sendBundledGRE(listOf(greGsm, greActions))
        val info = "Pushed full state gsId=$gsId objects=${full.gsm.gameObjectsCount} zones=${full.gsm.zonesCount}"
        log.info(info)
        respond(ex, 200, "text/plain", info)
    }

    // --- Puzzle ---

    private fun serveGetPuzzle(ex: HttpExchange) {
        val current = runtimePuzzle?.get()
        respondJson(ex, """{"puzzle":${if (current != null) "\"$current\"" else "null"}}""")
    }

    /**
     * Set (or clear) the one-shot seat-2 AI deck override. Body = deck name as
     * plain text; empty body clears. Consumed at the next match's seat-2
     * resolution. Enables the endless self-play loop to randomize the opponent
     * per match with no server restart.
     */
    private fun serveSetAiDeck(ex: HttpExchange) {
        val name =
            ex.requestBody
                .bufferedReader()
                .readText()
                .trim()
        if (name.isEmpty()) {
            aiDeckOverride?.set(null)
            respondJson(ex, json.encodeToString(AiDeckSetResponse(aiDeck = null, cleared = true)))
            return
        }
        aiDeckOverride?.set(name)
        log.info("AI-deck override set: {}", name)
        respondJson(ex, json.encodeToString(AiDeckResponse(name)))
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

        val puzzleDefinition =
            if (fileParam != null) {
                puzzleLibrary.find(fileParam) ?: run {
                    respond(
                        ex,
                        404,
                        "text/plain",
                        "Puzzle not found: $fileParam in the configured puzzle library",
                    )
                    return
                }
            } else {
                null
            }

        if (puzzleDefinition != null) {
            runtimePuzzle?.set(puzzleDefinition.identity)
        }

        val session = sessionProvider?.invoke()
        val bridge = session?.gameBridge

        if (session != null && bridge != null) {
            val label = hotSwapPuzzle(session, bridge, body, fileParam, puzzleDefinition, ex) ?: return
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
        puzzleDefinition: PuzzleDefinition?,
        ex: HttpExchange,
    ): String? {
        val puzzle =
            when {
                body.isNotEmpty() -> PuzzleSource.loadFromText(body, "injected")
                puzzleDefinition != null -> PuzzleSource.load(puzzleDefinition)
                else -> {
                    respond(ex, 400, "text/plain", "Unexpected state")
                    return null
                }
            }

        val (newSession, deletedIds) = session.replaceForPuzzle(puzzle)
        bridge.awaitPriority()
        val actionBridge = newSession.gameBridge.seat(newSession.seatId).action
        val pending = checkNotNull(actionBridge.getPending()) { "Puzzle hot-swap has no pending priority window" }

        val counter = newSession.counter
        val gsId = counter.nextGsId()
        val msgId = counter.nextMsgId()

        val game = bridge.getGame()!!
        val full = BundleBuilder(bridge, newSession.matchId, newSession.seatId.value).fullState(game, gsId)
        val actions = bridge.bindInitialActionWindow(pending.actionId, gsId)

        val gsmWithDeletes =
            if (deletedIds.isNotEmpty()) {
                full.gsm
                    .toBuilder()
                    .addAllDiffDeletedInstanceIds(deletedIds)
                    .build()
            } else {
                full.gsm
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

        newSession.sendBundledGRE(listOf(greGsm, greActions))
        newSession.registry.getConnection(newSession.matchId, newSession.seatId)?.armRuntimeDeliveryObserver()

        return if (fileParam != null) {
            "Puzzle '$fileParam' set + injected gsId=$gsId " +
                "objects=${full.gsm.gameObjectsCount} zones=${full.gsm.zonesCount}"
                    .also { log.info(it) }
        } else {
            val meta = PuzzleSource.parseMetadata(body)
            "Injected puzzle '${meta.name}' gsId=$gsId " +
                "objects=${full.gsm.gameObjectsCount} zones=${full.gsm.zonesCount}"
                    .also { log.info(it) }
        }
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

    @Serializable
    private data class AiDeckResponse(
        val aiDeck: String?,
    )

    @Serializable
    private data class AiDeckSetResponse(
        val aiDeck: String?,
        val cleared: Boolean,
    )
}
