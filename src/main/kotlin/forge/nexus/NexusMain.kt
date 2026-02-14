package forge.nexus

import forge.nexus.debug.DebugServer
import forge.nexus.server.NexusServer
import java.io.File

/**
 * Standalone entry point for the Nexus server (Arena compat layer).
 *
 * Run via justfile targets: `just serve`, `just serve-stub`, etc.
 * See forge-nexus/CLAUDE.md for mode descriptions.
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)

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
    )

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    val mode = when {
        server.isReplay -> "replay (proxy FD, replay MD)"
        server.isHybrid -> "hybrid (proxy FD, stub MD)"
        server.isProxy -> "proxy"
        else -> "stub"
    }
    val debugPort = a["--debug-port"]?.toIntOrNull() ?: 8090
    val debugServer = DebugServer(debugPort)

    println("Starting Arena server ($mode mode)...")
    server.start()
    debugServer.start()
    println("Arena server running. Press Ctrl+C to stop.")
    println("Debug panel: http://localhost:$debugPort")

    Thread.currentThread().join()
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
