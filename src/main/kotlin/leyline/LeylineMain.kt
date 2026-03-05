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

    // Load config (TOML) — skip in proxy mode
    val config = if (isProxy) {
        MatchConfig()
    } else {
        val configFile = a["--config"]?.let { File(it) }
            ?: File(System.getProperty("user.dir"), MatchConfig.DEFAULT_FILENAME)
        MatchConfig.load(configFile)
    }
    val sc = config.server

    // TLS cert/key for FD+MD (UnityTls validates these; self-signed needs mitmproxy CA)
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val certFile = a["--cert"]?.let { File(it) } ?: envCert
    val keyFile = a["--key"]?.let { File(it) } ?: envKey

    // Card database: always required (collection, debug panel, recordings)
    val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        ?: detectArenaCardDb()
    requireNotNull(cardDbPath) {
        "Card database not found. Set LEYLINE_CARD_DB or install Arena client.\n" +
            "  Expected: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga"
    }
    require(File(cardDbPath).exists()) { "Card database not found at: $cardDbPath" }
    val cardRepo = ExposedCardRepository(
        org.jetbrains.exposed.v1.jdbc.Database.connect(
            "jdbc:sqlite:${File(cardDbPath).absolutePath}",
            "org.sqlite.JDBC",
        ),
    )

    // Puzzle mode: --puzzle <file> overrides normal constructed flow
    val puzzleFile = a["--puzzle"]?.let { File(it) }
    if (puzzleFile != null) {
        require(puzzleFile.exists()) { "Puzzle file not found: ${puzzleFile.absolutePath}" }
    }

    // FD golden file for replay stub (captured fd-frames.jsonl)
    val fdGoldenFile = a["--fd-golden"]?.let { File(it) }
    if (fdGoldenFile != null) {
        require(fdGoldenFile.exists()) { "FD golden file not found: ${fdGoldenFile.absolutePath}" }
    }

    // Ports: CLI args override config
    val fdPort = a["--fd-port"]?.toIntOrNull() ?: sc.fdPort
    val mdPort = a["--md-port"]?.toIntOrNull() ?: sc.mdPort

    // FD host for doorbell + MatchCreated: CLI arg > env var > config-derived default
    val fdHost = a["--fd-host"]
        ?: System.getenv("LEYLINE_FD_HOST")
        ?: "localhost:$fdPort"
    val externalHost = fdHost.substringBefore(":")

    // Player DB path: env var > config
    val playerDbPath = System.getenv("LEYLINE_PLAYER_DB") ?: sc.playerDb

    val server = LeylineServer(
        frontDoorPort = fdPort,
        matchDoorPort = mdPort,
        certFile = certFile,
        keyFile = keyFile,
        upstreamFrontDoor = a["--proxy-fd"],
        upstreamMatchDoor = a["--proxy-md"],
        replayDir = a["--replay"]?.let { File(it) },
        fdGoldenFile = fdGoldenFile,
        matchConfig = config,
        puzzleFile = puzzleFile,
        externalHost = externalHost,
        cardRepo = cardRepo,
        playerDbPath = playerDbPath,
    )

    val logWatcher = PlayerLogWatcher(eventBus = server.eventBus)
    val debugPort = a["--debug-port"]?.toIntOrNull() ?: sc.debugPort
    val debugServer = DebugServer(
        port = debugPort,
        debugCollector = server.debugCollector,
        gameStateCollector = server.gameStateCollector,
        fdCollector = server.fdCollector,
        eventBus = server.eventBus,
        recordingInspector = server.recordingInspector,
    )

    // Management server — always starts, owns /health
    val mgmtPort = sc.managementPort
    val mgmtServer = ManagementServer(
        port = mgmtPort,
        healthCheck = { server.isHealthy() },
    )

    // WAS — mock in stub mode, reverse proxy in proxy mode
    val wasPort = a["--was-port"]?.toIntOrNull() ?: sc.wasPort
    val wasCert = a["--was-cert"]?.let { File(it) } ?: envCert
    val wasKey = a["--was-key"]?.let { File(it) } ?: envKey
    val wasServer = if (isProxy) {
        MockWasServer(
            port = wasPort,
            certFile = wasCert,
            keyFile = wasKey,
            fdHost = fdHost,
            upstreamWas = a["--proxy-was"] ?: MockWasServer.DEFAULT_UPSTREAM_WAS,
            upstreamDoorbell = a["--proxy-doorbell"] ?: MockWasServer.DEFAULT_UPSTREAM_DOORBELL,
        )
    } else {
        val debugRoles = System.getenv("LEYLINE_DEBUG").let { it == "true" || it == "1" }
        MockWasServer(
            port = wasPort,
            roles = if (debugRoles) MockWasServer.DEBUG_ROLES else MockWasServer.DEFAULT_ROLES,
            certFile = wasCert,
            keyFile = wasKey,
            fdHost = fdHost,
        )
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logWatcher.stop()
            wasServer.stop()
            debugServer.stop()
            mgmtServer.stop()
            server.stop()
        },
    )

    val mode = when {
        server.isReplay -> "replay"
        server.isProxy -> "proxy"
        else -> "stub"
    }
    val puzzleSuffix = if (puzzleFile != null) " + puzzle" else ""

    println("Starting Leyline server ($mode$puzzleSuffix mode)...")
    server.start()
    mgmtServer.start()
    debugServer.start()
    wasServer.start()
    logWatcher.start()
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

    Thread.currentThread().join()
}

/** Auto-detect Arena card DB on macOS. Returns path or null. */
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
