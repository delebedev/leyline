package forge.nexus

import forge.nexus.config.DeckValidator
import forge.nexus.config.PlaytestConfig
import forge.nexus.debug.DebugServer
import forge.nexus.debug.PlayerLogWatcher
import forge.nexus.server.MockWasServer
import forge.nexus.server.NexusServer
import java.io.File

/**
 * Standalone entry point for the Nexus server (client compat layer).
 *
 * Run via justfile targets: `just serve`, `just serve-stub`, etc.
 * See forge-nexus/CLAUDE.md for mode descriptions.
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)

    // Load playtest config (TOML)
    val nexusDir = findNexusDir()
    val configFile = a["--config"]?.let { File(it) }
        ?: File(nexusDir, PlaytestConfig.DEFAULT_FILENAME)
    val config = PlaytestConfig.load(configFile)

    // Puzzle mode: --puzzle <file> overrides normal constructed flow
    val puzzleFile = a["--puzzle"]?.let { File(it) }
    if (puzzleFile != null) {
        require(puzzleFile.exists()) { "Puzzle file not found: ${puzzleFile.absolutePath}" }
    }

    // Validate deck files at startup (skip in puzzle mode — no decks needed)
    if (puzzleFile == null) {
        validateDecks(config, nexusDir)
    }

    // FD golden file for replay stub (captured fd-frames.jsonl)
    val fdGoldenFile = a["--fd-golden"]?.let { File(it) }
    if (fdGoldenFile != null) {
        require(fdGoldenFile.exists()) { "FD golden file not found: ${fdGoldenFile.absolutePath}" }
    }

    val server = NexusServer(
        frontDoorPort = a["--fd-port"]?.toIntOrNull() ?: 30010,
        matchDoorPort = a["--md-port"]?.toIntOrNull() ?: 30003,
        frontDoorCert = a["--fd-cert"]?.let { File(it) },
        frontDoorKey = a["--fd-key"]?.let { File(it) },
        matchDoorCert = a["--md-cert"]?.let { File(it) },
        matchDoorKey = a["--md-key"]?.let { File(it) },
        upstreamFrontDoor = a["--proxy-fd"],
        upstreamMatchDoor = a["--proxy-md"],
        replayDir = a["--replay"]?.let { File(it) },
        fdGoldenFile = fdGoldenFile,
        playtestConfig = config,
        puzzleFile = puzzleFile,
    )

    val logWatcher = PlayerLogWatcher()

    // Mock WAS — serves crafted JWTs with debug roles
    val wasPort = a["--was-port"]?.toIntOrNull() ?: 9443
    val wasServer = MockWasServer(
        port = wasPort,
        certFile = a["--was-cert"]?.let { File(it) },
        keyFile = a["--was-key"]?.let { File(it) },
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

    println("Starting Nexus server ($mode$puzzleSuffix mode)...")
    server.start()
    debugServer.start()
    wasServer.start()
    logWatcher.start()
    println("Nexus server running. Press Ctrl+C to stop.")
    println("Debug panel: http://localhost:$debugPort")
    println("Mock WAS:    https://localhost:$wasPort")
    if (puzzleFile != null) {
        println("Puzzle: ${puzzleFile.name}")
    } else {
        println("Config: ${config.summary()}")
    }

    Thread.currentThread().join()
}

/** Locate forge-nexus root (for resolving deck paths). */
private fun findNexusDir(): File {
    // Try CWD first, then look for forge-nexus subdir
    val cwd = File(System.getProperty("user.dir"))
    if (File(cwd, "decks").isDirectory) return cwd
    val sub = File(cwd, "forge-nexus")
    if (sub.isDirectory) return sub
    return cwd
}

/** Validate both deck files from config. Throws on invalid. */
private fun validateDecks(config: PlaytestConfig, nexusDir: File) {
    val seat1File = PlaytestConfig.resolveDeckFile(config.decks.seat1, nexusDir)
    val seat2File = PlaytestConfig.resolveDeckFile(config.decks.seat2, nexusDir)
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
