package leyline.tooling.simclient

import leyline.copilot.ForgeAiPolicy
import leyline.game.bundle.InvariantSelection
import leyline.game.data.CardRepository
import leyline.game.data.ExposedCardRepository
import leyline.tooling.headless.HeadlessResponseMode
import leyline.tooling.headless.MatchFlowHarness
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.nio.file.Path
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
        SimClientLogging.configure(config.verbose)
        val result = SimClientRunner(config).run()
        return if (config.strict && result.hasStrictFailures) 1 else 0
    }
}

class SimClientRunner(
    private val config: SimClientConfig,
) {
    @Suppress("CanBeNonNullable")
    private val resolvedCardDbPath: String? by lazy { resolveSimClientCardDbPath(config) }

    private val cardRepo: CardRepository by lazy {
        val path = requireNotNull(resolvedCardDbPath) { "Card database not found; set LEYLINE_CARD_DB or --card-db" }
        val file = validateSimClientCardDbFile(path)
        ExposedCardRepository(Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC"))
    }

    fun run(): SimClientRunResult {
        config.outDir.mkdirs()
        val rows = expandSimClientRows(config)
        if (rows.any { it.useCardDb } || resolvedCardDbPath != null) {
            require(resolvedCardDbPath != null) { "Card database not found; set LEYLINE_CARD_DB or --card-db for deck-file rows" }
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
            val rowWithResolvedOverlay = resolveRowOverlay(row)
            if (rowWithResolvedOverlay == null) {
                skipped += 1
                println("[${row.runLabel} s=${row.seed}] skipped by quarantine")
                continue
            }
            val stats = runRow(rowWithResolvedOverlay)
            results += SimClientRowResult(rowWithResolvedOverlay, stats)
            printRowSummary(rowWithResolvedOverlay, stats)
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
                run =
                    TimedRunContext(
                        tag = row.tag,
                        logFile = logFile,
                        consoleLogFile = File(config.outDir, "${row.tag}.console.log"),
                        runLabel = row.runLabel,
                        opponentRunLabel = row.opponentRunLabel,
                        seed = row.seed,
                        runKind = row.runKind,
                        deckOverlay = (row as? DeckSimClientRow)?.overlay,
                        opponentDeckOverlay = (row as? DeckSimClientRow)?.opponentOverlay,
                    ),
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
                    cardRepositoryOverride = if (row.useCardDb || resolvedCardDbPath != null) cardRepo else null,
                    responseMode = HeadlessResponseMode.PolicyVisible,
                )
            is PuzzleSimClientRow ->
                MatchFlowHarness(
                    seed = row.seed,
                    deckList = null,
                    validation = InvariantSelection.protocolFacts(),
                    validationStrict = false,
                    cardRepositoryOverride = if (row.useCardDb || resolvedCardDbPath != null) cardRepo else null,
                    responseMode = HeadlessResponseMode.PolicyVisible,
                )
        }

    private fun createDriver(
        row: SimClientRow,
        harness: MatchFlowHarness,
        playerLog: PlayerLogWriter,
    ): SimClientDriver {
        val forgeAi =
            if (config.policy == SimClientPolicyMode.ForgeAi || config.policy == SimClientPolicyMode.ShadowAi) {
                ForgeAiPolicy({ harness.bridge }, leyline.bridge.types.SeatId(1))
            } else {
                null
            }
        return when (row) {
            is DeckSimClientRow ->
                SimClientDriver(
                    harness,
                    playerLog,
                    maxTurns = config.maxTurns,
                    maxIterations = 3_000,
                    forgeAi = forgeAi,
                    shadowAdvisor = config.policy == SimClientPolicyMode.ShadowAi,
                    snapshotShadow = config.policy == SimClientPolicyMode.SnapshotShadow,
                    snapshotConsult = config.policy == SimClientPolicyMode.Snapshot,
                )
            is PuzzleSimClientRow ->
                SimClientDriver(
                    harness,
                    playerLog,
                    maxTurns = config.maxTurns,
                    maxIterations = 3_000,
                    connect = { harness.connectAndKeepPuzzleText(row.puzzleText) },
                    forgeAi = forgeAi,
                    shadowAdvisor = config.policy == SimClientPolicyMode.ShadowAi,
                    snapshotShadow = config.policy == SimClientPolicyMode.SnapshotShadow,
                    snapshotConsult = config.policy == SimClientPolicyMode.Snapshot,
                )
        }
    }

    private fun runWithTimeout(
        run: TimedRunContext,
        createHarness: () -> MatchFlowHarness,
        runGame: (MatchFlowHarness, PlayerLogWriter) -> GameStats,
    ): GameStats {
        val matchId = "simclient-${run.tag}"
        val harnessRef = AtomicReference<MatchFlowHarness?>()
        val timeoutMs = config.gameTimeoutSeconds * 1_000
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "simclient-${run.tag}").apply { isDaemon = true } }
        val future =
            executor.submit<GameStats> {
                val runBody = {
                    val harness = createHarness()
                    harnessRef.set(harness)
                    var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
                    val writer = run.logFile.bufferedWriter()
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
                if (config.verbose) runBody() else SimClientLogging.withRedirectedStdio(run.consoleLogFile, runBody)
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
        writeSimClientSidecar(
            run.logFile,
            matchId,
            run.runLabel,
            run.opponentRunLabel,
            run.seed,
            LocalDateTime.now(),
            run.runKind,
            deckOverlay = run.deckOverlay,
            opponentDeckOverlay = run.opponentDeckOverlay,
        )
        return stats
    }

    private fun resolveRowOverlay(row: SimClientRow): SimClientRow? {
        if (row !is DeckSimClientRow || !row.useCardDb) return row
        val quarantine = quarantineSpec(config)
        if (quarantine.isEmpty) return row
        val overlay = overlayDeck(row.deckList, quarantine, config.excludePolicy, cardRepo)
        val opponentOverlay = row.opponentDeckList?.let { overlayDeck(it, quarantine, config.excludePolicy, cardRepo) }
        if (overlay.skipped || opponentOverlay?.skipped == true) return null
        return row.copy(
            deckList = overlay.deckList,
            opponentDeckList = opponentOverlay?.deckList ?: row.opponentDeckList,
            overlay = overlay.report ?: row.overlay,
            opponentOverlay = opponentOverlay?.report ?: row.opponentOverlay,
        )
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
        var count = 0
        config.outDir.listFiles { file -> isSimClientGameLogFile(file) }.orEmpty().forEach { log ->
            ingestSimClientArtifacts(log, out)
            count += 1
        }
        println("Sim-client: $count game(s) ingested into $out")
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null && current.cause !== current) current = current.cause ?: current
        return current
    }
}

private data class TimedRunContext(
    val tag: String,
    val logFile: File,
    val consoleLogFile: File,
    val runLabel: String,
    val opponentRunLabel: String?,
    val seed: Long,
    val runKind: String,
    val deckOverlay: DeckOverlayReport?,
    val opponentDeckOverlay: DeckOverlayReport?,
)

internal fun isSimClientGameLogFile(file: File): Boolean = file.extension == "log" && !file.name.endsWith(".console.log")
