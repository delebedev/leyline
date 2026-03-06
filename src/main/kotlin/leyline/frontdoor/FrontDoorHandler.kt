package leyline.frontdoor

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import leyline.debug.FdDebugCollector
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.MatchInfo
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import leyline.frontdoor.service.CollectionService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.EventRegistry
import leyline.frontdoor.service.LobbyStubs
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.DeckWireBuilder
import leyline.frontdoor.wire.EventWireBuilder
import leyline.frontdoor.wire.FdResponseWriter
import leyline.frontdoor.wire.PlayerWireBuilder
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.FdEnvelope
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Front Door handler (port 30010) — fully offline, no proxy.
 *
 * Dispatches by CmdType to layered services ([DeckService], [PlayerService],
 * [MatchmakingService]) with [LobbyStubs] for unimplemented endpoints.
 *
 * Static protocol data (formats, sets, graph defs) comes from [GoldenData].
 * Wire serialization is handled by [DeckWireBuilder] / [PlayerWireBuilder]
 * and responses go through [FdResponseWriter].
 */
class FrontDoorHandler(
    private val playerId: PlayerId?,
    private val deckService: DeckService,
    private val playerService: PlayerService,
    private val matchmaking: MatchmakingService,
    private val collectionService: CollectionService,
    private val writer: FdResponseWriter,
    private val golden: GoldenData,
    private val fdCollector: FdDebugCollector? = null,
    /** Called when client sends 612 with a deckId — writes to shared holder. */
    private val onDeckSelected: ((String) -> Unit)? = null,
    /** Called when client sends 612 with an eventName — writes to shared holder. */
    private val onEventSelected: ((String) -> Unit)? = null,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(FrontDoorHandler::class.java)

    /** Deck selected via 622 (Event_SetDeckV2), keyed by eventName. Consumed by 603. */
    private val selectedDeckByEvent = mutableMapOf<String, String>()

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        log.info(
            "Front Door stub: loaded golden data — formats={}B sets={}B startHook={}B",
            golden.getFormatsProto.size,
            golden.getSetsProto.size,
            golden.startHookJson.length,
        )
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Front Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ReferenceCountUtil.release(msg)
            return
        }
        try {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)

            if (bytes.size < ClientFrameDecoder.HEADER_SIZE) return

            val frameType = bytes[1]
            log.debug("Front Door: frame type=0x{} size={}", String.format("%02x", frameType), bytes.size)

            // Control frames
            if (frameType == ClientFrameDecoder.TYPE_CTRL_INIT) {
                log.debug("Front Door: CTRL_INIT received, sending ACK")
                writer.sendCtrlAck(ctx, bytes)
                return
            }
            if (frameType == ClientFrameDecoder.TYPE_CTRL_ACK) {
                log.debug("Front Door: CTRL_ACK received (ignored)")
                return
            }

            val payload = if (bytes.size > ClientFrameDecoder.HEADER_SIZE) {
                bytes.copyOfRange(ClientFrameDecoder.HEADER_SIZE, bytes.size)
            } else {
                null
            }

            if (payload == null) {
                log.debug("Front Door: header-only message (ack/heartbeat)")
                return
            }

            val decoded = try {
                FdEnvelope.decode(payload)
            } catch (e: Exception) {
                log.error("Front Door: envelope decode FAILED ({}B payload): {}", payload.size, e.message)
                writer.sendEmpty(ctx, null)
                return
            }
            val json = decoded.jsonPayload
            val transactionId = decoded.transactionId
            val cmdType = decoded.cmdType
            val cmdName = cmdType?.let { FdEnvelope.cmdTypeName(it) } ?: "unknown"

            fdCollector?.record("C2S", decoded)
            log.debug("Front Door: cmd={} cmdType={} txId={}", cmdName, cmdType, transactionId)

            dispatch(ctx, cmdType, transactionId, json)
        } finally {
            msg.release()
        }
    }

    private fun dispatch(ctx: ChannelHandlerContext, cmdType: Int?, txId: String?, json: String?) {
        when (cmdType) {
            // --- Auth ---
            0 -> {
                log.info("Front Door: auth → session")
                val pid = playerId
                if (pid != null) {
                    val session = playerService.authenticate(pid, "Player")
                    writer.sendJson(ctx, txId, """{"SessionId":"${session.value}","Attached":true}""")
                } else {
                    val sessionId = UUID.randomUUID().toString()
                    writer.sendJson(ctx, txId, """{"SessionId":"$sessionId","Attached":true}""")
                }
            }

            // --- Startup essentials (golden data) ---
            1 -> { // StartHook
                val hook = buildStartHook()
                log.info("Front Door: StartHook ({}B)", hook.length)
                writer.sendJson(ctx, txId, hook)
            }

            1700 -> { // Graph_GetGraphDefinitions
                log.info("Front Door: GraphDefs (golden, {}B)", golden.graphDefinitionsJson.length)
                writer.sendJson(ctx, txId, golden.graphDefinitionsJson)
            }

            1701 -> { // Graph_GetGraphState
                handleGraphRequest(ctx, txId, json)
            }

            2400 -> { // GetDesignerMetadata
                log.info("Front Door: DesignerMetadata (golden)")
                writer.sendJson(ctx, txId, golden.designerMetadataJson)
            }

            // --- Protobuf responses (golden .bin files) ---
            6 -> { // GetFormats
                log.info("Front Door: GetFormats (golden proto, {}B)", golden.getFormatsProto.size)
                writer.sendRawProto(ctx, txId, golden.getFormatsProto)
            }

            1521 -> { // GetSets
                log.info("Front Door: GetSets (golden proto, {}B)", golden.getSetsProto.size)
                writer.sendRawProto(ctx, txId, golden.getSetsProto)
            }

            // --- Match trigger ---
            612 -> { // Event_AiBotMatch
                val deckId = extractDeckId(json)
                val eventName = extractEventName(json) ?: "AIBotMatch"
                if (deckId != null) onDeckSelected?.invoke(deckId)
                onEventSelected?.invoke(eventName)
                val pid = playerId ?: PlayerId("anonymous")
                val match = matchmaking.startAiMatch(pid, DeckId(deckId ?: ""), eventName)
                log.info("Front Door: Event_AiBotMatch deckId={} event={} → ack + pushing MatchCreated", deckId, eventName)
                writer.sendEmpty(ctx, txId)
                sendMatchCreated(ctx, match)
            }

            // --- Play blade data ---
            1910 -> { // GetPlayBladeQueueConfig
                val configJson = EventWireBuilder.toQueueConfigJson(EventRegistry.queues)
                log.info("Front Door: PlayBladeQueueConfig ({} queues)", EventRegistry.queues.size)
                writer.sendJson(ctx, txId, configJson)
            }

            624 -> { // Event_GetActiveEventsV2
                val eventsJson = EventWireBuilder.toActiveEventsJson(EventRegistry.events)
                log.info("Front Door: ActiveEventsV2 ({} events)", EventRegistry.events.size)
                writer.sendJson(ctx, txId, eventsJson)
            }

            623 -> { // EventGetCoursesV2
                val coursesJson = EventWireBuilder.toCoursesJson(EventRegistry.defaultCourses)
                log.debug("Front Door: EventGetCoursesV2")
                writer.sendJson(ctx, txId, coursesJson)
            }

            // --- Player data (from services) ---

            1911 -> { // GetPlayerPreferences
                val pid = playerId
                val prefs = pid?.let { playerService.getPreferences(it) }
                val raw = prefs?.json?.takeIf { it != "{}" }
                log.info("Front Door: PlayerPreferences ({})", if (raw != null) "db" else "empty")
                writer.sendJson(ctx, txId, raw ?: """{"Preferences":{}}""")
            }

            1912 -> { // SetPlayerPreferences
                requireJson(ctx, txId, json) { body ->
                    val pid = playerId
                    if (pid != null) {
                        val cleaned = PlayerWireBuilder.parsePreferences(body)
                        playerService.savePreferences(pid, Preferences(cleaned))
                        log.info("Front Door: SetPlayerPreferences saved")
                    }
                    writer.sendJson(ctx, txId, """{}""")
                }
            }

            403 -> { // Deck_DeleteDeck
                requireJson(ctx, txId, json) { body ->
                    val deckId = try {
                        lenientJson.parseToJsonElement(body).jsonObject["DeckId"]?.jsonPrimitive?.content
                    } catch (_: Exception) {
                        null
                    }
                    if (deckId != null) {
                        deckService.delete(DeckId(deckId))
                        log.info("Front Door: Deck_DeleteDeck '{}'", deckId)
                    }
                    writer.sendJson(ctx, txId, "{}")
                }
            }

            406 -> { // Deck_UpsertDeckV2
                requireJson(ctx, txId, json) { body ->
                    val pid = playerId
                    if (pid != null) {
                        val deck = DeckWireBuilder.parseDeckUpdate(body, pid)
                        if (deck != null) {
                            deckService.save(deck)
                            log.info("Front Door: Deck_UpsertDeckV2 saved '{}'", deck.name)
                        } else {
                            log.warn("Front Door: Deck_UpsertDeckV2 parse failed")
                        }
                    }
                    // Echo back the Summary from the request
                    val summary = try {
                        val obj = lenientJson.parseToJsonElement(body).jsonObject
                        obj["Summary"]?.let { lenientJson.encodeToString(JsonObject.serializer(), it.jsonObject) }
                    } catch (_: Exception) {
                        null
                    }
                    writer.sendJson(ctx, txId, summary ?: "{}")
                }
            }

            407 -> { // Deck_GetDeckSummariesV2 — response key is "Summaries" (NOT DeckSummariesV2)
                val pid = playerId
                if (pid != null) {
                    val decks = deckService.listForPlayer(pid)
                    val summaries = buildJsonArray { decks.forEach { add(DeckWireBuilder.toV2Summary(it)) } }
                    log.info("Front Door: DeckSummariesV2 ({} decks)", decks.size)
                    val resp = buildJsonObject { put("Summaries", summaries) }
                    writer.sendJson(ctx, txId, lenientJson.encodeToString(JsonObject.serializer(), resp))
                } else {
                    writer.sendJson(ctx, txId, """{"Summaries":[]}""")
                }
            }

            410 -> { // Deck_GetAllPreconDecksV3
                log.info("Front Door: PreconDecksV3")
                writer.sendJson(ctx, txId, golden.preconDecksJson)
            }

            // --- Lobby requests: minimal valid responses ---
            613 -> writer.sendJson(ctx, txId, LobbyStubs.activeMatches())
            1520 -> writer.sendProto(ctx, txId, "Wizards.Arena.Models.Network.GetVoucherDefinitionsResponse")
            704 -> { // Carousel_GetCarouselItems
                log.info("Front Door: CarouselItems")
                writer.sendJson(ctx, txId, golden.carouselJson)
            }
            2600 -> writer.sendJson(ctx, txId, LobbyStubs.preferredPrintings())
            2700 -> writer.sendJson(ctx, txId, LobbyStubs.prizeWalls())
            1100 -> writer.sendJson(ctx, txId, LobbyStubs.rankInfo())
            1000 -> writer.sendJson(ctx, txId, LobbyStubs.quests())
            1200, 1201 -> writer.sendJson(ctx, txId, LobbyStubs.periodicRewards())
            800 -> writer.sendJson(ctx, txId, LobbyStubs.currencies())
            901 -> writer.sendJson(ctx, txId, LobbyStubs.boosters())
            1900 -> writer.sendJson(ctx, txId, LobbyStubs.cosmetics())
            2200 -> writer.sendJson(ctx, txId, LobbyStubs.netDeckFolders())
            2300 -> writer.sendJson(ctx, txId, LobbyStubs.playerInbox())
            2500 -> writer.sendJson(ctx, txId, LobbyStubs.staticContent())
            551 -> { // Card_GetAllCards
                val collection = collectionService.getCollection(playerId)
                log.info("Front Door: CardGetAllCards ({} cards)", collection.size)
                writer.sendJson(ctx, txId, collectionService.toJson(collection))
            }
            708, 712, 715 -> writer.sendJson(ctx, txId, LobbyStubs.storeStatus())
            1102 -> writer.sendJson(ctx, txId, LobbyStubs.rankSeasonDetails())
            1105 -> writer.sendJson(ctx, txId, LobbyStubs.rankSeasonDetails())
            3006 -> writer.sendProto(ctx, txId, "Wizards.Arena.Models.Network.ChallengeReconnectAllResp")

            // --- Telemetry ack ---
            1913, 1914 -> {
                log.debug("Front Door: telemetry (CmdType {})", cmdType)
                writer.sendJson(ctx, txId, LobbyStubs.telemetryAck())
            }

            // --- Event flow ---
            600 -> { // Event_Join
                val eventName = extractEventName(json)
                log.info("Front Door: Event_Join event={} (golden)", eventName)
                writer.sendJson(ctx, txId, golden.eventJoinJson)
            }

            601 -> { // Event_Drop
                val eventName = extractEventName(json)
                log.info("Front Door: Event_Drop event={}", eventName)
                writer.sendJson(ctx, txId, "{}")
            }

            603 -> { // Event_EnterPairing
                val eventName = extractEventName(json)
                val deckId = eventName?.let { selectedDeckByEvent[it] }
                    ?: extractDeckId(json)
                log.info("Front Door: Event_EnterPairing event={} deck={}", eventName, deckId)

                val pid = playerId ?: PlayerId("anonymous")
                try {
                    if (eventName != null) onEventSelected?.invoke(eventName)
                    if (deckId != null) onDeckSelected?.invoke(deckId)
                    val match = matchmaking.startMatch(pid, DeckId(deckId ?: ""), eventName ?: "")
                    writer.sendJson(ctx, txId, """{"CurrentModule":"CreateMatch","Payload":"Success"}""")
                    sendMatchCreated(ctx, match)
                } catch (e: IllegalArgumentException) {
                    log.warn("Front Door: Event_EnterPairing rejected — {}", e.message)
                    writer.sendEmpty(ctx, txId)
                }
            }

            606 -> { // Event_LeavePairing
                val eventName = extractEventName(json)
                log.info("Front Door: Event_LeavePairing event={}", eventName)
                writer.sendEmpty(ctx, txId)
            }

            608 -> { // Event_GetMatchResultReport
                val eventName = extractEventName(json)
                log.info("Front Door: Event_GetMatchResultReport event={}", eventName)
                writer.sendJson(ctx, txId, """{"CurrentModule":"Complete","questUpdates":[]}""")
            }

            622 -> { // Event_SetDeckV2
                val eventName = extractEventName(json)
                val deckId = extractDeckId(json)
                if (eventName != null && deckId != null) {
                    selectedDeckByEvent[eventName] = deckId
                }
                log.info("Front Door: Event_SetDeckV2 event={} deck={}", eventName, deckId)
                writer.sendJson(ctx, txId, golden.eventSetDeckJson)
            }

            // --- Fallback ---
            null -> {
                if (json == null) return
                when {
                    "ClientVersion" in json || "Token" in json -> {
                        log.info("Front Door: auth (fallback)")
                        val sessionId = UUID.randomUUID().toString()
                        writer.sendJson(ctx, txId, """{"SessionId":"$sessionId","Attached":true}""")
                    }
                    "GraphId" in json -> handleGraphRequest(ctx, txId, json)
                    "AIBotMatch" in json || "PlayQueue" in json -> {
                        val pid = playerId ?: PlayerId("anonymous")
                        val match = matchmaking.startAiMatch(pid, DeckId(""))
                        sendMatchCreated(ctx, match)
                    }
                    else -> {
                        log.debug("Front Door: unrecognized (no CmdType): {}", json.take(80))
                        writer.sendEmpty(ctx, txId)
                    }
                }
            }

            else -> {
                log.warn("Front Door: UNHANDLED CmdType {} ({})", cmdType, FdEnvelope.cmdTypeName(cmdType))
                writer.sendEmpty(ctx, txId)
            }
        }
    }

    // --- Helpers ---

    private inline fun requireJson(
        ctx: ChannelHandlerContext,
        txId: String?,
        json: String?,
        block: (String) -> Unit,
    ) {
        if (json == null) {
            log.warn("Front Door: expected JSON payload, got null")
            writer.sendEmpty(ctx, txId)
            return
        }
        block(json)
    }

    private fun buildStartHook(): String {
        val pid = playerId ?: return golden.startHookJson
        val decks = deckService.listForPlayer(pid)
        if (decks.isEmpty()) return golden.startHookJson

        val root = lenientJson.parseToJsonElement(golden.startHookJson).jsonObject
        val summaries = buildJsonArray { decks.forEach { add(DeckWireBuilder.toV2Summary(it)) } }
        val decksMap = buildJsonObject {
            for (d in decks) put(d.id.value, DeckWireBuilder.toStartHookEntry(d))
        }

        val patched = JsonObject(root + ("DeckSummariesV2" to summaries) + ("Decks" to decksMap))
        log.info("StartHook assembled from DB: {} deck(s)", decks.size)
        return lenientJson.encodeToString(JsonObject.serializer(), patched)
    }

    private fun sendMatchCreated(ctx: ChannelHandlerContext, match: MatchInfo) {
        val json = FdEnvelope.buildMatchCreatedJson(match.matchId, match.host, match.port, match.eventName)
        log.info("Front Door: pushing MatchCreated matchId={} event={}", match.matchId, match.eventName)
        val pushTxId = UUID.randomUUID().toString()
        writer.sendJson(ctx, pushTxId, json)
    }

    private fun extractEventName(json: String?): String? =
        json?.let {
            try {
                val obj = lenientJson.parseToJsonElement(it).jsonObject
                obj["EventName"]?.jsonPrimitive?.content
                    ?: obj["eventName"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }

    /** Extract DeckId from 622 body — lives in Summary.DeckId or top-level DeckId. */
    private fun extractDeckId(json: String?): String? =
        json?.let {
            try {
                val obj = lenientJson.parseToJsonElement(it).jsonObject
                obj["Summary"]?.jsonObject?.get("DeckId")?.jsonPrimitive?.content
                    ?: obj["DeckId"]?.jsonPrimitive?.content
                    ?: obj["deckId"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }

    private fun handleGraphRequest(ctx: ChannelHandlerContext, transactionId: String?, json: String?) {
        val graphId = json?.let { GRAPH_ID_PATTERN.find(it)?.groupValues?.get(1) } ?: "unknown"
        log.info("Front Door: GraphState graphId={}", graphId)
        val response = golden.graphStateResponses[graphId] ?: GRAPH_DEFAULT
        writer.sendJson(ctx, transactionId, response)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Front Door error: {}", cause.message, cause)
        ctx.close()
    }

    companion object {
        private val GRAPH_ID_PATTERN = Regex(""""GraphId"\s*:\s*"([^"]+)"""")

        private const val GRAPH_DEFAULT = """{"NodeStates":{},"MilestoneStates":{}}"""
    }
}
