package leyline.frontdoor

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseModule
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
import leyline.frontdoor.service.MatchCoordinator
import leyline.frontdoor.service.MatchmakingQueue
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PairResult
import leyline.frontdoor.service.PairingEntry
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
import leyline.frontdoor.wire.StartHookBuilder
import org.slf4j.LoggerFactory
import java.util.Locale
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
    private val playerId: PlayerId,
    private val deckService: DeckService,
    private val playerService: PlayerService,
    private val matchmaking: MatchmakingService,
    private val collectionService: CollectionService,
    private val courseService: CourseService,
    private val draftService: DraftService,
    private val writer: FdResponseWriter,
    private val golden: GoldenData,
    private val onFdMessage: ((String, FdEnvelope.FdMessage) -> Unit)? = null,
    private val coordinator: MatchCoordinator = MatchCoordinator.NOOP,
    private val matchmakingQueue: MatchmakingQueue? = null,
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
            "Front Door: loaded golden data — formats={}B sets={}B",
            golden.getFormatsProto.size,
            golden.getSetsProto.size,
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
            log.debug("Front Door: frame type=0x{} size={}", String.format(Locale.ROOT, "%02x", frameType), bytes.size)

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
        CmdType.CAROUSEL_GET_ITEMS.value to { FdResponse.Json("[]") },
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
                val session = playerService.authenticate(playerId, "Player")
                writer.send(ctx, txId, FdResponse.Json("""{"SessionId":"${session.value}","Attached":true}"""))
            }

            CmdType.START_HOOK.value -> {
                val decks = deckService.listForPlayer(playerId)
                val hook = StartHookBuilder.build(decks)
                log.info("Front Door: StartHook ({}B, {} decks)", hook.length, decks.size)
                writer.send(ctx, txId, FdResponse.Json(hook))
            }

            CmdType.GRAPH_GET_STATE.value -> handleGraphRequest(ctx, txId, json)

            CmdType.GRAPH_ADVANCE_NODE.value -> handleGraphRequest(ctx, txId, json)

            CmdType.EVENT_AI_BOT_MATCH.value -> {
                val req = FdRequests.parseAiBotMatch(json)
                val deckId = req?.deckId
                if (deckId != null) coordinator.selectDeck(deckId)
                coordinator.selectEvent("AIBotMatch")
                val match = matchmaking.startAiMatch(playerId, DeckId(deckId ?: ""), "AIBotMatch")
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
                val homeEvents = EventRegistry.activeEvents
                val eventsJson = EventWireBuilder.toActiveEventsJson(homeEvents, EventRegistry.aiBotMatches)
                log.info("Front Door: ActiveEventsV2 ({} events, {} aiBotMatches)", homeEvents.size, EventRegistry.aiBotMatches.size)
                writer.send(ctx, txId, FdResponse.Json(eventsJson))
            }

            CmdType.EVENT_GET_COURSES_V2.value -> {
                log.info("Front Door: Event_GetCoursesV2")
                val courses = courseService.getCoursesForPlayer(playerId)
                    .filter { it.module != CourseModule.BotDraft } // real server hides draft-in-progress courses
                val realEventNames = courses.map { it.eventName }.toSet()
                val defaultJson = EventRegistry.defaultCourses
                    .filter { it.first !in realEventNames }
                writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.toMergedCoursesJson(courses, defaultJson)))
            }

            CmdType.GET_PLAYER_PREFERENCES.value -> {
                val prefs = playerService.getPreferences(playerId)
                val raw = prefs?.json?.takeIf { it != "{}" }
                log.info("Front Door: PlayerPreferences ({})", if (raw != null) "db" else "empty")
                writer.send(ctx, txId, FdResponse.Json(raw ?: """{"Preferences":{}}"""))
            }

            CmdType.SET_PLAYER_PREFERENCES.value -> {
                requireJson(ctx, txId, json) { body ->
                    val cleaned = PlayerWireBuilder.parsePreferences(body)
                    playerService.savePreferences(playerId, Preferences(cleaned))
                    log.info("Front Door: SetPlayerPreferences saved")
                    writer.send(ctx, txId, FdResponse.Json("{}"))
                }
            }

            CmdType.DECK_DELETE.value -> {
                val req = FdRequests.parseDeleteDeck(json)
                if (req != null) {
                    deckService.delete(DeckId(req.deckId))
                    log.info("Front Door: Deck_DeleteDeck '{}'", req.deckId)
                }
                writer.send(ctx, txId, FdResponse.Json("Success"))
            }

            CmdType.DECK_UPSERT_V2.value -> {
                requireJson(ctx, txId, json) { body ->
                    val savedDeck = DeckWireBuilder.parseDeckUpdate(body, playerId)
                    val resp = if (savedDeck != null) {
                        deckService.save(savedDeck)
                        log.info("Front Door: Deck_UpsertDeckV2 saved '{}'", savedDeck.name)
                        val summary = DeckWireBuilder.toV2Summary(savedDeck)
                        buildJsonObject { put("Summary", summary) }
                    } else {
                        log.warn("Front Door: Deck_UpsertDeckV2 parse failed")
                        buildJsonObject {}
                    }
                    writer.send(ctx, txId, FdResponse.Json(lenientJson.encodeToString(JsonObject.serializer(), resp)))
                }
            }

            CmdType.DECK_GET_SUMMARIES_V2.value -> {
                val decks = deckService.listForPlayer(playerId)
                val summaries = buildJsonArray { decks.forEach { add(DeckWireBuilder.toV2Summary(it)) } }
                log.info("Front Door: DeckSummariesV2 ({} decks)", decks.size)
                val resp = buildJsonObject { put("Summaries", summaries) }
                writer.send(ctx, txId, FdResponse.Json(lenientJson.encodeToString(JsonObject.serializer(), resp)))
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
                if (eventName != null) {
                    val course = courseService.join(playerId, eventName)
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildJoinResponse(course)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_DROP.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_Drop event={}", req?.eventName)
                if (req?.eventName != null) {
                    try {
                        val course = courseService.drop(playerId, req.eventName)
                        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
                    } catch (e: IllegalArgumentException) {
                        log.debug("Front Door: Event_Drop failed: {}", e.message)
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

                try {
                    if (eventName != null) coordinator.selectEvent(eventName)

                    // Resolve deck: course-based (sealed/draft) or selected (constructed)
                    val course = if (eventName != null) courseService.getCourse(playerId, eventName) else null
                    val courseDeckId = course?.deck?.deckId?.value
                    val deckId = courseDeckId ?: eventName?.let { selectedDeckByEvent[it] }
                    if (deckId != null) coordinator.selectDeck(deckId)

                    // Ack immediately — spinner shows while waiting for MatchCreated push
                    writer.send(ctx, txId, FdResponse.Json("""{"CurrentModule":"CreateMatch","Payload":"Success"}"""))

                    if (matchmakingQueue != null) {
                        // PvP queue path — all 603 events go through the queue
                        val evName = eventName ?: ""
                        val entry = PairingEntry(
                            screenName = playerId.value,
                            pushCallback = { matchId, yourSeat ->
                                val info = MatchInfo(matchId, matchmaking.matchDoorHost, matchmaking.matchDoorPort, evName)
                                sendMatchCreated(ctx, info, yourSeat)
                            },
                            matchId = matchmaking.createMatchId(evName),
                        )
                        when (val result = matchmakingQueue.pair(entry)) {
                            is PairResult.Waiting -> log.info("Front Door: {} entered queue", playerId.value)
                            is PairResult.Paired -> {
                                if (!result.synthetic) coordinator.registerPvpMatch(result.matchId, result.seat2.screenName)
                                result.seat1.pushCallback(result.matchId, 1)
                                result.seat2.pushCallback(result.matchId, 2)
                            }
                        }
                    } else {
                        // No queue — direct match (bot match, sealed, draft)
                        val match = if (courseDeckId != null) {
                            matchmaking.createMatchInfo(eventName ?: "")
                        } else {
                            matchmaking.startMatch(playerId, DeckId(deckId ?: ""), eventName ?: "")
                        }
                        sendMatchCreated(ctx, match)
                    }
                } catch (e: IllegalArgumentException) {
                    log.warn("Front Door: Event_EnterPairing rejected — {}", e.message)
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_LEAVE_PAIRING.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_LeavePairing event={}", req?.eventName)
                matchmakingQueue?.cancel(playerId.value)
                writer.send(ctx, txId, FdResponse.Empty)
            }

            CmdType.EVENT_RESIGN.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_Resign event={}", req?.eventName)
                if (req?.eventName != null) {
                    try {
                        if (EventRegistry.isDraft(req.eventName)) {
                            draftService.drop(playerId, req.eventName)
                        }
                        val course = courseService.drop(playerId, req.eventName)
                        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
                    } catch (e: IllegalArgumentException) {
                        log.debug("Front Door: Event_Resign failed: {}", e.message)
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_GET_MATCH_RESULT.value -> {
                val req = FdRequests.parseMatchResult(json)
                log.info("Front Door: Event_GetMatchResultReport event={}", req?.eventName)
                val eventName = req?.eventName
                val course = if (eventName != null) courseService.getCourse(playerId, eventName) else null
                if (course != null) {
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildMatchResultReport(course)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_SET_JUMPSTART_PACKET.value -> {
                error("EVENT_SET_JUMPSTART_PACKET: not supported — should not be reachable in local mode")
            }

            CmdType.BOT_DRAFT_START.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: BotDraft_StartDraft event={}", eventName)
                if (eventName != null) {
                    val session = draftService.startDraft(playerId, eventName)
                    writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.BOT_DRAFT_PICK.value -> {
                val req = FdRequests.parseDraftPick(json)
                log.info("Front Door: BotDraft_DraftPick card={} pack={} pick={}", req?.cardId, req?.packNumber, req?.pickNumber)
                if (req != null) {
                    val session = draftService.pick(playerId, req.eventName, req.cardId, req.packNumber, req.pickNumber)
                    if (session.status == DraftStatus.Completed) {
                        val collationId = EventRegistry.findEvent(req.eventName)?.collationId ?: 0
                        courseService.completeDraft(playerId, req.eventName, session.pickedCards, collationId)
                    }
                    writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.BOT_DRAFT_STATUS.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: BotDraft_DraftStatus event={}", eventName)
                if (eventName != null) {
                    val session = draftService.getStatus(playerId, eventName)
                    if (session != null) {
                        writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                    } else {
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            // Stub: client sends these after last BotDraft pick as fallback when our
            // completed-draft response doesn't fully convince it the draft ended.
            // Real server never receives them during BotDraft (see proxy recording
            // 2026-03-07_17-57-22, seq 301-304). Needs investigation into response
            // shape difference vs real server.
            CmdType.EVENT_PLAYER_DRAFT_CONFIRM_CARD_POOL_GRANT.value,
            CmdType.DRAFT_COMPLETE_DRAFT.value,
            -> {
                log.warn("Front Door: stub no-op for CmdType {} ({})", cmdType, CmdType.nameOf(cmdType))
                writer.send(ctx, txId, FdResponse.Empty)
            }

            CmdType.EVENT_SET_DECK_V2.value -> {
                val req = FdRequests.parseSetDeck(json)
                if (req != null && req.deckId != null) {
                    selectedDeckByEvent[req.eventName] = req.deckId
                }
                log.info("Front Door: Event_SetDeckV2 event={} deck={}", req?.eventName, req?.deckId)
                if (req != null) {
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
                        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
                    } catch (e: IllegalArgumentException) {
                        log.warn("Front Door: Event_SetDeckV2 failed: {}", e.message)
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
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
                        val match = matchmaking.startAiMatch(playerId, DeckId(""))
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

    private fun sendMatchCreated(ctx: ChannelHandlerContext, match: MatchInfo, yourSeat: Int = 1) {
        val matchType = if (yourSeat > 1) "Queue" else "Familiar"

        // Resolve commander grpIds for Brawl events (feeds VSScreen commander reveal).
        // AI mirrors seat 1's deck (same commander) — seat 2 gets the same grpIds.
        val commanderGrpIds = coordinator.selectedDeckId?.let { deckId ->
            deckService.getById(DeckId(deckId))?.commandZone?.map { it.grpId }
        } ?: emptyList()

        val playerInfos = if (commanderGrpIds.isNotEmpty()) {
            listOf(
                FdEnvelope.PlayerInfo(seatId = 1, teamId = 1, name = "ForgePlayer", commanderGrpIds = commanderGrpIds),
                FdEnvelope.PlayerInfo(seatId = 2, teamId = 2, name = "Sparky", commanderGrpIds = commanderGrpIds),
            )
        } else {
            null
        }

        val json = FdEnvelope.buildMatchCreatedJson(
            match.matchId,
            match.host,
            match.port,
            matchType = matchType,
            yourSeat = yourSeat,
            eventId = match.eventName,
            playerInfos = playerInfos,
        )
        log.info("Front Door: pushing MatchCreated matchId={} event={} seat={} commanders={}", match.matchId, match.eventName, yourSeat, commanderGrpIds.size)
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

        /** Fallback for unknown graph IDs — not expected in normal flow. Known graphs need real state files. */
        private const val GRAPH_DEFAULT = """{"NodeStates":{},"MilestoneStates":{}}"""
    }
}
