package leyline

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.CollationPool
import leyline.domain.PlayerId
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DeckService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool
import leyline.game.data.ExposedCardRepository
import leyline.game.generator.ForgeBoosterDraftDriver
import leyline.game.generator.SealedPoolGenerator
import leyline.infra.AppMatchCoordinator
import leyline.infra.persistence.SqlitePlayerStore
import leyline.webdoor.AuthRateLimitConfig
import leyline.webdoor.DEV_WEB_AUTH_SECRET
import leyline.webdoor.DevEmailSender
import leyline.webdoor.DraftPlayResponse
import leyline.webdoor.EmbeddedWebGreEngineSession
import leyline.webdoor.GreStartRequest
import leyline.webdoor.InMemoryRateLimiter
import leyline.webdoor.InProcessWebGreRelay
import leyline.webdoor.ResendEmailSender
import leyline.webdoor.SqliteWebAuthStore
import leyline.webdoor.WebAuthService
import leyline.webdoor.WebDoorServices
import leyline.webdoor.WebMatchLauncher
import leyline.webdoor.installWebDoor
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.util.UUID

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val port = options["--web-port"]?.toIntOrNull() ?: System.getenv("LEYLINE_WEBDOOR_PORT")?.toIntOrNull() ?: 8080
    val config = MatchConfig.load(options["--config"]?.let(::File) ?: File(System.getProperty("user.dir"), MatchConfig.DEFAULT_FILENAME))
    val cardRepo = ExposedCardRepository(Database.connect("jdbc:sqlite:${resolveCardDb().absolutePath}", "org.sqlite.JDBC"))
    val playerDb = resolvePlayerDb(config)
    val playerDatabase = Database.connect("jdbc:sqlite:${playerDb.absolutePath}", "org.sqlite.JDBC")
    val playerStore = SqlitePlayerStore(playerDatabase).also { it.createTables() }
    val authStore = SqliteWebAuthStore(playerDatabase).also { it.createTables() }
    val defaultPlayerId = PlayerId(System.getenv("LEYLINE_WEB_PLAYER_ID") ?: "web-player")
    playerStore.ensurePlayer(defaultPlayerId, "Web Player")

    val deckService = DeckService(playerStore)
    val sealedPoolGenerator = SealedPoolGenerator(cardRepo)
    val courseService =
        CourseService(playerStore) { setCode ->
            val pool = sealedPoolGenerator.generate(setCode)
            GeneratedPool(
                cards = pool.grpIds,
                byCollation = listOf(CollationPool(pool.collationId, pool.grpIds)),
                collationId = pool.collationId,
            )
        }
    val draftRepo = playerStore.asDraftSessionRepository()
    val draftDriver = ForgeBoosterDraftDriver(cardRepo, config.draft)
    val draftService =
        DraftService(
            draftRepo,
            object : DraftService.Driver {
                override fun start(
                    sessionKey: String,
                    setCode: String,
                ): List<Int> = draftDriver.start(sessionKey, setCode)

                override fun pick(
                    sessionKey: String,
                    grpId: Int,
                ): DraftService.PickOutcome {
                    val result = draftDriver.pick(sessionKey, grpId)
                    return DraftService.PickOutcome(result.packNumber, result.pickNumber, result.nextPack, result.complete)
                }

                override fun complete(sessionKey: String): DraftService.PodOutcome {
                    val result = draftDriver.complete(sessionKey)
                    return DraftService.PodOutcome(result.playerPool, result.botDecks)
                }
            },
        ).also { it.discardIncompleteSessions() }
    val coordinator = AppMatchCoordinator(defaultPlayerId, deckService, courseService, draftRepo, cardRepo::findNameByGrpId)
    val relay = InProcessWebGreRelay()
    val runtimeMatches = RuntimeMatchConfigRegistry()
    val launcher =
        WebRuntimeMatchLauncher(
            config = config,
            coordinator = coordinator,
            cardRepo = cardRepo,
            runtimeMatches = runtimeMatches,
            relay = relay,
        )
    val emailSender =
        System
            .getenv("RESEND_API_KEY")
            ?.takeIf { it.isNotBlank() }
            ?.let { ResendEmailSender(it, System.getenv("RESEND_FROM") ?: "login@localhost") }
            ?: DevEmailSender()

    val services =
        WebDoorServices(
            draftService = draftService,
            courseService = courseService,
            deckService = deckService,
            collectionService = CollectionService { cardRepo.findAllGrpIds() },
            cardRepository = cardRepo,
            matchLauncher = launcher,
            greRelay = relay,
            authService =
                WebAuthService(
                    authStore,
                    emailSender,
                    secret = resolveWebAuthSecret(),
                    rateLimiter = InMemoryRateLimiter(),
                    rateLimitConfig = AuthRateLimitConfig.fromEnv(),
                    fixedLoginCode = System.getenv("LEYLINE_WEB_LOGIN_CODE"),
                ),
        )

    embeddedServer(Netty, host = "127.0.0.1", port = port) { installWebDoor(services) }.start(wait = true)
}

