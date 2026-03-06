package leyline.debug

/**
 * Standalone CLI: tail Player.log and print client errors to stdout.
 *
 * Usage: `just watch-client`
 *
 * Runs until Ctrl-C. Errors are also written to
 * `recordings/<session>/client-errors.jsonl`.
 */
fun main() {
    val logFile = PlayerLogWatcher.PLAYER_LOG
    if (!logFile.exists()) {
        System.err.println("Player.log not found: $logFile")
        System.err.println("Launch MTGA at least once to create it.")
        System.exit(1)
    }

    println("Tailing ${logFile.absolutePath}")
    println("Watching for exceptions... (Ctrl-C to stop)")
    println()

    val watcher = PlayerLogWatcher()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            watcher.stop()
        },
    )
    watcher.start()

    // Block until interrupted
    Thread.currentThread().join()
}
