package leyline

import leyline.config.MatchConfig
import leyline.debug.DebugServer
import leyline.debug.PlayerLogWatcher
import leyline.frontdoor.DeckValidator
import leyline.game.ExposedCardRepository
import leyline.infra.LeylineServer
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
 * Environment variable fallbacks (CLI args take precedence):
 *   LEYLINE_CERT_PATH  — TLS cert file for FD/MD (self-signed if missing)
 *   LEYLINE_KEY_PATH   — TLS key file for FD/MD (self-signed if missing)
 *   LEYLINE_CARD_DB    — path to Arena card database SQLite file
 *   LEYLINE_FD_HOST    — FrontDoor host:port for doorbell response (default: localhost:30010)
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)
    val isProxy = a["--proxy-fd"] != null && a["--proxy-md"] != null

    // TLS cert/key for FD+MD (UnityTls validates these; self-signed needs mitmproxy CA)
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val certFile = a["--cert"]?.let { File(it) } ?: envCert
    val keyFile = a["--key"]?.let { File(it) } ?: envKey

    // Card database: skip in proxy mode (pure passthrough, no card lookups)
    val cardRepo = if (!isProxy) {
        val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        if (cardDbPath != null && File(cardDbPath).exists()) {
            val cardDb = org.jetbrains.exposed.v1.jdbc.Database.connect(
                "jdbc:sqlite:${File(cardDbPath).absolutePath}",
                "org.sqlite.JDBC",
            )
            ExposedCardRepository(cardDb)
        } else {
            null
        }
    } else {
        null
    }

    // Load playtest config (TOML) — skip in proxy mode
    val projectDir = findProjectDir()
    val config = if (isProxy) {
        MatchConfig()
    } else {
        val configFile = a["--config"]?.let { File(it) }
            ?: File(projectDir, MatchConfig.DEFAULT_FILENAME)
        MatchConfig.load(configFile)
    }

    // Puzzle mode: --puzzle <file> overrides normal constructed flow
    val puzzleFile = a["--puzzle"]?.let { File(it) }
    if (puzzleFile != null) {
        require(puzzleFile.exists()) { "Puzzle file not found: ${puzzleFile.absolutePath}" }
    }

    // Validate deck files at startup (skip in puzzle and proxy modes)
    if (puzzleFile == null && !isProxy) {
        validateDecks(config, projectDir)
    }

    // FD golden file for replay stub (captured fd-frames.jsonl)
    val fdGoldenFile = a["--fd-golden"]?.let { File(it) }
    if (fdGoldenFile != null) {
        require(fdGoldenFile.exists()) { "FD golden file not found: ${fdGoldenFile.absolutePath}" }
    }

    val fdPort = a["--fd-port"]?.toIntOrNull() ?: 30010
    val mdPort = a["--md-port"]?.toIntOrNull() ?: 30003

    // FD host for doorbell + MatchCreated: CLI arg > env var > default
    val fdHost = a["--fd-host"]
        ?: System.getenv("LEYLINE_FD_HOST")
        ?: "localhost:$fdPort"
    // Extract hostname (without port) for MatchCreated push
    val externalHost = fdHost.substringBefore(":")

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
    )

    val logWatcher = PlayerLogWatcher(eventBus = server.eventBus)
    val debugPort = a["--debug-port"]?.toIntOrNull() ?: 8090
    val debugServer = DebugServer(
        port = debugPort,
        debugCollector = server.debugCollector,
        gameStateCollector = server.gameStateCollector,
        fdCollector = server.fdCollector,
        eventBus = server.eventBus,
    )

    // Mock WAS — skip in proxy mode (client uses real WAS for auth)
    val wasServer = if (!isProxy) {
        val wasPort = a["--was-port"]?.toIntOrNull() ?: 9443
        val debugRoles = System.getenv("LEYLINE_DEBUG").let { it == "true" || it == "1" }
        val wasCert = a["--was-cert"]?.let { File(it) } ?: envCert
        val wasKey = a["--was-key"]?.let { File(it) } ?: envKey
        MockWasServer(
            port = wasPort,
            roles = if (debugRoles) MockWasServer.DEBUG_ROLES else MockWasServer.DEFAULT_ROLES,
            certFile = wasCert,
            keyFile = wasKey,
            fdHost = fdHost,
        )
    } else {
        null
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logWatcher.stop()
            wasServer?.stop()
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
    debugServer.start()
    wasServer?.start()
    logWatcher.start()
    println("Leyline server running. Press Ctrl+C to stop.")
    println("Debug panel: http://localhost:$debugPort")
    if (wasServer != null) {
        val wasPort = a["--was-port"]?.toIntOrNull() ?: 9443
        println("Mock WAS:    https://localhost:$wasPort")
        println("Doorbell:    FdURI=$fdHost")
    }
    if (puzzleFile != null) {
        println("Puzzle: ${puzzleFile.name}")
    } else if (!isProxy) {
        println("Config: ${config.summary()}")
    }

    Thread.currentThread().join()
}

/** Locate project root (for resolving deck paths). */
private fun findProjectDir(): File {
    val cwd = File(System.getProperty("user.dir"))
    if (File(cwd, "decks").isDirectory) return cwd
    return cwd
}

/** Validate both deck files from config. Throws on invalid. */
private fun validateDecks(config: MatchConfig, projectDir: File) {
    val seat1File = MatchConfig.resolveDeckFile(config.decks.seat1, projectDir)
    val seat2File = MatchConfig.resolveDeckFile(config.decks.seat2, projectDir)
    DeckValidator.validateOrThrow(seat1File)
    if (seat1File.absolutePath != seat2File.absolutePath) {
        DeckValidator.validateOrThrow(seat2File)
    }
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
