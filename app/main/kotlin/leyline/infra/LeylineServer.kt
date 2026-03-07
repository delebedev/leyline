package leyline.infra

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.bridge.DeckConverter
import leyline.bridge.DeckLoader
import leyline.bridge.FormatService
import leyline.bridge.GameBootstrap
import leyline.config.MatchConfig
import leyline.debug.DebugCollector
import leyline.debug.DebugEventBus
import leyline.debug.DebugSinkAdapter
import leyline.debug.FdDebugCollector
import leyline.debug.GameStateCollector
import leyline.debug.SessionRecorder
import leyline.frontdoor.FrontDoorHandler
import leyline.frontdoor.FrontDoorReplayStub
import leyline.frontdoor.GoldenData
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.SqlitePlayerStore
import leyline.frontdoor.service.CollectionService
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.GeneratedPool
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.DeckWireBuilder
import leyline.frontdoor.wire.FdResponseWriter
import leyline.match.MatchHandler
import leyline.match.ReplayHandler
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.ClientHeaderPrepender
import leyline.protocol.ClientHeaderStripper
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import java.io.File

/**
 * Client-compatible TLS TCP server — the single entry point for all three operating modes
 * (local/proxy/replay). Each mode assembles a different Netty pipeline per door.
 *
 * **Shared mutable state:** [selectedDeckId] and [selectedEventName] are written by the
 * FD handler (CmdType 612) on the Netty IO thread and read by [MatchHandler] on connect.
 * Marked `@Volatile`; no stronger sync needed because writes always precede reads in the
 * client's connection sequence (FD handshake completes before MD connect).
 *
 * Both doors share the same 6-byte header framing (see [ClientFrameDecoder]).
 */
