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
import kotlinx.serialization.json.put
import leyline.debug.FdDebugCollector
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.MatchInfo
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.LobbyStubs
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.DeckWireBuilder
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
    private val writer: FdResponseWriter,
    private val golden: GoldenData,
    /** Called when client sends 612 with a deckId — writes to shared holder. */
    private val onDeckSelected: ((String) -> Unit)? = null,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(FrontDoorHandler::class.java)

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    init {
        log.info(
            "Front Door stub: loaded golden data — formats={}B sets={}B startHook={}B queueConfig={}B",
            golden.getFormatsProto.size,
            golden.getSetsProto.size,
            golden.startHookJson.length,
            golden.playBladeQueueConfigJson.length,
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

            FdDebugCollector.record("C2S", decoded)
            log.info("Front Door: cmd={} cmdType={} txId={}", cmdName, cmdType, transactionId)

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
                val deckId = json?.let { DECK_ID_PATTERN.find(it)?.groupValues?.get(1) }
                if (deckId != null) onDeckSelected?.invoke(deckId)
                val pid = playerId ?: PlayerId("anonymous")
                val match = matchmaking.startAiMatch(pid, DeckId(deckId ?: ""))
                log.info("Front Door: Event_AiBotMatch deckId={} → ack + pushing MatchCreated", deckId)
                writer.sendEmpty(ctx, txId)
                sendMatchCreated(ctx, match)
            }

            // --- Play blade data ---
            1910 -> { // GetPlayBladeQueueConfig
                log.info("Front Door: PlayBladeQueueConfig (golden)")
                writer.sendJson(ctx, txId, golden.playBladeQueueConfigJson)
            }

            624 -> { // Event_GetActiveEventsV2
                log.info("Front Door: ActiveEventsV2 (golden)")
                writer.sendJson(ctx, txId, golden.activeEventsJson)
            }

            623 -> { // EventGetCoursesV2
                log.debug("Front Door: EventGetCoursesV2 → empty courses")
                writer.sendJson(ctx, txId, LobbyStubs.courses())
            }

            // --- Player data (from services) ---

            1911 -> { // GetPlayerPreferences
                val pid = playerId
                val prefs = pid?.let { playerService.getPreferences(it) }
                val raw = prefs?.json?.takeIf { it != "{}" }
                val source = if (raw != null) "db" else "golden"
                log.info("Front Door: PlayerPreferences ($source)")
                writer.sendJson(ctx, txId, raw ?: golden.goldenPlayerPreferencesJson)
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

            406 -> { // Deck_UpdateDeckV3
                requireJson(ctx, txId, json) { body ->
                    val pid = playerId
                    if (pid != null) {
                        val deck = DeckWireBuilder.parseDeckUpdate(body, pid)
                        if (deck != null) {
                            deckService.save(deck)
                            log.info("Front Door: Deck_UpdateDeckV3 saved '{}'", deck.name)
                        } else {
                            log.warn("Front Door: Deck_UpdateDeckV3 parse failed")
                        }
                    }
                    writer.sendEmpty(ctx, txId)
                }
            }

            410 -> { // Deck_GetDeckSummariesV3
                val pid = playerId
                if (pid != null) {
                    val decks = deckService.listForPlayer(pid)
                    val summaries = buildJsonArray {
                        for (d in decks) add(DeckWireBuilder.toV3Summary(d))
                    }
                    log.info("Front Door: DeckSummariesV3 ({} decks from db)", decks.size)
                    val resp = buildJsonObject { put("DeckSummariesV3", summaries) }
                    writer.sendJson(ctx, txId, lenientJson.encodeToString(JsonObject.serializer(), resp))
                } else {
                    writer.sendEmpty(ctx, txId)
                }
            }

            // --- Lobby requests: minimal valid responses ---
            613 -> writer.sendJson(ctx, txId, LobbyStubs.activeMatches())
            1520 -> writer.sendProto(ctx, txId, "Wizards.Arena.Models.Network.GetVoucherDefinitionsResponse")
            704 -> writer.sendJson(ctx, txId, LobbyStubs.carousel())
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
            551 -> writer.sendJson(ctx, txId, LobbyStubs.cardSet())
            708, 712 -> writer.sendJson(ctx, txId, LobbyStubs.storeStatus())
            1102 -> writer.sendJson(ctx, txId, LobbyStubs.rankSeasonDetails())
            1105 -> writer.sendJson(ctx, txId, LobbyStubs.rankSeasonDetails())
            3006 -> writer.sendProto(ctx, txId, "Wizards.Arena.Models.Network.ChallengeReconnectAllResp")

            // --- Telemetry ack ---
            1913, 1914 -> {
                log.debug("Front Door: telemetry (CmdType {})", cmdType)
                writer.sendJson(ctx, txId, LobbyStubs.telemetryAck())
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
                log.debug("Front Door: CmdType {} ({}) → empty", cmdType, FdEnvelope.cmdTypeName(cmdType))
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
        val json = FdEnvelope.buildMatchCreatedJson(match.matchId, match.host, match.port)
        log.info("Front Door: pushing MatchCreated matchId={}", match.matchId)
        val pushTxId = UUID.randomUUID().toString()
        writer.sendJson(ctx, pushTxId, json)
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
        private val DECK_ID_PATTERN = Regex(""""deckId"\s*:\s*"([^"]+)"""")
        private const val GRAPH_DEFAULT = """{"NodeStates":{},"MilestoneStates":{}}"""
    }
}