private class WebRuntimeMatchLauncher(
    private val config: MatchConfig,
    private val coordinator: AppMatchCoordinator,
    private val cardRepo: ExposedCardRepository,
    private val runtimeMatches: RuntimeMatchConfigRegistry,
    private val relay: InProcessWebGreRelay,
) : WebMatchLauncher {
    override fun launchGreMatch(
        playerId: PlayerId?,
        request: GreStartRequest,
    ): DraftPlayResponse {
        val matchId = request.matchId?.takeIf { it.isNotBlank() } ?: "web-${UUID.randomUUID()}"
        runtimeMatches.configure(
            RuntimeMatchConfig(
                matchId = matchId,
                seat1Deck = request.seat1Deck,
                seat2Deck = request.seat2Deck,
                puzzle = request.puzzle,
                spectatorMode = request.spectatorMode,
            ),
        )
        relay.register(
            matchId,
            EmbeddedWebGreEngineSession(config, coordinator, cardRepo, runtimeMatches),
            ownerPlayerId = playerId,
            publicAccess = playerId == null && request.spectatorMode == true,
        )
        return DraftPlayResponse(matchId, matchId)
    }

    override fun launchCourseMatch(
        playerId: PlayerId,
        eventName: String,
    ): DraftPlayResponse {
        val matchId = "web-${UUID.randomUUID()}"
        val (seat1, seat2) = coordinator.configureCourseMatch(matchId, playerId, eventName)
        runtimeMatches.configure(RuntimeMatchConfig(matchId = matchId, seat1Deck = seat1, seat2Deck = seat2))
        relay.register(matchId, EmbeddedWebGreEngineSession(config, coordinator, cardRepo, runtimeMatches), ownerPlayerId = playerId)
        return DraftPlayResponse(matchId, matchId)
    }
}

private fun resolvePlayerDb(config: MatchConfig): File {
    val path = System.getenv("LEYLINE_PLAYER_DB") ?: config.server.playerDb.ifEmpty { LeylinePaths.PLAYER_DB.absolutePath }
    val file = File(path).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), path) }
    file.parentFile?.mkdirs()
    return file
}

private fun resolveCardDb(): File {
    val explicit = System.getenv("LEYLINE_CARD_DB")?.takeIf { it.isNotBlank() }?.let(::File)
    val detected = explicit ?: detectLocalCardDb()
    requireNotNull(detected) { "Card database not found. Set LEYLINE_CARD_DB." }
    require(detected.exists() && detected.length() > 1_000_000L) { "Card database is missing or too small: ${detected.absolutePath}" }
    return detected
}

private fun resolveWebAuthSecret(): String {
    val secret = System.getenv("LEYLINE_WEB_AUTH_SECRET")?.takeIf { it.isNotBlank() }
    require(secret != null) { "LEYLINE_WEB_AUTH_SECRET is required for the web profile" }
    require(secret != DEV_WEB_AUTH_SECRET) { "LEYLINE_WEB_AUTH_SECRET must not use the dev default" }
    require(secret.length >= 32) { "LEYLINE_WEB_AUTH_SECRET must be at least 32 characters" }
    return secret
}

private fun detectLocalCardDb(): File? {
    val raw = File(System.getProperty("user.home"), "Library/Application Support/com.wizards.mtga/Downloads/Raw")
    return raw
        .listFiles()
        ?.filter { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") && it.length() > 1_000_000L }
        ?.maxByOrNull { it.lastModified() }
}

private fun parseArgs(args: Array<String>): Map<String, String> =
    args
        .toList()
        .chunked(2)
        .filter { it.size == 2 && it[0].startsWith("--") }
        .associate { it[0] to it[1] }
