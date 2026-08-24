package leyline.infra

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.pkitesting.CertificateBuilder
import leyline.DevCheck
import leyline.bridge.bootstrap.CardEntry
import leyline.bridge.bootstrap.DeckConverter
import leyline.bridge.bootstrap.DeckLoader
import leyline.bridge.bootstrap.FormatService
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfigRegistry
import leyline.debug.DebugSinkAdapter
import leyline.domain.CollationPool
import leyline.domain.DeckCard
import leyline.domain.PlayerId
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool
import leyline.domain.service.MatchmakingService
import leyline.game.data.CardRepository
import leyline.game.generator.ForgeBoosterDraftDriver
import leyline.game.generator.SealedPoolGenerator
import leyline.infra.persistence.SqlitePlayerStore
import leyline.native.frontdoor.FrontDoorBootstrapData
import leyline.native.frontdoor.FrontDoorHandler
import leyline.native.frontdoor.service.PlayerService
import leyline.native.frontdoor.wire.FdResponseWriter
import leyline.native.matchdoor.NativeMatchDoorBootstrap
import leyline.native.protocol.ClientFrameDecoder
import org.slf4j.LoggerFactory
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
    private val bindAddress: String,
    private val frontDoorPort: Int,
    private val matchDoorPort: Int,
    /** TLS cert+key (PEM). Falls back to self-signed if both null. Needed when client validates certs (UnityTls). */
    private val tlsFiles: Pair<File?, File?> = null to null,
    /** Resolved engine behavior settings (timing, match defaults, draft, diagnostics). */
    private val engineSettings: EngineSettings,
    /** Resolved puzzle library root (content root). */
    private val puzzlesDir: File,
    /** Resolved booster-draft model directory (content-root anchored). */
    private val draftModelDir: File,
    /** External hostname for MatchCreated push (client connects here for MD). Defaults to localhost. */
    private val externalHost: String,
    /** Card data repository — passed to MatchConnection for grpId↔name lookups. */
    val cardRepo: CardRepository,
    /** Resolved player database file (may not exist yet — startLocal handles missing DB). */
    private val playerDbFile: File,
    /** Resolved protocol dump output directory (outbound GRE messages). */
    private val engineDumpDir: File,
    private val sessionJournalFile: File,
) {
    private val log = LoggerFactory.getLogger(LeylineServer::class.java)

    /** Hardcoded player ID — matches seed-db. */
    private val playerId = "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"

    private val bossGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workerGroup = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())

    @Volatile private var frontDoorChannel: Channel? = null

    @Volatile private var matchDoorChannel: Channel? = null

    // --- Debug infrastructure (wired in start()) ---
    val debugSink = DebugSinkAdapter()

    /** Runtime puzzle path — set via debug API, read by PuzzleHandler and createMatchId(). */
    val runtimePuzzle = AtomicReference<String?>(null)
    val runtimeMatchConfigs = RuntimeMatchConfigRegistry()

    /** One-shot seat-2 (AI) deck override by name — set via POST /api/ai-deck, consumed per match. */
    val aiDeckOverride = AtomicReference<String?>(null)

    /** Health probe: true when both server channels are bound and active. */
    fun isHealthy(): Boolean {
        val fd = frontDoorChannel
        val md = matchDoorChannel
        return fd != null && fd.isActive && md != null && md.isActive
    }

    fun start() {
        // Initialize dev-time strict checking from config
        DevCheck.init(engineSettings.dev.strict, engineSettings.dev.strictPass)

        // Configure proto dump output directory
        leyline.protocol.ProtoDump.engineDumpDir = engineDumpDir

        // Initialize engine card DB on a background thread — server accepts connections
        // immediately while the ~2s card parse runs. GameBridge.start() calls
        // initializeCardDatabase() again (idempotent) and blocks until ready; failures
        // propagate via awaitAndRethrow on any subsequent caller.
        Thread({ GameBootstrap.initializeCardDatabase() }, "forge-init").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e ->
                log.error("Card DB init failed — server is listening but match creation will fail", e)
            }
            start()
        }

        val ssl = buildSslContext()
        startLocal(ssl, ssl)
    }

    private fun buildSslContext(): SslContext =
        if (tlsFiles.first != null && tlsFiles.second != null) {
            log.info("Loading TLS cert={} key={}", tlsFiles.first, tlsFiles.second)
            SslContextBuilder.forServer(tlsFiles.first, tlsFiles.second).build()
        } else {
            log.info("Using self-signed TLS certificate")
            val certificate =
                CertificateBuilder()
                    .subject("CN=localhost")
                    .addSanDnsName("localhost")
                    .addExtendedKeyUsageServerAuth()
                    .setIsCertificateAuthority(true)
                    .buildSelfSigned()
            SslContextBuilder.forServer(certificate.keyPair.private, certificate.certificate).build()
        }

    private fun startLocal(
        fdSsl: SslContext,
        mdSsl: SslContext,
    ) {
        // Always use the persistent player database: create the file and schema
        // when missing. The former in-memory fallback never worked (each pooled
        // SQLite `:memory:` connection is a separate empty database) and blocked
        // fresh state paths such as additional instances.
        playerDbFile.parentFile?.mkdirs()
        if (!playerDbFile.exists()) {
            log.info("No player.db at {} — creating a fresh persistent database", playerDbFile.absolutePath)
        }
        val db =
            org.jetbrains.exposed.v1.jdbc.Database.connect(
                "jdbc:sqlite:${playerDbFile.absolutePath}",
                "org.sqlite.JDBC",
            )
        val store = SqlitePlayerStore(db)
        store.createTables()
        val pid = PlayerId(playerId)
        store.ensurePlayer(pid, "Player")
        val deckService = DeckService(store)
        val playerService = PlayerService(store)
        val sealedPoolGen = SealedPoolGenerator(cardRepo::findGrpIdByName)
        val courseService =
            CourseService(store) { setCode ->
                val pool = sealedPoolGen.generate(setCode)
                GeneratedPool(
                    cards = pool.grpIds,
                    byCollation = listOf(CollationPool(pool.collationId, pool.grpIds)),
                    collationId = pool.collationId,
                )
            }
        val draftRepo = store.asDraftSessionRepository()
        val forgeDriver =
            ForgeBoosterDraftDriver(cardRepo::findGrpIdByName, engineSettings.draft.copy(modelDir = draftModelDir.absolutePath))
        val draftService =
            DraftService(
                draftRepo,
                object : DraftService.Driver {
                    override fun start(
                        sessionKey: String,
                        setCode: String,
                    ): List<Int> = forgeDriver.start(sessionKey, setCode)

                    override fun pick(
                        sessionKey: String,
                        grpId: Int,
                    ): DraftService.PickOutcome {
                        val r = forgeDriver.pick(sessionKey, grpId)
                        return DraftService.PickOutcome(
                            packNumber = r.packNumber,
                            pickNumber = r.pickNumber,
                            nextPack = r.nextPack,
                            complete = r.complete,
                        )
                    }

                    override fun complete(sessionKey: String): DraftService.PodOutcome {
                        val r = forgeDriver.complete(sessionKey)
                        return DraftService.PodOutcome(
                            playerPool = r.playerPool,
                            botDecks = r.botDecks,
                        )
                    }
                },
                courseService,
            )
        draftService.discardIncompleteSessions()
        val validateDeck = buildDeckValidator(cardRepo::findNameByGrpId)
        val matchmakingService =
            MatchmakingService(
                store,
                externalHost,
                matchDoorPort,
                validateDeck = validateDeck,
                matchIdFactory = ::createMatchId,
            )
        val writer = FdResponseWriter()
        val bootstrapData = FrontDoorBootstrapData.loadFromClasspath()

        val coordinator =
            AppMatchCoordinator(
                playerId = pid,
                deckService = deckService,
                courseService = courseService,
                draftRepo = draftRepo,
                nameByGrpId = cardRepo::findNameByGrpId,
            )
        frontDoorChannel =
            bindServer(fdSsl, frontDoorPort) { ch ->
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
                        bootstrapData = bootstrapData,
                        coordinator = coordinator,
                    ),
                )
            }
        log.info("Client Front Door listening on {}:{}", bindAddress, frontDoorPort)

        matchDoorChannel = bindMatchDoor(mdSsl, coordinator)
    }

    private fun createMatchId(eventName: String): String {
        val puzzle = runtimePuzzle.get()
        val matchId = UUID.randomUUID().toString()
        // Puzzle runs are inferred from runtime puzzle injection because Arena
        // currently has no distinct Front Door event for "this is a puzzle".
        val puzzleEvent = eventName == "SparkyStarterDeckDuel" || eventName == "AIBotMatch"
        val source = if (puzzle != null && puzzleEvent) "puzzle" else "leyline"
        val puzzleRef = if (source == "puzzle") File(puzzle).nameWithoutExtension else null
        ScrySessionJournal.record(
            journalPath = sessionJournalFile.toPath(),
            matchId = matchId,
            source = source,
            eventName = eventName,
            puzzleRef = puzzleRef,
        )
        return matchId
    }

    private fun bindMatchDoor(
        mdSsl: SslContext,
        coordinator: AppMatchCoordinator,
    ): Channel {
        val ch =
            NativeMatchDoorBootstrap.bind(
                bossGroup = bossGroup,
                workerGroup = workerGroup,
                ssl = mdSsl,
                bindAddress = bindAddress,
                port = matchDoorPort,
                engineSettings = engineSettings,
                puzzlesDir = puzzlesDir,
                coordinator = coordinator,
                cardRepository = cardRepo,
                debugSink = debugSink,
                puzzlePath = { runtimePuzzle.get() },
                runtimeMatchConfigs = runtimeMatchConfigs,
                aiDeckNameOverride = { aiDeckOverride.getAndSet(null) },
            )
        log.info("Client Match Door listening on {}:{}", bindAddress, matchDoorPort)
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
     * Returns null if legal, error string if illegal. Keeps engine deps behind the native composition layer.
     */
    private fun buildDeckValidator(nameByGrpId: (Int) -> String?): (List<DeckCard>, List<DeckCard>, String) -> String? =
        { mainDeck, sideboard, formatId ->
            val mainEntries = mainDeck.map { CardEntry(it.grpId, it.quantity) }
            val sideEntries = sideboard.map { CardEntry(it.grpId, it.quantity) }
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
        initChannel: (SocketChannel) -> Unit,
    ): Channel {
        val bootstrap =
            ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(
                    object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().addFirst("ssl", sslCtx.newHandler(ch.alloc()))
                            initChannel(ch)
                        }
                    },
                ).option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

        return bootstrap.bind(bindAddress, port).sync().channel()
    }
}
