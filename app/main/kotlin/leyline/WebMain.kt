package leyline

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import leyline.config.ConfigException
import leyline.config.EngineSettings
import leyline.config.LeylineConfigResolver
import leyline.config.ResolvedLeylineConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.config.WebSettings
import leyline.domain.CollationPool
import leyline.domain.PlayerId
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DraftService
import leyline.domain.service.GeneratedPool
import leyline.game.data.CardRepository
import leyline.game.data.ClientCardDatabase
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
    val resolved = resolveWebConfig()
    val web = resolved.config.web
    validateWebHead(web)
    System.setProperty("LEYLINE_LOG_DIR", resolved.paths.artifactsRoot.absolutePath)
    val paths = resolved.paths.also { it.ensureDirectories() }
    val engineSettings = resolved.config.engine

    println(resolved.report(head = "web"))

    val cardRepo = resolveCardRepository()
    val playerDb = paths.playerDb
    val playerDatabase = Database.connect("jdbc:sqlite:${playerDb.absolutePath}", "org.sqlite.JDBC")
    val playerStore = SqlitePlayerStore(playerDatabase).also { it.createTables() }
    val authStore = SqliteWebAuthStore(playerDatabase).also { it.createTables() }
    val defaultPlayerId = PlayerId(web.playerId)
    playerStore.ensurePlayer(defaultPlayerId, "Web Player")

    val sealedPoolGenerator = SealedPoolGenerator(cardRepo::findGrpIdByName)
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
    val draftDriver =
        ForgeBoosterDraftDriver(
            cardRepo::findGrpIdByName,
            engineSettings.draft.copy(modelDir = paths.draftModelDir(engineSettings.draft.modelDir).absolutePath),
        )
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
            courseService,
        ).also { it.discardIncompleteSessions() }
    val coordinator = AppMatchCoordinator(defaultPlayerId, playerStore, courseService, draftRepo)
    val relay = InProcessWebGreRelay()
    val runtimeMatches = RuntimeMatchConfigRegistry()
    val launcher =
        WebRuntimeMatchLauncher(
            engineSettings = engineSettings,
            puzzlesDir = paths.puzzlesDir,
            coordinator = coordinator,
            cardRepo = cardRepo,
            runtimeMatches = runtimeMatches,
            relay = relay,
        )
    val emailSender =
        if (web.resendApiKey.isNotBlank()) {
            ResendEmailSender(web.resendApiKey, web.resendFrom)
        } else {
            DevEmailSender()
        }

    val services =
        WebServices(
            draftService = draftService,
            courseService = courseService,
            decks = playerStore,
            collectionService = CollectionService { cardRepo.findAllGrpIds() },
            cardRepository = cardRepo,
            matchLauncher = launcher,
            greRelay = relay,
            authService =
                WebAuthService(
                    authStore,
                    emailSender,
                    secret = web.authSecret,
                    rateLimitConfig =
                        AuthRateLimitConfig(
                            enabled = web.rateLimitEnabled,
                            loginLimit = web.rateLimit,
                            loginWindowMs = web.rateLimitWindowMs,
                        ),
                    fixedLoginCode = web.loginCode.takeIf { it.isNotBlank() },
                ),
            puzzleCatalogDir = paths.puzzlesDir,
            sealedSets = {
                SealedPoolGenerator.supportedSets().map {
                    LimitedSetView(code = it.code, name = it.name, type = it.type, cardCount = it.cardCount)
                }
            },
        )

    embeddedServer(Netty, host = web.host, port = web.port) { installWeb(services) }.start(wait = true)
}

private class WebRuntimeMatchLauncher(
    private val engineSettings: EngineSettings,
    private val puzzlesDir: File,
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
        runtimeMatches.configure(RuntimeMatchConfig(matchId = matchId, seat1Cards = seat1, seat2Cards = seat2))
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
        ) { onFrame, onClosed ->
            DirectWebGreEngineSession(
                engineSettings,
                coordinator,
                cardRepo,
                runtimeMatches,
                onFrame,
                onClosed,
                puzzlesDir,
            )
        }
        return DraftPlayResponse(matchId, matchId)
    }
}

private fun resolveWebConfig(): ResolvedLeylineConfig =
    try {
        LeylineConfigResolver(baseDir = File(System.getProperty("user.dir")), env = System.getenv()).resolve()
    } catch (e: ConfigException) {
        System.err.println("Configuration error: ${e.message}")
        kotlin.system.exitProcess(1)
    }

private fun resolveCardRepository(): CardRepository =
    ClientCardDatabase.open(overridePath = System.getenv("LEYLINE_CARD_DB")).cardRepository()

/**
 * Head-specific validation for the browser-facing web head. The web
 * authentication secret is required, must not reuse the dev default, and must
 * be strong enough to sign sessions; the optional fixed login code requires
 * explicit opt-in and exactly six digits.
 */
internal fun validateWebHead(web: WebSettings) {
    require(web.authSecret.isNotBlank()) { "web.auth_secret is required for the web head (set LEYLINE_WEB_AUTH_SECRET)" }
    require(web.authSecret != DEV_WEB_AUTH_SECRET) { "web.auth_secret must not use the dev default" }
    require(web.authSecret.length >= 32) { "web.auth_secret must be at least 32 characters" }
    if (web.loginCode.isNotBlank()) {
        require(web.allowFixedLoginCode) { "web.login_code requires web.allow_fixed_login_code=true" }
        require(Regex("^[0-9]{6}$").matches(web.loginCode)) { "web.login_code must be a six-digit code" }
    }
}
