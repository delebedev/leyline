package leyline

import leyline.config.MatchConfig
import leyline.debug.DebugServer
import leyline.debug.PlayerLogWatcher
import leyline.game.ExposedCardRepository
import leyline.infra.LeylineServer
import leyline.infra.ManagementServer
import leyline.infra.MockWasServer
import java.io.File

/**
 * Standalone entry point for the Leyline server (client compat layer).
 *
 * Run via justfile targets: `just serve`, `just serve-proxy`, etc.
 * See CLAUDE.md for mode descriptions.
 *
 * TLS: self-signed certs by default (needs mitmproxy CA certs for UnityTls validation).
 * MockWAS always self-signs (CheckSC=0 covers HTTP).
 *
 * Configuration layering (highest priority wins):
 *   CLI args > env vars > leyline.toml > code defaults
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)
    val isProxy = a["--proxy-fd"] != null && a["--proxy-md"] != null

    val config = loadConfig(a, isProxy)
    val sc = config.server
    val tls = resolveTls(a)
    val cardRepo = openCardRepo(a)
    val puzzleFile = resolveOptionalFile(a["--puzzle"], "Puzzle file")
    val fdGoldenFile = resolveOptionalFile(a["--fd-golden"], "FD golden file")

    val fdPort = a["--fd-port"]?.toIntOrNull() ?: sc.fdPort
    val mdPort = a["--md-port"]?.toIntOrNull() ?: sc.mdPort
    val fdHost = a["--fd-host"]
        ?: System.getenv("LEYLINE_FD_HOST")
        ?: "localhost:$fdPort"

    val server = LeylineServer(
        frontDoorPort = fdPort,
        matchDoorPort = mdPort,
        certFile = tls.first,
        keyFile = tls.second,
        upstreamFrontDoor = a["--proxy-fd"],
        upstreamMatchDoor = a["--proxy-md"],
        replayDir = a["--replay"]?.let { File(it) },
        fdGoldenFile = fdGoldenFile,
        matchConfig = config,
        puzzleFile = puzzleFile,
        externalHost = fdHost.substringBefore(":"),
        cardRepo = cardRepo,
        playerDbPath = System.getenv("LEYLINE_PLAYER_DB") ?: sc.playerDb,
    )

    val debugPort = a["--debug-port"]?.toIntOrNull() ?: sc.debugPort
    val mgmtPort = sc.managementPort
    val wasPort = a["--was-port"]?.toIntOrNull() ?: sc.wasPort

    val logWatcher = PlayerLogWatcher(eventBus = server.eventBus)
    val debugServer = buildDebugServer(debugPort, server)
    val mgmtServer = ManagementServer(port = mgmtPort, healthCheck = { server.isHealthy() })
    val wasServer = buildWasServer(a, isProxy, wasPort, tls, fdHost)

    installShutdownHook(logWatcher, wasServer, debugServer, mgmtServer, server)
    startAll(server, mgmtServer, debugServer, wasServer, logWatcher)
    printBanner(server, puzzleFile, isProxy, config, mgmtPort, debugPort, wasPort, wasServer, fdHost)

    Thread.currentThread().join()
}

// -- Config & resources -------------------------------------------------------

private fun loadConfig(a: Map<String, String>, isProxy: Boolean): MatchConfig {
    if (isProxy) return MatchConfig()
    val configFile = a["--config"]?.let { File(it) }
        ?: File(System.getProperty("user.dir"), MatchConfig.DEFAULT_FILENAME)
    return MatchConfig.load(configFile)
}

private fun resolveTls(a: Map<String, String>): Pair<File?, File?> {
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val cert = a["--cert"]?.let { File(it) } ?: envCert
    val key = a["--key"]?.let { File(it) } ?: envKey
    return cert to key
}

private fun openCardRepo(a: Map<String, String>): ExposedCardRepository {
    val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        ?: detectArenaCardDb()
    requireNotNull(cardDbPath) {
        "Card database not found. Set LEYLINE_CARD_DB or install Arena client.\n" +
            "  Expected: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga"
    }
    require(File(cardDbPath).exists()) { "Card database not found at: $cardDbPath" }
    return ExposedCardRepository(
        org.jetbrains.exposed.v1.jdbc.Database.connect(
            "jdbc:sqlite:${File(cardDbPath).absolutePath}",
            "org.sqlite.JDBC",
        ),
    )
}

private fun resolveOptionalFile(path: String?, label: String): File? {
    val file = path?.let { File(it) } ?: return null
    require(file.exists()) { "$label not found: ${file.absolutePath}" }
    return file
}

// -- Server builders ----------------------------------------------------------

private fun buildDebugServer(port: Int, server: LeylineServer) = DebugServer(
    port = port,
    debugCollector = server.debugCollector,
    gameStateCollector = server.gameStateCollector,
    fdCollector = server.fdCollector,
    eventBus = server.eventBus,
    recordingInspector = server.recordingInspector,
)

private fun buildWasServer(
    a: Map<String, String>,
    isProxy: Boolean,
    port: Int,
    tls: Pair<File?, File?>,
    fdHost: String,
): MockWasServer {
    if (isProxy) {
        return MockWasServer(
            port = port,
            certFile = a["--was-cert"]?.let { File(it) } ?: tls.first,
            keyFile = a["--was-key"]?.let { File(it) } ?: tls.second,
            fdHost = fdHost,
            upstreamWas = a["--proxy-was"] ?: MockWasServer.DEFAULT_UPSTREAM_WAS,
            upstreamDoorbell = a["--proxy-doorbell"] ?: MockWasServer.DEFAULT_UPSTREAM_DOORBELL,
        )
    }
    val debugRoles = System.getenv("LEYLINE_DEBUG").let { it == "true" || it == "1" }
    return MockWasServer(
        port = port,
        roles = if (debugRoles) MockWasServer.DEBUG_ROLES else MockWasServer.DEFAULT_ROLES,
        certFile = a["--was-cert"]?.let { File(it) } ?: tls.first,
        keyFile = a["--was-key"]?.let { File(it) } ?: tls.second,
        fdHost = fdHost,
    )
}

// -- Lifecycle ----------------------------------------------------------------

private fun installShutdownHook(
    logWatcher: PlayerLogWatcher,
    wasServer: MockWasServer,
    debugServer: DebugServer,
    mgmtServer: ManagementServer,
    server: LeylineServer,
) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logWatcher.stop()
            wasServer.stop()
            debugServer.stop()
            mgmtServer.stop()
            server.stop()
        },
    )
}

private fun startAll(
    server: LeylineServer,
    mgmtServer: ManagementServer,
    debugServer: DebugServer,
    wasServer: MockWasServer,
    logWatcher: PlayerLogWatcher,
) {
    server.start()
    mgmtServer.start()
    debugServer.start()
    wasServer.start()
    logWatcher.start()
}

private fun printBanner(
    server: LeylineServer,
    puzzleFile: File?,
    isProxy: Boolean,
    config: MatchConfig,
    mgmtPort: Int,
    debugPort: Int,
    wasPort: Int,
    wasServer: MockWasServer,
    fdHost: String,
) {
    val mode = when {
        server.isReplay -> "replay"
        server.isProxy -> "proxy"
        else -> "stub"
    }
    val puzzleSuffix = if (puzzleFile != null) " + puzzle" else ""

    println("Starting Leyline server ($mode$puzzleSuffix mode)...")
    println("Leyline server running. Press Ctrl+C to stop.")
    println("Management: http://localhost:$mgmtPort/health")
    println("Debug panel: http://localhost:$debugPort")
    if (wasServer.isProxy) {
        println("WAS proxy:   https://localhost:$wasPort -> ${MockWasServer.DEFAULT_UPSTREAM_WAS}")
    } else {
        println("Mock WAS:    https://localhost:$wasPort")
    }
    println("Doorbell:    FdURI=$fdHost")
    if (puzzleFile != null) {
        println("Puzzle: ${puzzleFile.name}")
    } else if (!isProxy) {
        println("Config: ${config.summary()}")
    }
}

// -- Utilities ----------------------------------------------------------------

private fun detectArenaCardDb(): String? {
    val rawDir = File(System.getProperty("user.home"), "Library/Application Support/com.wizards.mtga/Downloads/Raw")
    if (!rawDir.isDirectory) return null
    return rawDir.listFiles()
        ?.filter { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
        ?.maxByOrNull { it.lastModified() }
        ?.absolutePath
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            map[args[i]] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return map
}
