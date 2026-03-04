package leyline

import leyline.debug.DebugServer
import leyline.debug.PlayerLogWatcher
import leyline.frontdoor.DeckValidator
import leyline.game.CardDb
import leyline.infra.LeylineServer
import leyline.infra.MockWasServer
import leyline.infra.PlaytestConfig
import java.io.File

/**
 * Standalone entry point for the Leyline server (client compat layer).
 *
 * Run via justfile targets: `just serve`, `just serve-stub`, etc.
 * See CLAUDE.md for mode descriptions.
 *
 * Environment variable fallbacks (CLI args take precedence):
 *   LEYLINE_CERT_PATH  — TLS cert file (used for FD, MD, WAS when specific args missing)
 *   LEYLINE_KEY_PATH   — TLS key file (used for FD, MD, WAS when specific args missing)
 *   LEYLINE_CARD_DB    — path to Arena card database SQLite file
 *   LEYLINE_FD_HOST    — FrontDoor host:port for doorbell response (default: localhost:30010)
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)

    // Env var fallbacks for TLS cert/key (single cert for all servers)
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }

    fun certFile(arg: String) = a[arg]?.let { File(it) } ?: envCert
    fun keyFile(arg: String) = a[arg]?.let { File(it) } ?: envKey

    // Card database: explicit path (LEYLINE_CARD_DB) or auto-detect from macOS Arena install
    val cardDbPath = System.getenv("LEYLINE_CARD_DB")
    if (cardDbPath != null) {
        CardDb.init(File(cardDbPath))
    }

    // Load playtest config (TOML)
    val projectDir = findProjectDir()
    val configFile = a["--config"]?.let { File(it) }
        ?: File(projectDir, PlaytestConfig.DEFAULT_FILENAME)
    val config = PlaytestConfig.load(configFile)

    // Puzzle mode: --puzzle <file> overrides normal constructed flow
    val puzzleFile = a["--puzzle"]?.let { File(it) }
    if (puzzleFile != null) {
        require(puzzleFile.exists()) { "Puzzle file not found: ${puzzleFile.absolutePath}" }
    }

    // Validate deck files at startup (skip in puzzle mode — no decks needed)
    if (puzzleFile == null) {
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
        frontDoorCert = certFile("--fd-cert"),
        frontDoorKey = keyFile("--fd-key"),
        matchDoorCert = certFile("--md-cert"),
        matchDoorKey = keyFile("--md-key"),
        upstreamFrontDoor = a["--proxy-fd"],
        upstreamMatchDoor = a["--proxy-md"],
        replayDir = a["--replay"]?.let { File(it) },
        fdGoldenFile = fdGoldenFile,
        playtestConfig = config,
        puzzleFile = puzzleFile,
        externalHost = externalHost,
    )

    val logWatcher = PlayerLogWatcher()

    // Mock WAS — serves crafted JWTs
    val wasPort = a["--was-port"]?.toIntOrNull() ?: 9443
    val debugRoles = System.getenv("LEYLINE_DEBUG").let { it == "true" || it == "1" }
    val wasServer = MockWasServer(
        port = wasPort,
        roles = if (debugRoles) MockWasServer.DEBUG_ROLES else MockWasServer.DEFAULT_ROLES,
        certFile = certFile("--was-cert"),
        keyFile = keyFile("--was-key"),
        fdHost = fdHost,
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logWatcher.stop()
            wasServer.stop()
            server.stop()
        },
    )

    val mode = when {
        server.isReplay -> "replay (proxy FD, replay MD)"
        server.isHybrid -> "hybrid (proxy FD, stub MD)"
        server.isProxy -> "proxy"
        else -> "stub"
    }
    val puzzleSuffix = if (puzzleFile != null) " + puzzle" else ""
    val debugPort = a["--debug-port"]?.toIntOrNull() ?: 8090
    val debugServer = DebugServer(debugPort)

    println("Starting Leyline server ($mode$puzzleSuffix mode)...")
    server.start()
    debugServer.start()
    wasServer.start()
    logWatcher.start()
    println("Leyline server running. Press Ctrl+C to stop.")
    println("Debug panel: http://localhost:$debugPort")
    println("Mock WAS:    https://localhost:$wasPort")
    println("Doorbell:    FdURI=$fdHost")
    if (puzzleFile != null) {
        println("Puzzle: ${puzzleFile.name}")
    } else {
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
private fun validateDecks(config: PlaytestConfig, projectDir: File) {
    val seat1File = PlaytestConfig.resolveDeckFile(config.decks.seat1, projectDir)
    val seat2File = PlaytestConfig.resolveDeckFile(config.decks.seat2, projectDir)
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
