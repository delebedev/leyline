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
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.MatchInfo
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import leyline.frontdoor.service.CollectionService
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.DraftService
import leyline.frontdoor.service.EventRegistry
import leyline.frontdoor.service.LobbyStubs
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.CmdType
import leyline.frontdoor.wire.DeckWireBuilder
import leyline.frontdoor.wire.DraftWireBuilder
import leyline.frontdoor.wire.EventWireBuilder
import leyline.frontdoor.wire.FdEnvelope
import leyline.frontdoor.wire.FdRequests
import leyline.frontdoor.wire.FdResponse
import leyline.frontdoor.wire.FdResponseWriter
import leyline.frontdoor.wire.FdWireConstants
import leyline.frontdoor.wire.PlayerWireBuilder
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
    private val courseService: CourseService? = null,
    private val draftService: DraftService? = null,
    private val writer: FdResponseWriter,
    private val golden: GoldenData,
    private val onFdMessage: ((String, FdEnvelope.FdMessage) -> Unit)? = null,
    /** Called when client sends 612 with a deckId — writes to shared holder. */
    private val onDeckSelected: ((String) -> Unit)? = null,
    /** Called when client sends 612 with an eventName — writes to shared holder. */
    private val onEventSelected: ((String) -> Unit)? = null,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(FrontDoorHandler::class.java)

    /**
     * Deck selected via 622 (Event_SetDeckV2), keyed by eventName. Consumed by 603 (EnterPairing).
     *
     * Only used for constructed events where the deck lives in [DeckRepository].
     * Sealed events get their deck from [CourseService] instead — 622 writes
     * the deck into the Course, and 603 reads it back via [CourseService.enterPairing].
     * Entries are never cleaned up (harmless — handler is per-connection).
     */
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

            if (bytes.size < FdWireConstants.HEADER_SIZE) return

            val frameType = bytes[1]
            log.debug("Front Door: frame type=0x{} size={}", String.format("%02x", frameType), bytes.size)

            // Control frames
            if (frameType == FdWireConstants.TYPE_CTRL_INIT) {
                log.debug("Front Door: CTRL_INIT received, sending ACK")
                writer.sendCtrlAck(ctx, bytes)
                return
            }
            if (frameType == FdWireConstants.TYPE_CTRL_ACK) {
                log.debug("Front Door: CTRL_ACK received (ignored)")
                return
            }

            val payload = if (bytes.size > FdWireConstants.HEADER_SIZE) {
                bytes.copyOfRange(FdWireConstants.HEADER_SIZE, bytes.size)
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
            val cmdName = cmdType?.let { CmdType.nameOf(it) } ?: "unknown"

            onFdMessage?.invoke("C2S", decoded)
            log.debug("Front Door: cmd={} cmdType={} txId={}", cmdName, cmdType, transactionId)

            dispatch(ctx, cmdType, transactionId, json)
        } finally {
            msg.release()
        }
    }

    /** Table-driven stubs — CmdTypes that return static/golden data with no logic. */
    private val stubs: Map<Int, () -> FdResponse> = mapOf(
        // Golden data (proto)
        CmdType.GET_FORMATS.value to { FdResponse.RawProto(golden.getFormatsProto) },
        CmdType.GET_SETS.value to { FdResponse.RawProto(golden.getSetsProto) },
        // Golden data (JSON)
        CmdType.DECK_GET_PRECONS_V3.value to { FdResponse.Json(golden.preconDecksJson) },
        CmdType.CAROUSEL_GET_ITEMS.value to { FdResponse.Json(golden.carouselJson) },
        CmdType.GRAPH_GET_DEFINITIONS.value to { FdResponse.Json(golden.graphDefinitionsJson) },
        CmdType.GET_DESIGNER_METADATA.value to { FdResponse.Json(golden.designerMetadataJson) },
        // Lobby stubs
        CmdType.EVENT_GET_ACTIVE_MATCHES.value to { FdResponse.Json(LobbyStubs.activeMatches()) },
        CmdType.CURRENCY_GET_CURRENCIES.value to { FdResponse.Json(LobbyStubs.currencies()) },
        CmdType.BOOSTER_GET_OWNED.value to { FdResponse.Json(LobbyStubs.boosters()) },
        CmdType.QUEST_GET_QUESTS.value to { FdResponse.Json(LobbyStubs.quests()) },
        CmdType.RANK_GET_COMBINED.value to { FdResponse.Json(LobbyStubs.rankInfo()) },
        CmdType.RANK_GET_SEASON_DETAILS.value to { FdResponse.Json(LobbyStubs.rankSeasonDetails()) },
        CmdType.RANK_EVALUATE_PAYOUTS_V2.value to { FdResponse.Json(LobbyStubs.rankSeasonDetails()) },
        CmdType.PERIODIC_REWARDS_GET_STATUS.value to { FdResponse.Json(LobbyStubs.periodicRewards()) },
        CmdType.RENEWAL_GET_CURRENT.value to { FdResponse.Json(LobbyStubs.periodicRewards()) },
        CmdType.COSMETICS_GET_OWNED.value to { FdResponse.Json(LobbyStubs.cosmetics()) },
        CmdType.GET_NET_DECK_FOLDERS.value to { FdResponse.Json(LobbyStubs.netDeckFolders()) },
        CmdType.GET_PLAYER_INBOX.value to { FdResponse.Json(LobbyStubs.playerInbox()) },
        CmdType.STATIC_CONTENT.value to { FdResponse.Json(LobbyStubs.staticContent()) },
        CmdType.GET_ALL_PREFERRED_PRINTINGS.value to { FdResponse.Json(LobbyStubs.preferredPrintings()) },
        CmdType.GET_ALL_PRIZE_WALLS.value to { FdResponse.Json(LobbyStubs.prizeWalls()) },
        CmdType.MERC_GET_STORE_STATUS_V2.value to { FdResponse.Json(LobbyStubs.storeStatus()) },
        CmdType.STORE_GET_ENTITLEMENTS_V2.value to { FdResponse.Json(LobbyStubs.storeStatus()) },
        CmdType.MERC_GET_SKUS_AND_LISTINGS.value to { FdResponse.Json(LobbyStubs.storeStatus()) },
        CmdType.LOG_BUSINESS_EVENTS.value to { FdResponse.Json(LobbyStubs.telemetryAck()) },
        CmdType.LOG_BUSINESS_EVENTS_V2.value to { FdResponse.Json(LobbyStubs.telemetryAck()) },
        // Typed proto stubs
        CmdType.GET_VOUCHER_DEFINITIONS.value to { FdResponse.TypedProto("Wizards.Arena.Models.Network.GetVoucherDefinitionsResponse") },
        CmdType.CHALLENGE_RECONNECT_ALL.value to { FdResponse.TypedProto("Wizards.Arena.Models.Network.ChallengeReconnectAllResp") },
    )

    private fun dispatch(ctx: ChannelHandlerContext, cmdType: Int?, txId: String?, json: String?) {
        // Fast path: table-driven stubs (no logic, just data)
        stubs[cmdType]?.let { supplier ->
            writer.send(ctx, txId, supplier())
            return
        }

        // Commands with real logic
        when (cmdType) {
            CmdType.AUTHENTICATE.value -> {
                log.info("Front Door: auth → session")
                val pid = playerId
                if (pid != null) {
                    val session = playerService.authenticate(pid, "Player")
                    writer.send(ctx, txId, FdResponse.Json("""{"SessionId":"${session.value}","Attached":true}"""))
                } else {
                    val sessionId = UUID.randomUUID().toString()
                    writer.send(ctx, txId, FdResponse.Json("""{"SessionId":"$sessionId","Attached":true}"""))
                }
            }

            CmdType.START_HOOK.value -> {
                val hook = buildStartHook()
                log.info("Front Door: StartHook ({}B)", hook.length)
                writer.send(ctx, txId, FdResponse.Json(hook))
            }

            CmdType.GRAPH_GET_STATE.value -> handleGraphRequest(ctx, txId, json)

            CmdType.EVENT_AI_BOT_MATCH.value -> {
                val req = FdRequests.parseAiBotMatch(json)
                val deckId = req?.deckId
                if (deckId != null) onDeckSelected?.invoke(deckId)
                onEventSelected?.invoke("AIBotMatch")
                val pid = playerId ?: PlayerId("anonymous")
                val match = matchmaking.startAiMatch(pid, DeckId(deckId ?: ""), "AIBotMatch")
                log.info("Front Door: Event_AiBotMatch deckId={} botDeckId={} → ack + pushing MatchCreated", deckId, req?.botDeckId)
                writer.send(ctx, txId, FdResponse.Empty)
                sendMatchCreated(ctx, match)
            }

            CmdType.GET_PLAY_BLADE_QUEUE_CONFIG.value -> {
                val configJson = EventWireBuilder.toQueueConfigJson(EventRegistry.queues)
                log.info("Front Door: PlayBladeQueueConfig ({} queues)", EventRegistry.queues.size)
                writer.send(ctx, txId, FdResponse.Json(configJson))
            }

            CmdType.EVENT_GET_ACTIVE_EVENTS_V2.value -> {
                val eventsJson = EventWireBuilder.toActiveEventsJson(EventRegistry.events)
                log.info("Front Door: ActiveEventsV2 ({} events)", EventRegistry.events.size)
                writer.send(ctx, txId, FdResponse.Json(eventsJson))
            }

            CmdType.EVENT_GET_COURSES_V2.value -> {
                log.info("Front Door: Event_GetCoursesV2")
                if (courseService != null && playerId != null) {
                    val courses = courseService.getCoursesForPlayer(playerId)
                    // Merge real courses with default seed courses (Ladder, Play, etc.)
                    val realEventNames = courses.map { it.eventName }.toSet()
                    val defaultJson = EventRegistry.defaultCourses
                        .filter { it.first !in realEventNames }
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.toMergedCoursesJson(courses, defaultJson)))
                } else {
                    writer.send(
                        ctx,
                        txId,
                        FdResponse.Json(
                            EventWireBuilder.toDefaultCoursesJson(EventRegistry.defaultCourses),
                        ),
                    )
                }
            }

            CmdType.GET_PLAYER_PREFERENCES.value -> {
                val pid = playerId
                val prefs = pid?.let { playerService.getPreferences(it) }
                val raw = prefs?.json?.takeIf { it != "{}" }
                log.info("Front Door: PlayerPreferences ({})", if (raw != null) "db" else "empty")
                writer.send(ctx, txId, FdResponse.Json(raw ?: """{"Preferences":{}}"""))
            }

            CmdType.SET_PLAYER_PREFERENCES.value -> {
                requireJson(ctx, txId, json) { body ->
                    val pid = playerId
                    if (pid != null) {
                        val cleaned = PlayerWireBuilder.parsePreferences(body)
                        playerService.savePreferences(pid, Preferences(cleaned))
                        log.info("Front Door: SetPlayerPreferences saved")
                    }
                    writer.send(ctx, txId, FdResponse.Json("{}"))
                }
            }

            CmdType.DECK_DELETE.value -> {
                val req = FdRequests.parseDeleteDeck(json)
                if (req != null) {
                    deckService.delete(DeckId(req.deckId))
                    log.info("Front Door: Deck_DeleteDeck '{}'", req.deckId)
                }
                writer.send(ctx, txId, FdResponse.Json("{}"))
            }

            CmdType.DECK_UPSERT_V2.value -> {
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
                    val summary = try {
                        val obj = lenientJson.parseToJsonElement(body).jsonObject
                        obj["Summary"]?.let { lenientJson.encodeToString(JsonObject.serializer(), it.jsonObject) }
                    } catch (_: Exception) {
                        null
                    }
                    writer.send(ctx, txId, FdResponse.Json(summary ?: "{}"))
                }
            }

            CmdType.DECK_GET_SUMMARIES_V2.value -> {
                val pid = playerId
                if (pid != null) {
                    val decks = deckService.listForPlayer(pid)
                    val summaries = buildJsonArray { decks.forEach { add(DeckWireBuilder.toV2Summary(it)) } }
                    log.info("Front Door: DeckSummariesV2 ({} decks)", decks.size)
                    val resp = buildJsonObject { put("Summaries", summaries) }
                    writer.send(ctx, txId, FdResponse.Json(lenientJson.encodeToString(JsonObject.serializer(), resp)))
                } else {
                    writer.send(ctx, txId, FdResponse.Json("""{"Summaries":[]}"""))
                }
            }

            CmdType.CARD_GET_ALL.value -> {
                val collection = collectionService.getCollection(playerId)
                log.info("Front Door: CardGetAllCards ({} cards)", collection.size)
                writer.send(ctx, txId, FdResponse.Json(collectionService.toJson(collection)))
            }

            CmdType.EVENT_JOIN.value -> {
                val req = FdRequests.parseEventJoin(json)
                val eventName = req?.eventName
                log.info("Front Door: Event_Join event={}", eventName)
                if (eventName != null && courseService != null && playerId != null) {
                    val course = courseService.join(playerId, eventName)
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildJoinResponse(course)))
                } else {
                    val goldenJson = when {
                        EventRegistry.isDraft(eventName ?: "") -> golden.draftJoinJson
                        EventRegistry.isSealed(eventName ?: "") -> golden.sealedJoinJson
                        else -> golden.eventJoinJson
                    }
                    writer.send(ctx, txId, FdResponse.Json(goldenJson))
                }
            }

            CmdType.EVENT_DROP.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_Drop event={}", req?.eventName)
                if (req?.eventName != null && courseService != null && playerId != null) {
                    try {
                        val course = courseService.drop(playerId, req.eventName)
                        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
                    } catch (e: IllegalArgumentException) {
                        writer.send(ctx, txId, FdResponse.Json("{}"))
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Json("{}"))
                }
            }

            CmdType.EVENT_ENTER_PAIRING.value -> {
                val req = FdRequests.parseEnterPairing(json)
                val eventName = req?.eventName
                log.info("Front Door: Event_EnterPairing event={}", eventName)

                val pid = playerId ?: PlayerId("anonymous")
                try {
                    if (eventName != null) onEventSelected?.invoke(eventName)

                    // Try course-based deck (sealed events), fall back to selected deck (constructed)
                    val course = if (courseService != null && playerId != null && eventName != null) {
                        courseService.getCourse(playerId, eventName)
                    } else {
                        null
                    }
                    val courseDeckId = course?.deck?.deckId?.value
                    val deckId = courseDeckId ?: eventName?.let { selectedDeckByEvent[it] }
                    if (deckId != null) onDeckSelected?.invoke(deckId)

                    val match = if (courseDeckId != null) {
                        matchmaking.createMatchInfo(eventName ?: "")
                    } else {
                        matchmaking.startMatch(pid, DeckId(deckId ?: ""), eventName ?: "")
                    }
                    writer.send(ctx, txId, FdResponse.Json("""{"CurrentModule":"CreateMatch","Payload":"Success"}"""))
                    sendMatchCreated(ctx, match)
                } catch (e: IllegalArgumentException) {
                    log.warn("Front Door: Event_EnterPairing rejected — {}", e.message)
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_LEAVE_PAIRING.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_LeavePairing event={}", req?.eventName)
                writer.send(ctx, txId, FdResponse.Empty)
            }

            CmdType.EVENT_RESIGN.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_Resign event={}", req?.eventName)
                if (req?.eventName != null && courseService != null && playerId != null) {
                    try {
                        if (EventRegistry.isDraft(req.eventName)) {
                            draftService?.drop(playerId, req.eventName)
                        }
                        val course = courseService.drop(playerId, req.eventName)
                        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
                    } catch (e: IllegalArgumentException) {
                        writer.send(ctx, txId, FdResponse.Json(golden.eventResignJson))
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Json(golden.eventResignJson))
                }
            }

            CmdType.EVENT_GET_MATCH_RESULT.value -> {
                val req = FdRequests.parseMatchResult(json)
                log.info("Front Door: Event_GetMatchResultReport event={}", req?.eventName)
                val eventName = req?.eventName
                val course = if (courseService != null && playerId != null && eventName != null) {
                    courseService.getCourse(playerId, eventName)
                } else {
                    null
                }
                if (course != null) {
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildMatchResultReport(course)))
                } else {
                    writer.send(ctx, txId, FdResponse.Json(golden.eventMatchResultReportJson))
                }
            }

            CmdType.EVENT_SET_JUMPSTART_PACKET.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_SetJumpStartPacket event={}", req?.eventName)
                writer.send(ctx, txId, FdResponse.Json(golden.eventSetJumpstartPacketJson))
            }

            CmdType.BOT_DRAFT_START.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: BotDraft_StartDraft event={}", eventName)
                if (eventName != null && draftService != null && playerId != null) {
                    val session = draftService.startDraft(playerId, eventName)
                    writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                } else {
                    writer.send(ctx, txId, FdResponse.Json(golden.draftStartJson))
                }
            }

            CmdType.BOT_DRAFT_PICK.value -> {
                val req = FdRequests.parseDraftPick(json)
                log.info("Front Door: BotDraft_DraftPick card={} pack={} pick={}", req?.cardId, req?.packNumber, req?.pickNumber)
                if (req != null && draftService != null && playerId != null) {
                    val session = draftService.pick(playerId, req.eventName, req.cardId, req.packNumber, req.pickNumber)
                    if (session.status == DraftStatus.Completed && courseService != null) {
                        courseService.completeDraft(playerId, req.eventName, session.pickedCards)
                    }
                    writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                } else {
                    writer.send(ctx, txId, FdResponse.Json(golden.draftPickJson))
                }
            }

            CmdType.BOT_DRAFT_STATUS.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: BotDraft_DraftStatus event={}", eventName)
                if (eventName != null && draftService != null && playerId != null) {
                    val session = draftService.getStatus(playerId, eventName)
                    if (session != null) {
                        writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                    } else {
                        writer.send(ctx, txId, FdResponse.Json(golden.draftStatusJson))
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Json(golden.draftStatusJson))
                }
            }

            CmdType.EVENT_SET_DECK_V2.value -> {
                val req = FdRequests.parseSetDeck(json)
                if (req != null && req.deckId != null) {
                    selectedDeckByEvent[req.eventName] = req.deckId
                }
                log.info("Front Door: Event_SetDeckV2 event={} deck={}", req?.eventName, req?.deckId)
                if (req != null && courseService != null && playerId != null) {
                    try {
                        val resolvedDeckId = DeckId(req.deckId ?: UUID.randomUUID().toString())
                        val deck = CourseDeck(
                            deckId = resolvedDeckId,
                            mainDeck = req.mainDeck,
                            sideboard = req.sideboard,
                        )
                        val summary = CourseDeckSummary(
                            deckId = resolvedDeckId,
                            name = req.deckName ?: "Sealed Deck",
                            tileId = req.tileId ?: 0,
                            format = "Limited",
                        )
                        val course = courseService.setDeck(playerId, req.eventName, deck, summary)
                        writer.send(
                            ctx,
                            txId,
                            FdResponse.Json(
                                EventWireBuilder.buildCourseJson(course).toString(),
                            ),
                        )
                    } catch (e: IllegalArgumentException) {
                        log.warn("Front Door: Event_SetDeckV2 failed: {}", e.message)
                        val isSealed = EventRegistry.isSealed(req.eventName)
                        writer.send(
                            ctx,
                            txId,
                            FdResponse.Json(
                                if (isSealed) golden.sealedSetDeckJson else golden.eventSetDeckJson,
                            ),
                        )
                    }
                } else {
                    val isSealed = EventRegistry.isSealed(req?.eventName ?: "")
                    writer.send(
                        ctx,
                        txId,
                        FdResponse.Json(
                            if (isSealed) golden.sealedSetDeckJson else golden.eventSetDeckJson,
                        ),
                    )
                }
            }

            // Response-type envelope (field 1 is UUID, not varint) — no CmdType extracted.
            // Legacy fallback: may be dead now that FdEnvelope handles CmdType=0 (Authenticate).
            // Each branch duplicates a named CmdType handler above. Track hits to confirm removal.
            null -> {
                if (json == null) return
                when {
                    "ClientVersion" in json || "Token" in json -> {
                        log.info("Front Door: auth (fallback, no CmdType) — txId={}", txId)
                        val sessionId = UUID.randomUUID().toString()
                        writer.send(ctx, txId, FdResponse.Json("""{"SessionId":"$sessionId","Attached":true}"""))
                    }
                    "GraphId" in json -> {
                        log.info("Front Door: graph (fallback, no CmdType) — txId={}", txId)
                        handleGraphRequest(ctx, txId, json)
                    }
                    "AIBotMatch" in json || "PlayQueue" in json -> {
                        log.info("Front Door: AI match (fallback, no CmdType) — txId={}", txId)
                        val pid = playerId ?: PlayerId("anonymous")
                        val match = matchmaking.startAiMatch(pid, DeckId(""))
                        sendMatchCreated(ctx, match)
                    }
                    else -> {
                        log.info("Front Door: unrecognized (no CmdType): {}", json.take(120))
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                }
            }

            else -> {
                log.warn("Front Door: UNHANDLED CmdType {} ({})", cmdType, CmdType.nameOf(cmdType))
                writer.send(ctx, txId, FdResponse.Empty)
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
            writer.send(ctx, txId, FdResponse.Empty)
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
        val json = FdEnvelope.buildMatchCreatedJson(match.matchId, match.host, match.port, eventId = match.eventName)
        log.info("Front Door: pushing MatchCreated matchId={} event={}", match.matchId, match.eventName)
        writer.send(ctx, null, FdResponse.Json(json))
    }

    private fun handleGraphRequest(ctx: ChannelHandlerContext, transactionId: String?, json: String?) {
        val graphId = json?.let { GRAPH_ID_PATTERN.find(it)?.groupValues?.get(1) } ?: "unknown"
        log.info("Front Door: GraphState graphId={}", graphId)
        val response = golden.graphStateResponses[graphId] ?: GRAPH_DEFAULT
        writer.send(ctx, transactionId, FdResponse.Json(response))
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
