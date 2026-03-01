package forge.nexus.server

import forge.nexus.config.PlaytestConfig
import forge.nexus.debug.GameStateCollector
import forge.nexus.debug.NexusDebugCollector
import forge.nexus.debug.NexusTap
import forge.nexus.debug.SessionRecorder
import forge.nexus.game.GameBridge
import forge.nexus.game.GsmBuilder
import forge.nexus.game.PuzzleSource
import forge.nexus.protocol.HandshakeMessages
import forge.nexus.protocol.ProtoDump
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Match Door handler (port 30003) — thin Netty adapter.
 *
 * Pre-mulligan messages (auth, connect, room state, deal hand, mulligan) are handled
 * here using template-based senders. Post-mulligan game orchestration is delegated
 * to [MatchSession].
 */
class MatchHandler(
    private val registry: MatchRegistry = defaultRegistry,
    private val playtestConfig: PlaytestConfig = PlaytestConfig(),
    /** CLI --puzzle override: forces puzzle mode for all connections. */
    private val puzzleFile: java.io.File? = null,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private val log = LoggerFactory.getLogger(MatchHandler::class.java)

    private var mulliganCount = 0
    private var matchId = "forge-match-1"
    private var clientId = "forge-player-1"
    private var seatId = 1

    private var seat1Hand: List<Int> = emptyList()
    private var seat2Hand: List<Int> = emptyList()

    /** Netty context — saved for template senders and cross-connection calls. */
    private var nettyCtx: ChannelHandlerContext? = null

    /** Game session — created on connect, holds all post-mulligan state. */
    internal var session: MatchSession? = null

    companion object {
        val defaultRegistry = MatchRegistry()

        init {
            // Wire debug collector's bridge provider to avoid debug→server import cycle.
            NexusDebugCollector.bridgeProvider = { defaultRegistry.activeBridges() }
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        NexusTap.inbound(msg.clientToMatchServiceMessageType, msg.requestId)
        NexusDebugCollector.recordInbound(msg)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleMatchAuth(ctx, msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleMatchDoorConnect(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGREMessage(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGreuimessage -> handleGREMessage(ctx, msg)
            else -> log.warn("Match Door: unhandled type: {}", msg.clientToMatchServiceMessageType)
        }
    }

    private fun handleMatchAuth(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        clientId = authReq.clientId.ifEmpty { "forge-player-$seatId" }
        val playerName = authReq.playerName.ifEmpty { "ForgePlayer" }
        log.info("Match Door: auth clientId={} playerName={}", clientId, playerName)

        val resp = MatchServiceToClientMessage.newBuilder()
            .setRequestId(msg.requestId)
            .setAuthenticateResponse(
                AuthenticateResponse.newBuilder()
                    .setClientId(clientId)
                    .setSessionId("forge-session-1")
                    .setScreenName(playerName),
            )
            .build()
        ProtoDump.dump(resp, "AuthResp")
        ctx.writeAndFlush(resp)
    }

    private fun handleMatchDoorConnect(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        if (connectReq.matchId.isNotEmpty()) matchId = connectReq.matchId
        log.info("Match Door: connect matchId={}", matchId)

        if (!connectReq.clientToGreMessageBytes.isEmpty) {
            val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
            if (greMsg.systemSeatId > 0) seatId = greMsg.systemSeatId
            log.info("Match Door: detected seatId={}", seatId)
            nettyCtx = ctx

            // Create session + sink + recorder
            // Seat 2 (Familiar) sink skips ProtoDump to avoid duplicate .bin files
            val sink = NettyMessageSink(ctx, dumpEnabled = seatId == 1)
            val rec = SessionRecorder(mode = "engine")
            SessionRecorder.register(rec)
            val s = MatchSession(seatId, matchId, sink, registry, recorder = rec)
            s.playerId = clientId.removeSuffix("_Familiar")
            session = s
            registry.registerSession(matchId, seatId, s)
            registry.registerHandler(matchId, seatId, this)

            processGREMessage(ctx, greMsg)
        }
    }

    private fun handleGREMessage(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        processGREMessage(ctx, ClientToGREMessage.parseFrom(msg.payload))
    }

    private fun processGREMessage(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        NexusTap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)
        val s = session

        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> {
                // Evict stale bridges from previous matches and reset debug collectors
                val evicted = registry.evictStale(matchId)
                if (evicted.isNotEmpty()) {
                    evicted.forEach { it.shutdown() }
                    NexusDebugCollector.clear()
                    GameStateCollector.clear()
                    log.info("Match Door: evicted {} stale bridge(s)", evicted.size)
                }

                if (isPuzzleMatch(matchId)) {
                    // Puzzle mode: create bridge with puzzle game, skip mulligan
                    val bridge = registry.getOrCreateBridge(matchId) {
                        GameBridge(playtestConfig = playtestConfig, messageCounter = s!!.counter).also {
                            val puzzle = loadPuzzleForMatch(matchId)
                            it.startPuzzle(puzzle)
                        }
                    }
                    s?.connectBridge(bridge)
                    log.info("Match Door: puzzle mode, seat {} connected", seatId)
                    sendRoomState(ctx)
                    sendPuzzleInitialBundle(ctx)
                } else {
                    // Constructed mode: normal flow
                    val bridge = registry.getOrCreateBridge(matchId) {
                        GameBridge(playtestConfig = playtestConfig, messageCounter = s!!.counter).also {
                            it.start(
                                seed = playtestConfig.game.seed,
                                deckList1 = loadDeckFromConfig(playtestConfig.decks.seat1),
                                deckList2 = loadDeckFromConfig(playtestConfig.decks.seat2),
                            )
                        }
                    }
                    s?.connectBridge(bridge)
                    seat1Hand = bridge.getHandGrpIds(1)
                    seat2Hand = bridge.getHandGrpIds(2)
                    log.info("Match Door: seat {} connected, hands seat1={} seat2={}", seatId, seat1Hand, seat2Hand)
                    sendRoomState(ctx)
                    sendInitialBundle(ctx)
                }
            }

            ClientMessageType.ChooseStartingPlayerResp_097b -> {
                if (s?.gameBridge?.isPuzzle == true) {
                    log.info("Match Door GRE: ignoring ChooseStartingPlayerResp for puzzle")
                } else if (playtestConfig.game.skipMulligan) {
                    // Skip mulligan: send DealHand (client needs the hand) but no MulliganReq.
                    // Engine auto-kept via MulliganBridge(autoKeep=true), go straight to game.
                    log.info("Match Door GRE: skipMulligan — bypassing mulligan phase")
                    sendDealHand(ctx) // this handler's seat (2): DealHand only
                    val seat1Handler = registry.getHandler(matchId, 1)
                    seat1Handler?.sendDealHand() // seat 1's handler: DealHand only
                    // Enter game loop via seat 1's session so bundles go to the human player
                    seat1Handler?.session?.onMulliganKeep()
                } else {
                    log.info("Match Door GRE: seat {} chose starting player", seatId)
                    sendDealHandAndMulligan(ctx) // seat 2: DealHand + MulliganReq
                    // Cross-connection: find seat 1's handler to send DealHand + MulliganReq
                    val seat1Handler = registry.getHandler(matchId, 1)
                    if (seat1Handler != null) {
                        seat1Handler.sendDealHand()
                        seat1Handler.sendMulliganReq()
                    } else {
                        log.warn("Match Door: seat 1 peer not found for matchId={}", matchId)
                    }
                }
            }

            ClientMessageType.MulliganResp_097b -> {
                if (s?.gameBridge?.isPuzzle == true) {
                    log.info("Match Door GRE: ignoring MulliganResp for puzzle")
                } else {
                    val decision = greMsg.mulliganResp.decision
                    log.info("Match Door GRE: seat {} mulligan decision={}", seatId, decision)
                    val bridge = s?.gameBridge
                    if (seatId == 2) {
                        // Familiar responded — ignored
                    } else if (decision == MulliganOption.AcceptHand) {
                        bridge?.submitKeep(seatId)
                        // Forge London: tuck already happened during mull (auto-tuck in submitMull).
                        // After keep, engine proceeds to game start.
                        bridge?.awaitPriority()
                        s?.onMulliganKeep()
                    } else {
                        mulliganCount++
                        bridge?.submitMull(seatId)
                        // Nuke-and-repave: delete all old instanceIds, allocate fresh.
                        // Real server does this on every mull — client forgets old face-up data.
                        val deletedIds = bridge?.ids?.resetAll() ?: emptyList()
                        seat1Hand = bridge?.getHandGrpIds(1) ?: emptyList()
                        // Auto-tuck already happened inside submitMull (Forge's London model).
                        // Send DealHand with deletedIds + MulliganReq with mulliganCount=0
                        // so the client doesn't double-count the tuck when labeling "Keep X".
                        sendDealHand(ctx, deletedIds)
                        sendMulliganReq(reportedMulliganCount = 0, numCards = seat1Hand.size)
                    }
                }
            }

            ClientMessageType.GroupResp_097b -> {
                if (seatId == 1) {
                    val bridge = s?.gameBridge ?: return
                    // Two groups: [0]=keep (Hand/Top), [1]=tuck (Library/Bottom)
                    val groups = greMsg.groupResp.groupsList
                    val tuckIds = if (groups.size >= 2) groups[1].idsList else groups.firstOrNull()?.idsList ?: emptyList()
                    log.info("Match Door GRE: seat {} GroupResp tuck {} cards", seatId, tuckIds.size)
                    // Map instanceIds → Forge Card objects
                    val handCards = bridge.getHandCards(seatId)
                    val tuckCards = tuckIds.mapNotNull { iid ->
                        val forgeId = bridge.getForgeCardId(iid)
                        handCards.firstOrNull { it.id == forgeId }
                    }
                    bridge.submitTuck(seatId, tuckCards)
                    bridge.awaitPriority()
                    s?.onMulliganKeep()
                }
            }

            ClientMessageType.SetSettingsReq_097b -> {
                s?.onSettings(greMsg)
            }

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                s?.onConcede()
            }

            ClientMessageType.PerformActionResp_097b -> {
                if (seatId == 1) {
                    s?.onPerformAction(greMsg)
                } else {
                    log.debug("Match Door: ignoring PerformActionResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareAttackersResp_097b,
            ClientMessageType.SubmitAttackersReq,
            -> {
                if (seatId == 1) {
                    s?.onDeclareAttackers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareAttackersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareBlockersResp_097b,
            ClientMessageType.SubmitBlockersReq,
            -> {
                if (seatId == 1) {
                    s?.onDeclareBlockers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareBlockersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.SelectTargetsResp_097b -> {
                if (seatId == 1) {
                    s?.onSelectTargets(greMsg)
                }
            }

            ClientMessageType.CancelActionReq_097b -> {
                if (seatId == 1) {
                    s?.onCancelAction(greMsg)
                }
            }

            ClientMessageType.SelectNresp -> {
                if (seatId == 1) {
                    s?.onSelectN(greMsg)
                }
            }

            ClientMessageType.CheckpointReq -> {
                // Client acknowledges IntermissionReq — MatchCompleted room state
                // was already sent in sendGameOver(). Nothing to do here.
                log.info("Match Door GRE: CheckpointReq (post-game acknowledgement)")
            }

            // Cosmetic UI relay (emotes, card hover, pet animations) — no game state impact.
            // In single-player-vs-AI context there's nobody to relay to; silently ignore.
            ClientMessageType.Uimessage_a39e -> { }

            else -> log.warn("Match Door GRE: unhandled type: {}", greMsg.type)
        }
    }

    // --- Template-based senders (pre-mulligan, use ctx directly) ---

    private fun sendRoomState(ctx: ChannelHandlerContext) {
        val playerId = clientId.removeSuffix("_Familiar")
        val msg = HandshakeMessages.roomState(matchId, playerId)
        NexusTap.outboundTemplate("RoomState matchId=$matchId")
        ProtoDump.dump(msg, "RoomState")
        ctx.writeAndFlush(msg)
    }

    private fun sendInitialBundle(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val deckGrpIds = bridge.getDeckGrpIds(seatId)
        val deck = GsmBuilder.buildDeckMessage(deckGrpIds)
        val (msg, nextMsgId) = HandshakeMessages.initialBundle(
            seatId,
            matchId,
            s.counter.currentMsgId(),
            gsId,
            deck,
            bridge,
            dieRollWinner = playtestConfig.game.dieRollWinner,
        )
        s.counter.setMsgId(nextMsgId)
        NexusTap.outboundTemplate("InitialBundle seat=$seatId")
        ProtoDump.dump(msg, "InitialBundle-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** DealHand only (no MulliganReq). Uses stored nettyCtx; public for cross-connection calls. */
    fun sendDealHand() {
        val ctx = nettyCtx ?: return
        sendDealHand(ctx)
    }

    /** DealHand only (no MulliganReq) for this handler's seat, on the given channel. */
    private fun sendDealHand(ctx: ChannelHandlerContext, diffDeletedInstanceIds: List<Int> = emptyList()) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHand(s.counter.currentMsgId(), gsId, bridge, seatId, diffDeletedInstanceIds)
        s.counter.setMsgId(nextMsgId)
        NexusTap.outboundTemplate("DealHand seat=$seatId deletedIds=${diffDeletedInstanceIds.size}")
        ProtoDump.dump(msg, "DealHand-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /**
     * MulliganReq sequence for seat 1.
     *
     * @param reportedMulliganCount mulliganCount for the proto (default: internal counter).
     *   After auto-tuck, pass 0 so the client doesn't double-count the tuck.
     * @param numCards NumberOfCards prompt value (default: 7 for London).
     *   After auto-tuck, pass actual hand size so "Keep X" label is correct.
     */
    fun sendMulliganReq(reportedMulliganCount: Int = mulliganCount, numCards: Int = 7) {
        val ctx = nettyCtx ?: return
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.mulliganReqSeat1(
            s.counter.currentMsgId(),
            gsId,
            bridge,
            mulliganCount = reportedMulliganCount,
            numCards = numCards,
        )
        s.counter.setMsgId(nextMsgId)
        NexusTap.outboundTemplate("MulliganReq seat=$seatId mulliganCount=$reportedMulliganCount numCards=$numCards")
        ProtoDump.dump(msg, "MulliganReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /**
     * GroupReq bundle for London mulligan tuck: GSM Diff + PromptReq + GroupReq.
     * Matches real server's 3-message bundle shape.
     */
    private fun sendGroupReq(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val handCards = bridge.getHandCards(seatId)
        val handInstanceIds = handCards.map { bridge.getOrAllocInstanceId(it.id) }
        val tuckCount = bridge.getTuckCount()
        val (msg, nextMsgId) = HandshakeMessages.groupReqBundle(
            s.counter.currentMsgId(),
            gsId,
            seatId,
            mulliganCount,
            handInstanceIds,
            tuckCount,
            bridge,
        )
        s.counter.setMsgId(nextMsgId)
        NexusTap.outboundTemplate("GroupReq seat=$seatId tuck=$tuckCount")
        ProtoDump.dump(msg, "GroupReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    /** DealHand + MulliganReq bundled (for seat 2). */
    private fun sendDealHandAndMulligan(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val (msg, nextMsgId) = HandshakeMessages.dealHandMulliganSeat2(s.counter.currentMsgId(), gsId, bridge)
        s.counter.setMsgId(nextMsgId)
        NexusTap.outboundTemplate("DealHand+MulliganReq seat=$seatId")
        ProtoDump.dump(msg, "DealHand+MullReq-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client disconnected")
        // Close recorder on disconnect (triggers analysis if game didn't end cleanly)
        session?.recorder?.run {
            close()
            SessionRecorder.unregister(this)
        }
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Match Door error: {}", cause.message, cause)
        session?.recorder?.run {
            close()
            SessionRecorder.unregister(this)
        }
        session?.gameBridge?.shutdown()
        ctx.close()
    }

    // --- Puzzle-mode senders ---

    /** Send puzzle initial bundle: ConnectResp + Full GSM (stage=Play) + ActionsAvailableReq. */
    private fun sendPuzzleInitialBundle(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()

        val (bundleMsg, nextMsgId) = HandshakeMessages.puzzleInitialBundle(
            seatId,
            matchId,
            s.counter.currentMsgId(),
            gsId,
            bridge,
        )
        s.counter.setMsgId(nextMsgId)
        NexusTap.outboundTemplate("PuzzleInitialBundle seat=$seatId")
        ProtoDump.dump(bundleMsg, "PuzzleInitialBundle-seat$seatId")
        ctx.writeAndFlush(bundleMsg)

        // Send ActionsAvailableReq immediately after
        val (actionsMsg, nextMsgId2) = HandshakeMessages.puzzleActionsReq(
            s.counter.currentMsgId(),
            gsId,
            seatId,
            bridge,
        )
        s.counter.setMsgId(nextMsgId2)
        NexusTap.outboundTemplate("PuzzleActionsReq seat=$seatId")
        ProtoDump.dump(actionsMsg, "PuzzleActionsReq-seat$seatId")
        ctx.writeAndFlush(actionsMsg)

        // Enter the game loop — same as onMulliganKeep but without mulligan
        s.onPuzzleStart()
    }

    // --- Puzzle detection ---

    /** Puzzle mode if --puzzle CLI flag is set, or matchId starts with "puzzle-". */
    private fun isPuzzleMatch(matchId: String): Boolean =
        puzzleFile != null || matchId.startsWith("puzzle-")

    /** Load puzzle: prefer --puzzle CLI file, fall back to matchId convention. */
    private fun loadPuzzleForMatch(matchId: String): forge.gamemodes.puzzle.Puzzle {
        // Puzzle constructor triggers GameState.<clinit> which needs localization
        forge.web.game.GameBootstrap.initializeLocalization()

        // CLI override takes precedence
        if (puzzleFile != null) {
            require(puzzleFile.exists()) { "Puzzle file not found: ${puzzleFile.absolutePath}" }
            return PuzzleSource.loadFromFile(puzzleFile.absolutePath)
        }
        // Fall back to matchId convention
        val puzzleName = matchId.removePrefix("puzzle-")
        val nexusDir = findNexusDir()
        val puzzlesDir = java.io.File(nexusDir, "puzzles")
        val pzlFile = java.io.File(puzzlesDir, "$puzzleName.pzl")
        if (pzlFile.exists()) {
            return PuzzleSource.loadFromFile(pzlFile.absolutePath)
        }
        val pzlFile2 = java.io.File(puzzlesDir, puzzleName)
        if (pzlFile2.exists()) {
            return PuzzleSource.loadFromFile(pzlFile2.absolutePath)
        }
        error("Puzzle not found: $puzzleName (looked in ${puzzlesDir.absolutePath})")
    }

    /** Load deck text from a config deck name (resolved from decks/ dir). */
    private fun loadDeckFromConfig(deckName: String): String {
        val nexusDir = findNexusDir()
        val file = PlaytestConfig.resolveDeckFile(deckName, nexusDir)
        return file.readText()
    }

    private fun findNexusDir(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir"))
        if (java.io.File(cwd, "decks").isDirectory) return cwd
        val sub = java.io.File(cwd, "forge-nexus")
        if (sub.isDirectory) return sub
        return cwd
    }
}
