package leyline.server

import leyline.debug.FdDebugCollector
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.FdEnvelope
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Front Door service (port 30010) — fully offline, no proxy.
 *
 * Loads golden capture data from classpath resources (`fd-golden/`) for:
 * - Protobuf responses (GetFormats, GetSets) — raw field 2 bytes
 * - Rich JSON responses (StartHook, GraphDefinitions, GraphState, DesignerMetadata)
 *
 * CmdType-based dispatch with no rigid state machine — the client sends requests
 * in varying order depending on version/cache state.
 *
 * Match trigger:
 * - Event_AiBotMatch (612): user clicks Play → Sparky, we push MatchCreated (600)
 */
class FrontDoorService(
    private val matchDoorHost: String = "localhost",
    private val matchDoorPort: Int = 30003,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(FrontDoorService::class.java)

    // --- Golden data loaded from classpath resources ---
    private val getFormatsProto: ByteArray = loadResource("fd-golden/get-formats-response.bin")
    private val getSetsProto: ByteArray = loadResource("fd-golden/get-sets-response.bin")
    private val startHookJson: String = loadTextResource("fd-golden/start-hook.json")
    private val graphDefinitionsJson: String = loadTextResource("fd-golden/graph-definitions.json")
    private val designerMetadataJson: String = loadTextResource("fd-golden/designer-metadata.json")
    private val playBladeQueueConfigJson: String = loadTextResource("fd-golden/play-blade-queue-config.json")
    private val activeEventsJson: String = loadTextResource("fd-golden/active-events.json")
    private val playerPreferencesJson: String = loadTextResource("fd-golden/player-preferences.json")
    private val graphStateResponses: Map<String, String> = mapOf(
        "NPE_Tutorial" to loadTextResource("fd-golden/graph-state-npe-tutorial.json"),
        "NewPlayerExperience" to loadTextResource("fd-golden/graph-state-npe.json"),
        "ColorChallenge" to loadTextResource("fd-golden/graph-state-color-challenge.json"),
    )

    init {
        log.info(
            "Front Door stub: loaded golden data — formats={}B sets={}B startHook={}B queueConfig={}B",
            getFormatsProto.size,
            getSetsProto.size,
            startHookJson.length,
            playBladeQueueConfigJson.length,
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
                sendCtrlAck(ctx, bytes)
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
                sendEmptyResponse(ctx, null)
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
            0 -> { // Authenticate
                log.info("Front Door: auth → session")
                val sessionId = UUID.randomUUID().toString()
                sendJsonResponse(ctx, txId, """{"SessionId":"$sessionId","Attached":true}""")
            }

            // --- Startup essentials (golden data) ---
            1 -> { // StartHook
                log.info("Front Door: StartHook (golden, {}B)", startHookJson.length)
                sendJsonResponse(ctx, txId, startHookJson)
            }

            1700 -> { // Graph_GetGraphDefinitions
                log.info("Front Door: GraphDefs (golden, {}B)", graphDefinitionsJson.length)
                sendJsonResponse(ctx, txId, graphDefinitionsJson)
            }

            1701 -> { // Graph_GetGraphState
                handleGraphRequest(ctx, txId, json)
            }

            2400 -> { // GetDesignerMetadata
                log.info("Front Door: DesignerMetadata (golden)")
                sendJsonResponse(ctx, txId, designerMetadataJson)
            }

            // --- Protobuf responses (field 2 — golden .bin files) ---
            6 -> { // GetFormats
                log.info("Front Door: GetFormats (golden proto, {}B)", getFormatsProto.size)
                sendRawProtoResponse(ctx, txId, getFormatsProto)
            }

            1521 -> { // GetSets
                log.info("Front Door: GetSets (golden proto, {}B)", getSetsProto.size)
                sendRawProtoResponse(ctx, txId, getSetsProto)
            }

            // --- Match trigger ---
            612 -> { // Event_AiBotMatch
                log.info("Front Door: Event_AiBotMatch → ack + pushing MatchCreated")
                // Must respond to 612 txId first, then push MatchCreated (600)
                sendEmptyResponse(ctx, txId)
                sendMatchCreated(ctx)
            }

            // --- Play blade data ---
            1910 -> { // GetPlayBladeQueueConfig — drives Find Match tab
                log.info("Front Door: PlayBladeQueueConfig (golden)")
                sendJsonResponse(ctx, txId, playBladeQueueConfigJson)
            }

            624 -> { // Event_GetActiveEventsV2 — drives Events tab + queue event defs
                log.info("Front Door: ActiveEventsV2 (golden)")
                sendJsonResponse(ctx, txId, activeEventsJson)
            }

            623 -> { // EventGetCoursesV2 — player's active event enrollments
                log.debug("Front Door: EventGetCoursesV2 → empty courses")
                sendJsonResponse(ctx, txId, """{"Courses":[]}""")
            }

            1911 -> { // GetPlayerPreferences — last selected queue/deck
                log.info("Front Door: PlayerPreferences (golden)")
                sendJsonResponse(ctx, txId, playerPreferencesJson)
            }

            // --- Lobby requests: minimal valid responses ---
            613 -> { // Event_GetActiveMatches
                sendJsonResponse(ctx, txId, """{"MatchesV3":[]}""")
            }

            1520 -> { // GetVoucherDefinitions — protobuf response
                sendProtoResponse(ctx, txId, "Wizards.Arena.Models.Network.GetVoucherDefinitionsResponse")
            }

            704 -> { // Carousel_GetCarouselItems — expects List<DTO_CarouselItem>
                sendJsonResponse(ctx, txId, """[]""")
            }

            2600 -> { // GetAllPreferredPrintings — expects Dictionary<int, int>
                sendJsonResponse(ctx, txId, """{}""")
            }

            2700 -> { // GetAllPrizeWalls — expects GetAllPrizeWallsResp
                sendJsonResponse(ctx, txId, """{"ActivePrizeWalls":[]}""")
            }

            1100 -> { // Rank_GetCombinedRankInfo
                sendJsonResponse(ctx, txId, RANK_DEFAULT)
            }

            1000 -> { // Quest_GetQuests
                sendJsonResponse(ctx, txId, """{"Quests":[]}""")
            }

            1200, 1201 -> { // PeriodicRewards_GetStatus
                sendJsonResponse(ctx, txId, """{}""")
            }

            800 -> { // Currency_GetCurrencies
                sendJsonResponse(ctx, txId, """{"Currencies":[]}""")
            }

            901 -> { // Booster_GetOwnedBoosters
                sendJsonResponse(ctx, txId, """{"Boosters":[]}""")
            }

            1900 -> { // Cosmetics_GetPlayerOwnedCosmetics
                sendJsonResponse(ctx, txId, """{"Cosmetics":[]}""")
            }

            2200 -> { // GetNetDeckFolders — expects List<DTO_NetDeckFolder>
                sendJsonResponse(ctx, txId, """[]""")
            }

            2300 -> { // GetPlayerInbox
                sendJsonResponse(ctx, txId, """{"Messages":[]}""")
            }

            2500 -> { // StaticContent
                sendJsonResponse(ctx, txId, """{}""")
            }

            // --- Misc lobby requests: minimal safe responses ---
            551 -> { // Card_GetCardSet
                sendJsonResponse(ctx, txId, """{}""")
            }

            410 -> { // Deck_GetDeckSummariesV3? (unknown)
                sendJsonResponse(ctx, txId, """{}""")
            }

            708, 712 -> { // StoreStatusV2 — expects JSON object, not array
                sendJsonResponse(ctx, txId, """{"CatalogStatus":[]}""")
            }

            1102 -> { // RankGetSeasonAndRankDetails
                sendJsonResponse(ctx, txId, """{}""")
            }

            1105 -> { // Rank variant
                sendJsonResponse(ctx, txId, """{}""")
            }

            1912 -> { // PlayerPreferences variant
                sendJsonResponse(ctx, txId, """{}""")
            }

            3006 -> { // ChallengeReconnectAll — protobuf response
                sendProtoResponse(ctx, txId, "Wizards.Arena.Models.Network.ChallengeReconnectAllResp")
            }

            // --- Telemetry ack ---
            1913, 1914 -> {
                log.debug("Front Door: telemetry (CmdType {})", cmdType)
                sendJsonResponse(ctx, txId, "Success")
            }

            // --- Everything else: empty response ---
            null -> {
                // CmdType not decoded — fall back to JSON content matching
                if (json == null) return
                when {
                    "ClientVersion" in json || "Token" in json -> {
                        log.info("Front Door: auth (fallback)")
                        val sessionId = UUID.randomUUID().toString()
                        sendJsonResponse(ctx, txId, """{"SessionId":"$sessionId","Attached":true}""")
                    }
                    "GraphId" in json -> handleGraphRequest(ctx, txId, json)
                    "AIBotMatch" in json || "PlayQueue" in json -> sendMatchCreated(ctx)
                    else -> {
                        log.debug("Front Door: unrecognized (no CmdType): {}", json.take(80))
                        sendEmptyResponse(ctx, txId)
                    }
                }
            }

            else -> {
                log.debug("Front Door: CmdType {} ({}) → empty", cmdType, FdEnvelope.cmdTypeName(cmdType))
                sendEmptyResponse(ctx, txId)
            }
        }
    }

    // --- outgoing message construction ---

    private fun sendCtrlAck(ctx: ChannelHandlerContext, initFrame: ByteArray) {
        val ack = initFrame.copyOf()
        ack[1] = ClientFrameDecoder.TYPE_CTRL_ACK
        val buf = ctx.alloc().buffer(ack.size)
        buf.writeBytes(ack)
        ctx.writeAndFlush(buf)
    }

    private fun sendJsonResponse(ctx: ChannelHandlerContext, transactionId: String?, json: String) {
        val txId = transactionId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeResponse(txId, json)
        sendRaw(ctx, txId, json.take(80), envelope)
    }

    /** Send a Response with only transactionId, no payload. */
    private fun sendEmptyResponse(ctx: ChannelHandlerContext, transactionId: String?) {
        val txId = transactionId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeEmptyResponse(txId)
        sendRaw(ctx, txId, null, envelope)
    }

    /** Send a Response with an empty protobuf Any in field 2 (default/empty proto message). */
    private fun sendProtoResponse(ctx: ChannelHandlerContext, transactionId: String?, typeName: String) {
        val txId = transactionId ?: UUID.randomUUID().toString()
        val typeUrl = "type.googleapis.com/$typeName"
        val envelope = FdEnvelope.encodeProtoResponse(txId, typeUrl)
        sendRaw(ctx, txId, "(proto empty $typeName)", envelope)
    }

    /** Send a Response with raw golden protobuf bytes in field 2. */
    private fun sendRawProtoResponse(ctx: ChannelHandlerContext, transactionId: String?, protoPayload: ByteArray) {
        val txId = transactionId ?: UUID.randomUUID().toString()
        val envelope = FdEnvelope.encodeRawProtoResponse(txId, protoPayload)
        sendRaw(ctx, txId, "(proto ${protoPayload.size}B)", envelope)
    }

    private fun sendRaw(ctx: ChannelHandlerContext, txId: String, logPayload: String?, envelope: ByteArray) {
        val header = FdEnvelope.buildOutgoingHeader(envelope.size)
        FdDebugCollector.record(
            "S2C",
            FdEnvelope.FdMessage(
                cmdType = null,
                transactionId = txId,
                jsonPayload = logPayload,
                envelopeType = FdEnvelope.EnvelopeType.RESPONSE,
            ),
        )
        val buf = ctx.alloc().buffer(header.size + envelope.size)
        buf.writeBytes(header)
        buf.writeBytes(envelope)
        ctx.writeAndFlush(buf)
    }

    /**
     * Push a MatchCreated notification as a Response envelope (not Cmd).
     *
     * The client's FrontDoorConnectionAWS always parses incoming frames as
     * `Response` protobuf. If the txId doesn't match a pending promise, the
     * JSON payload is deserialized as `PushNotification` and dispatched by
     * `ENotificationType`. Using a Cmd envelope causes "Failed to deserialize
     * Response" errors because field 1 (varint CmdType) is not a valid string.
     */
    private fun sendMatchCreated(ctx: ChannelHandlerContext) {
        val matchId = UUID.randomUUID().toString()
        val json = FdEnvelope.buildMatchCreatedJson(matchId, matchDoorHost, matchDoorPort)
        log.info("Front Door: pushing MatchCreated matchId={}", matchId)
        // Use a fresh txId that won't match any pending promise → client treats as push
        val pushTxId = UUID.randomUUID().toString()
        sendJsonResponse(ctx, pushTxId, json)
    }

    private fun handleGraphRequest(ctx: ChannelHandlerContext, transactionId: String?, json: String?) {
        val graphId = json?.let { GRAPH_ID_PATTERN.find(it)?.groupValues?.get(1) } ?: "unknown"
        log.info("Front Door: GraphState graphId={}", graphId)
        val response = graphStateResponses[graphId] ?: GRAPH_DEFAULT
        sendJsonResponse(ctx, transactionId, response)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Front Door error: {}", cause.message, cause)
        ctx.close()
    }

    companion object {
        private val GRAPH_ID_PATTERN = Regex(""""GraphId"\s*:\s*"([^"]+)"""")
        private const val GRAPH_DEFAULT = """{"NodeStates":{},"MilestoneStates":{}}"""
        private const val RANK_DEFAULT = """{"playerId":null,"constructedSeasonOrdinal":0,"constructedClass":"Bronze","constructedLevel":0,"constructedStep":0,"constructedMatchesWon":0,"constructedMatchesLost":0,"constructedMatchesDrawn":0,"limitedSeasonOrdinal":0,"limitedClass":"Bronze","limitedLevel":0,"limitedStep":0,"limitedMatchesWon":0,"limitedMatchesLost":0,"limitedMatchesDrawn":0}"""

        private fun loadResource(path: String): ByteArray = FrontDoorService::class.java.classLoader.getResourceAsStream(path)
            ?.readBytes()
            ?: error("Missing classpath resource: $path")

        private fun loadTextResource(path: String): String = loadResource(path).toString(Charsets.UTF_8)
    }
}
