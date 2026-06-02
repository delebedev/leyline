package leyline.simclient.tool

import leyline.game.bundle.InvariantSelection
import leyline.game.data.CardRepository
import leyline.game.data.ExposedCardRepository
import leyline.simclient.ForgeAiPolicy
import leyline.simclient.PlayerLogWriter
import leyline.simclient.SimClientDriver
import leyline.simclient.isSimPrompt
import leyline.simclient.writeSimClientSidecar
import leyline.testkit.MatchFlowHarness
import leyline.tooling.simclient.DeckSimClientRow
import leyline.tooling.simclient.GameStats
import leyline.tooling.simclient.PuzzleSimClientRow
import leyline.tooling.simclient.STATS_SCHEMA_VERSION
import leyline.tooling.simclient.SimClientConfig
import leyline.tooling.simclient.SimClientPolicyMode
import leyline.tooling.simclient.SimClientRow
import leyline.tooling.simclient.SimClientRowResult
import leyline.tooling.simclient.SimClientRunResult
import leyline.tooling.simclient.expandSimClientRows
import leyline.tooling.simclient.failureClass
import leyline.tooling.simclient.isStrictFailure
import leyline.tooling.simclient.mapToJson
import leyline.tooling.simclient.simJsonString
import leyline.tooling.simclient.statsToJson
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = SimClientMain.run(args)
    if (exitCode != 0) exitProcess(exitCode)
}

object SimClientMain {
    fun run(args: Array<String>): Int {
        val config = SimClientConfig.parse(args.toList(), System.getenv()) ?: return 0
        val result = SimClientRunner(config).run()
        return if (config.strict && result.hasStrictFailures) 1 else 0
    }
}