class LeylineServer(
    private val frontDoorPort: Int = 30010,
    private val matchDoorPort: Int = 30003,
    /** TLS cert+key (PEM). Falls back to self-signed if null. Needed when client validates certs (UnityTls). */
    private val certFile: java.io.File? = null,
    private val keyFile: java.io.File? = null,
    /** Proxy mode: if set, relay to these upstream IPs instead of stubbing. */
    private val upstreamFrontDoor: String? = null,
    private val upstreamMatchDoor: String? = null,
    /** Replay mode: if set, replay recorded payloads from this directory. */
    private val replayDir: File? = null,
    /** FD golden file: if set, use replay-based FD stub instead of hand-crafted. */
    val fdGoldenFile: File? = null,
    /** Playtest configuration (decks, seed, die roll, AI speed). */
    val matchConfig: MatchConfig = MatchConfig(),
    /** Puzzle mode: if set, load this .pzl file for all client connections. */
    val puzzleFile: File? = null,
    /** External hostname for MatchCreated push (client connects here for MD). Defaults to localhost. */
    private val externalHost: String = "localhost",
    /** Card data repository — passed to MatchHandler for grpId↔name lookups. */
    private val cardRepo: leyline.game.CardRepository,
    /** Resolved player database file (may not exist yet — startLocal handles missing DB). */
    private val playerDbFile: File,
) {
    private val log = LoggerFactory.getLogger(LeylineServer::class.java)

    /** Hardcoded player ID — matches seed-db. */
    private val playerId = "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"

    /** Shared deck selection: FD writes (on 612), MH reads (on ConnectReq). */
    @Volatile
    var selectedDeckId: String? = null

    /** Shared event selection: FD writes (on 612), MH reads (on ConnectReq). */
    @Volatile
    var selectedEventName: String? = null

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    @Volatile private var frontDoorChannel: Channel? = null

    @Volatile private var matchDoorChannel: Channel? = null

    // --- Debug infrastructure (wired in start()) ---
    val eventBus = DebugEventBus()
    val fdCollector = FdDebugCollector(eventBus)
    val captureSink = CaptureSink(fdCollector)
    val debugCollector = DebugCollector(eventBus)
    val gameStateCollector = GameStateCollector(cardRepo, eventBus)
    val recordingInspector = leyline.recording.RecordingInspector(
        cardNameLookup = { grpId: Int -> cardRepo.findNameByGrpId(grpId) },
    )

    /** Proxy: relay both FD + MD to real Arena servers for traffic capture. */
    val isProxy get() = upstreamFrontDoor != null && upstreamMatchDoor != null && replayDir == null

    /** Replay: stub FD, replay recorded bytes on MD. */
    val isReplay get() = replayDir != null

    /** Health probe: true when both server channels are bound and active. */
    fun isHealthy(): Boolean {
        val fd = frontDoorChannel
        val md = matchDoorChannel
        return fd != null && fd.isActive && md != null && md.isActive
    }

    fun start() {
        // Register global instance for logback appender (must happen before any logging)
        DebugCollector.instance = debugCollector

        // Configure proto dump output directory
        leyline.protocol.ProtoDump.engineDumpDir = leyline.LeylinePaths.ENGINE_DUMP

        // Eagerly initialize Forge card DB on main thread — avoids race when
        // multiple Netty threads hit GameBridge.start() concurrently.
        GameBootstrap.initializeCardDatabase()

        val ssl = buildSslContext()
        when {
            isReplay -> startReplay(ssl, ssl)
            isProxy -> startProxy(ssl, ssl)
            else -> startLocal(ssl, ssl)
        }
    }

    private fun buildSslContext(): SslContext = if (certFile != null && keyFile != null) {
        log.info("Loading TLS cert={} key={}", certFile, keyFile)
        SslContextBuilder.forServer(certFile, keyFile).build()
    } else {
        log.info("Using self-signed TLS certificate")
        val ssc = SelfSignedCertificate()
        SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
    }

    private fun startLocal(fdSsl: SslContext, mdSsl: SslContext) {
        val hasDb = playerDbFile.exists()
        val resolvedPlayerId = if (hasDb) {
            playerId
        } else {
            log.warn("No player.db found — run `just seed-db` first. Using golden fallback.")
            null
        }

        val db = org.jetbrains.exposed.v1.jdbc.Database.connect(
            if (hasDb) "jdbc:sqlite:${playerDbFile.absolutePath}" else "jdbc:sqlite::memory:",
            "org.sqlite.JDBC",
        )
        val store = SqlitePlayerStore(db)
        store.createTables()
        val deckService = DeckService(store)
        val playerService = PlayerService(store)
        val sealedPoolGen = leyline.game.SealedPoolGenerator(cardRepo)
        val courseService = CourseService(store) { setCode ->
            val pool = sealedPoolGen.generate(setCode)
            GeneratedPool(
                cards = pool.grpIds,
                byCollation = listOf(CollationPool(pool.collationId, pool.grpIds)),
                collationId = pool.collationId,
            )
        }
        val validateDeck = buildDeckValidator(cardRepo::findNameByGrpId)
        val matchmakingService = MatchmakingService(store, externalHost, matchDoorPort, validateDeck = validateDeck)
        val writer = FdResponseWriter(onFdMessage = fdCollector::record)
        val golden = GoldenData.loadFromClasspath()

        val goldenFile = fdGoldenFile
        if (goldenFile != null) {
            frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor-Replay") { ch ->
                ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
                ch.pipeline().addLast(
                    "handler",
                    FrontDoorReplayStub(
                        goldenFile,
                        matchDoorHost = externalHost,
                        matchDoorPort = matchDoorPort,
                        onFdMessage = fdCollector::record,
                    ),
                )
            }
            log.info("Client Front Door (replay from {}) listening on :{}", goldenFile.name, frontDoorPort)
        } else {
            frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor") { ch ->
                ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
                ch.pipeline().addLast(
                    "handler",
                    FrontDoorHandler(
                        playerId = resolvedPlayerId?.let { PlayerId(it) },
                        deckService = deckService,
                        playerService = playerService,
                        matchmaking = matchmakingService,
                        collectionService = CollectionService { cardRepo.findAllGrpIds() },
                        courseService = courseService,
                        writer = writer,
                        golden = golden,
                        onFdMessage = fdCollector::record,
                        onDeckSelected = { selectedDeckId = it },
                        onEventSelected = { selectedEventName = it },
                    ),
                )
            }
            log.info("Client Front Door (local) listening on :{}", frontDoorPort)
        }

        // Deck lookups for match handler — crosses BC boundary via function, not import.
        //
        // Invisible constraint — ordering: FD handler writes selectedDeckId (via
        // onDeckSelected callback on CmdType 612/603) before the client opens
        // the MD connection. The client's connection sequence guarantees FD
        // handshake completes before MD ConnectReq, so the @Volatile read in
        // MatchHandler always sees the write. No stronger sync needed.
        //
        // Sealed fallback: when deckId isn't in DeckRepository (constructed decks),
        // falls back to scanning CourseService for the first course with a deck.
        // TODO: key on selectedEventName instead of scanning all courses — current
        // approach picks wrong deck if player has multiple sealed courses.
        val deckToJson: (leyline.frontdoor.domain.Deck) -> String = { deck ->
            buildJsonObject {
                put("MainDeck", DeckWireBuilder.cardsToJsonArray(deck.mainDeck))
                put("Sideboard", DeckWireBuilder.cardsToJsonArray(deck.sideboard))
            }.toString()
        }
        val deckLookup: (String) -> String? = { deckId ->
            deckService.getById(DeckId(deckId))?.let(deckToJson)
                ?: resolvedPlayerId?.let { pid ->
                    courseService.getCoursesForPlayer(PlayerId(pid))
                        .firstOrNull { it.deck != null }
                        ?.deck?.let { courseDeck ->
                            buildJsonObject {
                                put("MainDeck", DeckWireBuilder.cardsToJsonArray(courseDeck.mainDeck))
                                put("Sideboard", DeckWireBuilder.cardsToJsonArray(courseDeck.sideboard))
                            }.toString()
                        }
                }
        }
        val deckLookupByName: (String) -> String? = { name ->
            deckService.getByName(name)?.let(deckToJson)
        }

        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
            ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
            ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
            ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender())
            ch.pipeline().addLast("protobufEncoder", ProtobufEncoder())
            ch.pipeline().addLast(
                "handler",
                MatchHandler(
                    matchConfig = matchConfig,
                    puzzleFile = puzzleFile,
                    selectedDeckOverride = { selectedDeckId },
                    selectedEventOverride = { selectedEventName },
                    deckLookup = deckLookup,
                    deckLookupByName = deckLookupByName,
                    cards = cardRepo,
                    debugSink = DebugSinkAdapter(debugCollector, gameStateCollector),
                    recorderFactory = {
                        SessionRecorder(mode = "engine").also { SessionRecorder.register(it) }
                    },
                    onMatchComplete = { won ->
                        val event = selectedEventName
                        if (resolvedPlayerId != null && event != null) {
                            courseService.recordMatchResult(PlayerId(resolvedPlayerId), event, won)
                            log.info("Match result recorded: event={} won={}", event, won)
                        }
                    },
                ),
            )
        }
        log.info("Client Match Door (local) listening on :{}", matchDoorPort)
    }

    private fun startReplay(fdSsl: SslContext, mdSsl: SslContext) {
        val dir = replayDir!!
        require(dir.isDirectory) { "Replay dir does not exist: $dir" }

        // FD local — in-memory SQLite, no deck/player data needed for replay
        val golden = GoldenData.loadFromClasspath()
        val memDb = org.jetbrains.exposed.v1.jdbc.Database.connect("jdbc:sqlite::memory:", "org.sqlite.JDBC")
        val memStore = SqlitePlayerStore(memDb)
        memStore.createTables()
        val memWriter = FdResponseWriter(onFdMessage = fdCollector::record)

        frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
            ch.pipeline().addLast(
                "handler",
                FrontDoorHandler(
                    playerId = null,
                    deckService = DeckService(memStore),
                    playerService = PlayerService(memStore),
                    matchmaking = MatchmakingService(memStore, externalHost, matchDoorPort),
                    collectionService = CollectionService { cardRepo.findAllGrpIds() },
                    writer = memWriter,
                    golden = golden,
                    onFdMessage = fdCollector::record,
                ),
            )
        }
        log.info("Client Front Door (local) listening on :{}", frontDoorPort)

        // Match Door: replay recorded payloads
        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor-Replay") { ch ->
            ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
            ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
            ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
            ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender())
            ch.pipeline().addLast("handler", ReplayHandler(dir))
        }
        log.info("Client Match Door (replay from {}) listening on :{}", dir, matchDoorPort)
    }

    private fun startProxy(fdSsl: SslContext, mdSsl: SslContext) {
        val fdHost = upstreamFrontDoor!!
        val mdHost = upstreamMatchDoor ?: fdHost

        frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor-Proxy") { ch ->
            ch.pipeline().addLast("proxy", ProxyFrontHandler(workerGroup, fdHost, frontDoorPort, "FD", captureSink))
        }
        log.info("Client Front Door (proxy → {}:{}) listening on :{}", fdHost, frontDoorPort, frontDoorPort)

        matchDoorChannel = bindServer(mdSsl, matchDoorPort, "MatchDoor-Proxy") { ch ->
            ch.pipeline().addLast("proxy", ProxyFrontHandler(workerGroup, mdHost, matchDoorPort, "MD", captureSink))
        }
        log.info("Client Match Door (proxy → {}:{}) listening on :{}", mdHost, matchDoorPort, matchDoorPort)
    }

    fun stop() {
        log.info("Shutting down client server")
        frontDoorChannel?.close()?.sync()
        matchDoorChannel?.close()?.sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
        captureSink.close()
    }

    /**
     * Compose DeckConverter + DeckLoader + FormatService into a single validation lambda.
     * Returns null if legal, error string if illegal. Keeps Forge deps out of :frontdoor.
     */
    private fun buildDeckValidator(
        nameByGrpId: (Int) -> String?,
    ): (List<DeckCard>, List<DeckCard>, String) -> String? = { mainDeck, sideboard, formatId ->
        val mainEntries = mainDeck.map { leyline.bridge.CardEntry(it.grpId, it.quantity) }
        val sideEntries = sideboard.map { leyline.bridge.CardEntry(it.grpId, it.quantity) }
        val deckText = DeckConverter.toDeckText(mainEntries, sideEntries, nameByGrpId)
        if (deckText.isBlank()) {
            null
        } else {
            val forgeDeck = DeckLoader.parseDeckList(deckText)
            FormatService.validateDeck(forgeDeck, formatId)
        }
    }

    private fun bindServer(
        sslCtx: SslContext,
        port: Int,
        name: String,
        initChannel: (SocketChannel) -> Unit,
    ): Channel {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addFirst("ssl", sslCtx.newHandler(ch.alloc()))
                    initChannel(ch)
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)

        return bootstrap.bind(port).sync().channel()
    }
}
