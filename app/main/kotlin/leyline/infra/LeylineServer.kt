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
import leyline.DevCheck
import leyline.LeylinePaths
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
import leyline.frontdoor.FrontDoorHandler
import leyline.frontdoor.GoldenData
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.SqlitePlayerStore
import leyline.frontdoor.service.CollectionService
import leyline.frontdoor.service.CourseService
import leyline.frontdoor.service.DeckService
import leyline.frontdoor.service.DraftService
import leyline.frontdoor.service.GeneratedPool
import leyline.frontdoor.service.MatchmakingQueue
import leyline.frontdoor.service.MatchmakingService
import leyline.frontdoor.service.PlayerService
import leyline.frontdoor.wire.FdResponseWriter
import leyline.match.MatchHandler
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.ClientHeaderPrepender
import leyline.protocol.ClientHeaderStripper
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Client-compatible TLS TCP server — local mode only.
 *
 * Cross-BC state (deck/event selection, deck resolution, match results) flows through
 * [AppMatchCoordinator] — both doors receive the same instance.
 *
 * Both doors share the same 6-byte header framing (see [ClientFrameDecoder]).
 */
class LeylineServer(
    private val frontDoorPort: Int = 30010,
    private val matchDoorPort: Int = 30003,
    /** TLS cert+key (PEM). Falls back to self-signed if both null. Needed when client validates certs (UnityTls). */
    private val tlsFiles: Pair<File?, File?> = null to null,
    /** Playtest configuration (decks, seed, die roll, AI speed). */
    val matchConfig: MatchConfig = MatchConfig(),
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

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    @Volatile private var frontDoorChannel: Channel? = null

    @Volatile private var matchDoorChannel: Channel? = null

    // --- Debug infrastructure (wired in start()) ---
    val eventBus = DebugEventBus()
    val fdCollector = FdDebugCollector(eventBus)
    val debugCollector = DebugCollector(eventBus)
    val gameStateCollector = GameStateCollector(cardRepo, eventBus)

    /** Runtime puzzle path — set via debug API, read by PuzzleHandler and createMatchId(). */
    val runtimePuzzle = AtomicReference<String?>(null)

    /** Health probe: true when both server channels are bound and active. */
    fun isHealthy(): Boolean {
        val fd = frontDoorChannel
        val md = matchDoorChannel
        return fd != null && fd.isActive && md != null && md.isActive
    }

    fun start() {
        // Register global instance for logback appender (must happen before any logging)
        DebugCollector.instance = debugCollector

        // Initialize dev-time strict checking from config
        DevCheck.init(matchConfig.dev.strict, matchConfig.dev.strictPass)

        // Configure proto dump output directory
        leyline.protocol.ProtoDump.engineDumpDir = leyline.LeylinePaths.ENGINE_DUMP

        // Eagerly initialize Forge card DB on main thread — avoids race when
        // multiple Netty threads hit GameBridge.start() concurrently.
        GameBootstrap.initializeCardDatabase()

        val ssl = buildSslContext()
        startLocal(ssl, ssl)
    }

    private fun buildSslContext(): SslContext = if (tlsFiles.first != null && tlsFiles.second != null) {
        log.info("Loading TLS cert={} key={}", tlsFiles.first, tlsFiles.second)
        SslContextBuilder.forServer(tlsFiles.first, tlsFiles.second).build()
    } else {
        log.info("Using self-signed TLS certificate")
        val ssc = SelfSignedCertificate()
        SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
    }

    private fun startLocal(fdSsl: SslContext, mdSsl: SslContext) {
        val hasDb = playerDbFile.exists()
        if (!hasDb) log.warn("No player.db found — run `just seed-db` first. Using in-memory DB.")

        val db = org.jetbrains.exposed.v1.jdbc.Database.connect(
            if (hasDb) "jdbc:sqlite:${playerDbFile.absolutePath}" else "jdbc:sqlite::memory:",
            "org.sqlite.JDBC",
        )
        val store = SqlitePlayerStore(db)
        store.createTables()
        val pid = PlayerId(playerId)
        store.ensurePlayer(pid, "Player")
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
        val draftRepo = store.asDraftSessionRepository()
        val draftPackGen = leyline.game.DraftPackGenerator(cardRepo)
        val draftService = DraftService(draftRepo) { setCode ->
            draftPackGen.generate(setCode)
        }
        val validateDeck = buildDeckValidator(cardRepo::findNameByGrpId)
        val matchmakingService = MatchmakingService(
            store,
            externalHost,
            matchDoorPort,
            validateDeck = validateDeck,
            matchIdFactory = ::createMatchId,
        )
        val writer = FdResponseWriter(onFdMessage = fdCollector::record)
        val golden = GoldenData.loadFromClasspath()

        val pvpQueue = MatchmakingQueue(syntheticOpponent = matchConfig.game.syntheticOpponent)

        val coordinator = AppMatchCoordinator(
            playerId = pid,
            deckService = deckService,
            courseService = courseService,
        )

        frontDoorChannel = bindServer(fdSsl, frontDoorPort, "FrontDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
            ch.pipeline().addLast(
                "handler",
                FrontDoorHandler(
                    playerId = pid,
                    deckService = deckService,
                    playerService = playerService,
                    matchmaking = matchmakingService,
                    collectionService = CollectionService { cardRepo.findAllGrpIds() },
                    courseService = courseService,
                    draftService = draftService,
                    writer = writer,
                    golden = golden,
                    onFdMessage = fdCollector::record,
                    coordinator = coordinator,
                    matchmakingQueue = pvpQueue,
                ),
            )
        }
        log.info("Client Front Door (local) listening on :{}", frontDoorPort)

        matchDoorChannel = bindMatchDoor(mdSsl, coordinator)
    }

    private fun createMatchId(eventName: String): String {
        val puzzle = runtimePuzzle.get()
        val matchId = UUID.randomUUID().toString()
        // Puzzle runs are inferred from runtime puzzle injection because Arena
        // currently has no distinct Front Door event for "this is a puzzle".
        val source = if (puzzle != null && eventName == "SparkyStarterDeckDuel") "puzzle" else "leyline"
        val puzzleRef = if (source == "puzzle") File(puzzle).nameWithoutExtension else null
        ScrySessionJournal.record(
            matchId = matchId,
            source = source,
            eventName = eventName,
            puzzleRef = puzzleRef,
        )
        return matchId
    }

    private fun bindMatchDoor(mdSsl: SslContext, coordinator: AppMatchCoordinator): Channel {
        val ch = bindServer(mdSsl, matchDoorPort, "MatchDoor") { ch ->
            ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
            ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
            ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
            ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender())
            ch.pipeline().addLast("protobufEncoder", ProtobufEncoder())
            ch.pipeline().addLast(
                "handler",
                MatchHandler(
                    matchConfig = matchConfig,
                    coordinator = coordinator,
                    cards = cardRepo,
                    debugSink = DebugSinkAdapter(debugCollector, gameStateCollector),
                    puzzlePath = { runtimePuzzle.get() },
                ),
            )
        }
        log.info("Client Match Door (local) listening on :{}", matchDoorPort)
        return ch
    }

    fun stop() {
        log.info("Shutting down client server")
        frontDoorChannel?.close()?.sync()
        matchDoorChannel?.close()?.sync()
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
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
        val deckText = DeckConverter.toDeckText(mainEntries, sideEntries, nameByGrpId = nameByGrpId)
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