class SimClientRunner(
    private val config: SimClientConfig,
) {
    private val cardRepo: CardRepository by lazy {
        val path = requireNotNull(config.cardDbPath) { "LEYLINE_CARD_DB or --card-db is required" }
        require(File(path).exists()) { "Card database not found at: $path" }
        ExposedCardRepository(Database.connect("jdbc:sqlite:${File(path).absolutePath}", "org.sqlite.JDBC"))
    }

    fun run(): SimClientRunResult {
        config.outDir.mkdirs()
        val rows = expandSimClientRows(config)
        if (rows.any { it.useCardDb }) {
            require(config.cardDbPath != null) { "LEYLINE_CARD_DB or --card-db is required for deck-file or card-db-backed puzzle rows" }
        }
        val runLine =
            "=== simclient: ${rows.size} row(s) policy=${config.policy.name} " +
                "out=${config.outDir} strict=${config.strict} resume=${config.resume} ==="
        println(runLine)
        val results = mutableListOf<SimClientRowResult>()
        var skipped = 0
        for (row in rows) {
            val statsFile = File(config.outDir, "${row.tag}.stats.json")
            if (config.resume && statsFile.exists()) {
                skipped += 1
                println("[${row.runLabel} s=${row.seed}] skipped existing ${statsFile.name}")
                continue
            }
            val stats = runRow(row)
            results += SimClientRowResult(row, stats)
            printRowSummary(row, stats)
            if (!config.continueOnException && (stats.completionReason == "wall-timeout" || stats.completionReason == "exception")) {
                error("simclient row failed: ${row.tag} reason=${stats.completionReason}")
            }
        }
        val result = SimClientRunResult(results, skipped)
        printAggregate(result)
        writeSummary(result)
        if (config.ingestScry) ingestScry()
        return result
    }

    private fun runRow(row: SimClientRow): GameStats {
        val logFile = File(config.outDir, "${row.tag}.log")
        val stats =
            runWithTimeout(
                tag = row.tag,
                logFile = logFile,
                runLabel = row.runLabel,
                opponentRunLabel = row.opponentRunLabel,
                seed = row.seed,
                runKind = row.runKind,
                createHarness = { createHarness(row) },
                runGame = { harness, playerLog -> createDriver(row, harness, playerLog).runOneGame() },
            )
        File(config.outDir, "${row.tag}.stats.json").writeText(statsToJson(row, stats, config.policy))
        return stats
    }

    private fun createHarness(row: SimClientRow): MatchFlowHarness =
        when (row) {
            is DeckSimClientRow ->
                MatchFlowHarness(
                    seed = row.seed,
                    deckList = row.deckList,
                    opponentDeckList = row.opponentDeckList,
                    validation = InvariantSelection.protocolFacts(),
                    validationStrict = false,
                    cardRepositoryOverride = if (row.useCardDb) cardRepo else null,
                )
            is PuzzleSimClientRow ->
                MatchFlowHarness(
                    seed = row.seed,
                    deckList = null,
                    validation = InvariantSelection.protocolFacts(),
                    validationStrict = false,
                    cardRepositoryOverride = if (row.useCardDb) cardRepo else null,
                )
        }

    private fun createDriver(
        row: SimClientRow,
        harness: MatchFlowHarness,
        playerLog: PlayerLogWriter,
    ): SimClientDriver {
        val forgeAi = if (config.policy == SimClientPolicyMode.ForgeAi) ForgeAiPolicy(harness, leyline.bridge.types.SeatId(1)) else null
        return when (row) {
            is DeckSimClientRow ->
                SimClientDriver(
                    harness,
                    playerLog,
                    maxTurns = config.maxTurns,
                    maxIterations = 3_000,
                    forgeAi = forgeAi,
                )
            is PuzzleSimClientRow ->
                SimClientDriver(
                    harness,
                    playerLog,
                    maxTurns = config.maxTurns,
                    maxIterations = 3_000,
                    connect = { harness.connectAndKeepPuzzleText(row.puzzleText) },
                )
        }
    }

    private fun runWithTimeout(
        tag: String,
        logFile: File,
        runLabel: String,
        opponentRunLabel: String?,
        seed: Long,
        runKind: String,
        createHarness: () -> MatchFlowHarness,
        runGame: (MatchFlowHarness, PlayerLogWriter) -> GameStats,
    ): GameStats {
        val matchId = "simclient-$tag"
        val harnessRef = AtomicReference<MatchFlowHarness?>()
        val timeoutMs = config.gameTimeoutSeconds * 1_000
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "simclient-$tag").apply { isDaemon = true } }
        val future =
            executor.submit<GameStats> {
                val harness = createHarness()
                harnessRef.set(harness)
                var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
                val writer = logFile.bufferedWriter()
                try {
                    val playerLog =
                        PlayerLogWriter(
                            out = writer,
                            matchId = matchId,
                            clock = {
                                fakeNow = fakeNow.plusSeconds(1)
                                fakeNow
                            },
                        )
                    runGame(harness, playerLog)
                } finally {
                    runCatching { writer.close() }
                    runCatching { harness.shutdown() }
                }
            }
        val t0 = System.nanoTime()
        val stats =
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                val statsAtTimeout = timeoutStats(harnessRef.get(), timeoutMs)
                future.cancel(true)
                runCatching { harnessRef.get()?.shutdown() }
                statsAtTimeout
            } catch (e: ExecutionException) {
                if (!config.continueOnException) throw e
                val statsAtException = exceptionStats(harnessRef.get(), (System.nanoTime() - t0) / 1_000_000, e)
                runCatching { harnessRef.get()?.shutdown() }
                statsAtException
            } finally {
                executor.shutdownNow()
            }
        writeSimClientSidecar(logFile, matchId, runLabel, opponentRunLabel, seed, LocalDateTime.now(), runKind)
        return stats
    }

    private fun timeoutStats(
        harness: MatchFlowHarness?,
        timeoutMs: Long,
    ): GameStats {
        val messages = runCatching { harness?.allMessages?.toList().orEmpty() }.getOrDefault(emptyList())
        val histogram = messages.filter { isSimPrompt(it) }.groupingBy { it.type }.eachCount()
        return GameStats(
            turn = runCatching { harness?.turn() ?: 0 }.getOrDefault(0),
            gameOver = runCatching { harness?.isGameOver() ?: false }.getOrDefault(false),
            iterations = 0,
            totalMessages = messages.size,
            promptHistogram = histogram,
            hitIterCap = false,
            durationMs = timeoutMs,
            errorsByType = mapOf(TimeoutException::class.qualifiedName.orEmpty() to 1),
            completionReason = "wall-timeout",
        )
    }

    private fun exceptionStats(
        harness: MatchFlowHarness?,
        elapsedMs: Long,
        throwable: Throwable,
    ): GameStats {
        val cause = throwable.rootCause()
        val messages = runCatching { harness?.allMessages?.toList().orEmpty() }.getOrDefault(emptyList())
        val histogram = messages.filter { isSimPrompt(it) }.groupingBy { it.type }.eachCount()
        val stackTop = cause.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
        return GameStats(
            turn = runCatching { harness?.turn() ?: 0 }.getOrDefault(0),
            gameOver = runCatching { harness?.isGameOver() ?: false }.getOrDefault(false),
            iterations = 0,
            totalMessages = messages.size,
            promptHistogram = histogram,
            hitIterCap = false,
            durationMs = elapsedMs,
            errorsByType = mapOf(cause::class.qualifiedName.orEmpty() to 1),
            exceptionMessage = "${cause::class.qualifiedName}: ${cause.message}",
            exceptionStackTop = stackTop,
            completionReason = "exception",
        )
    }

    private fun printRowSummary(
        row: SimClientRow,
        stats: GameStats,
    ) {
        val warnTotal = stats.warnsByLogger.values.sum()
        val errTotal = stats.errorsByType.values.sum()
        val validationTotal = stats.validationViolationsByCheck.values.sum()
        val aiSummary = if (stats.aiConsulted > 0) "ai=${stats.aiChose}/${stats.aiConsulted} aiMs=${stats.aiTotalMs} " else ""
        println(
            "[${row.runLabel} s=${row.seed}] ${stats.durationMs}ms turn=${stats.turn} " +
                "gameOver=${stats.gameOver} reason=${stats.completionReason} failure=${failureClass(stats)} " +
                "iter=${stats.iterations} msgs=${stats.totalMessages} hitCap=${stats.hitIterCap} " +
                "prompts=${stats.promptHistogram.size} ${aiSummary}warns=$warnTotal errs=$errTotal validation=$validationTotal",
        )
    }

    private fun printAggregate(result: SimClientRunResult) {
        val rows = result.rows
        println("\n=== simclient summary ===")
        println("rows run: ${rows.size}")
        println("rows skipped: ${result.skipped}")
        println("gameOver: ${rows.count { it.stats.gameOver }}")
        println("strictFailures: ${rows.count { isStrictFailure(it.stats) }}")
        rows
            .groupingBy { failureClass(it.stats) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .forEach { (klass, count) ->
                println("  $klass = $count")
            }
    }

    private fun writeSummary(result: SimClientRunResult) {
        val path = config.summaryJson ?: File(config.outDir, "summary.json")
        path.parentFile?.mkdirs()
        val failures = result.rows.groupingBy { failureClass(it.stats) }.eachCount()
        val json =
            buildString {
                append('{')
                append("\"schemaVersion\":$STATS_SCHEMA_VERSION,")
                append("\"rowsRun\":${result.rows.size},")
                append("\"rowsSkipped\":${result.skipped},")
                append("\"strictFailures\":${result.rows.count { isStrictFailure(it.stats) }},")
                append("\"failureClasses\":${mapToJson(failures)},")
                append("\"outDir\":${simJsonString(config.outDir.path)}")
                append('}')
            }
        path.writeText(json)
    }

    private fun ingestScry() {
        val out = Path.of(System.getProperty("user.home"), ".scry", "games")
        Files.createDirectories(out)
        var count = 0
        config.outDir.listFiles { file -> file.extension == "log" }.orEmpty().forEach { log ->
            val base = log.nameWithoutExtension
            Files.copy(log.toPath(), out.resolve("$base.log"), StandardCopyOption.REPLACE_EXISTING)
            val sidecar = File(config.outDir, "$base.meta.json")
            if (sidecar.exists()) Files.copy(sidecar.toPath(), out.resolve("$base.meta.json"), StandardCopyOption.REPLACE_EXISTING)
            count += 1
        }
        println("Sim-client: $count game(s) ingested into $out")
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }
}
