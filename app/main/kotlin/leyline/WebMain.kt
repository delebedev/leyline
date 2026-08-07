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
import leyline.game.data.AutoMappingCardRepository
import leyline.game.data.CardRepository
import leyline.game.data.ExposedCardRepository
import leyline.game.generator.ForgeBoosterDraftDriver
import leyline.game.generator.SealedPoolGenerator
import leyline.infra.AppMatchCoordinator
import leyline.infra.persistence.SqlitePlayerStore
import leyline.web.AuthRateLimitConfig
import leyline.web.DEV_WEB_AUTH_SECRET
import leyline.web.DevEmailSender
import leyline.web.DirectWebGreEngineSession
import leyline.web.DraftPlayResponse
import leyline.web.GreStartRequest
import leyline.web.InProcessWebGreRelay
import leyline.web.LimitedSetView
import leyline.web.ResendEmailSender
import leyline.web.SqliteWebAuthStore
import leyline.web.WebAuthService
import leyline.web.WebMatchLauncher
import leyline.web.WebServices
import leyline.web.installWeb
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.util.UUID

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val port = options["--web-port"]?.toIntOrNull() ?: System.getenv("LEYLINE_WEBDOOR_PORT")?.toIntOrNull() ?: 8080
    val host = options["--web-host"] ?: System.getenv("LEYLINE_WEBDOOR_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    // Browser clients animate on their own; server-side pacing between engine
    // steps only delays frame delivery, so the web profile runs the engine
    // at full speed unless LEYLINE_AI_SPEED asks for pacing.
    val aiSpeed = System.getenv("LEYLINE_AI_SPEED")?.toDoubleOrNull() ?: 0.0
    val config =
        MatchConfig
            .load(options["--config"]?.let(::File) ?: File(System.getProperty("user.dir"), MatchConfig.DEFAULT_FILENAME))
            .let { it.copy(ai = it.ai.copy(speed = aiSpeed)) }
    val cardRepo = resolveCardRepository()
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
        WebServices(
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
                    rateLimitConfig = AuthRateLimitConfig.fromEnv(),
                    fixedLoginCode = resolveFixedLoginCode(System.getenv()),
                ),
            sealedSets = {
                SealedPoolGenerator.supportedSets().map {
                    LimitedSetView(code = it.code, name = it.name, type = it.type, cardCount = it.cardCount)
                }
            },
        )

    embeddedServer(Netty, host = host, port = port) { installWeb(services) }.start(wait = true)
}

private class WebRuntimeMatchLauncher(
    private val config: MatchConfig,
    private val coordinator: AppMatchCoordinator,
    private val cardRepo: CardRepository,
    private val runtimeMatches: RuntimeMatchConfigRegistry,
    private val relay: InProcessWebGreRelay,
) : WebMatchLauncher {
    override fun launchGreMatch(
        playerId: PlayerId?,
        request: GreStartRequest,
    ): DraftPlayResponse {
        val matchId = request.matchId?.takeIf { it.isNotBlank() } ?: "web-${UUID.randomUUID()}"
        // Watch requests may arrive without decklists; an AI-vs-AI match still
        // needs two decks, so blank spectator seats fall back to defaults.
        val spectator = request.spectatorMode == true
        runtimeMatches.configure(
            RuntimeMatchConfig(
                matchId = matchId,
                seat1Deck = request.seat1Deck?.takeIf { it.isNotBlank() } ?: "60 Plains".takeIf { spectator },
                seat2Deck = request.seat2Deck?.takeIf { it.isNotBlank() } ?: "60 Mountain".takeIf { spectator },
                gameVariant = request.gameVariant,
                puzzle = request.puzzle,
                spectatorMode = request.spectatorMode,
            ),
        )
        return register(matchId, playerId, publicAccess = playerId == null && request.spectatorMode == true)
    }

    override fun launchCourseMatch(
        playerId: PlayerId,
        eventName: String,
    ): DraftPlayResponse {
        val matchId = "web-${UUID.randomUUID()}"
        val (seat1, seat2) = coordinator.configureCourseMatch(matchId, playerId, eventName)
        runtimeMatches.configure(RuntimeMatchConfig(matchId = matchId, seat1Deck = seat1, seat2Deck = seat2))
        return register(matchId, playerId)
    }

    private fun register(
        matchId: String,
        playerId: PlayerId?,
        publicAccess: Boolean = false,
    ): DraftPlayResponse {
        relay.register(
            matchId,
            ownerPlayerId = playerId,
            publicAccess = publicAccess,
            onClose = { runtimeMatches.remove(matchId) },
        ) { onFrame, onClosed -> DirectWebGreEngineSession(config, coordinator, cardRepo, runtimeMatches, onFrame, onClosed) }
        return DraftPlayResponse(matchId, matchId)
    }
}

private fun resolveCardDb(): File {
    val path = System.getenv("LEYLINE_CARD_DB")?.takeIf { it.isNotBlank() } ?: detectArenaCardDb()
    requireNotNull(path) { "Card database not found. Set LEYLINE_CARD_DB." }
    return File(path).also { validateCardDbFile(it) }
}

private fun resolveCardRepository(): CardRepository =
    when (System.getenv("LEYLINE_CARD_MODE")?.lowercase()) {
        "auto" -> AutoMappingCardRepository(useFixtures = true)
        null,
        "sqlite",
        -> ExposedCardRepository(Database.connect("jdbc:sqlite:${resolveCardDb().absolutePath}", "org.sqlite.JDBC"))

        else -> error("LEYLINE_CARD_MODE must be 'sqlite' or 'auto'")
    }

private fun resolveWebAuthSecret(): String {
    val secret = System.getenv("LEYLINE_WEB_AUTH_SECRET")?.takeIf { it.isNotBlank() }
    require(secret != null) { "LEYLINE_WEB_AUTH_SECRET is required for the web profile" }
    require(secret != DEV_WEB_AUTH_SECRET) { "LEYLINE_WEB_AUTH_SECRET must not use the dev default" }
    require(secret.length >= 32) { "LEYLINE_WEB_AUTH_SECRET must be at least 32 characters" }
    return secret
}

internal fun resolveFixedLoginCode(env: Map<String, String>): String? {
    val code = env["LEYLINE_WEB_LOGIN_CODE"]?.takeIf { it.isNotBlank() } ?: return null
    require(env["LEYLINE_ALLOW_FIXED_LOGIN_CODE"] == "true") {
        "LEYLINE_WEB_LOGIN_CODE requires LEYLINE_ALLOW_FIXED_LOGIN_CODE=true"
    }
    require(Regex("^[0-9]{6}$").matches(code)) { "LEYLINE_WEB_LOGIN_CODE must be a six-digit code" }
    return code
}
