package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import leyline.SimClientTag
import leyline.game.bundle.InvariantSelection
import leyline.game.data.CardRepository
import leyline.game.data.ExposedCardRepository
import leyline.testkit.MatchFlowHarness
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

/**
 * Batch runner — drives many (deck × seed) games to surface aggregate
 * insights. Not a strict assertion suite; the goal is to expose patterns
 * (hangs, prompt-type distributions, game lengths) that feed the next
 * iteration of the simclient.
 *
 * Output: per-game one-line summary printed to stdout, plus aggregated
 * histograms across all games. Player.log files land under build/simclient/
 * (re-runnable; cleared per batch).
 */
class SimClientBatchTest :
    FunSpec({
        tags(SimClientTag)
        val outDir = File("build/simclient").also { it.mkdirs() }
        val configuredBatchTimeoutMinutes =
            System.getProperty("simclient.test.timeout.minutes")
                ?: System.getenv("SIMCLIENT_TEST_TIMEOUT_MINUTES")
                ?: "120"
        val batchTimeoutMinutes =
            configuredBatchTimeoutMinutes.trim().toLong().coerceAtLeast(1)

        fun envOrProp(name: String): String? = System.getProperty(name.lowercase().replace('_', '.')) ?: System.getenv(name)

        // SQLite-backed card repo, built lazily on first test execution —
        // bypasses the YAML fixture path so any deck of installed cards runs
        // without a fixture emit step. The simclient policy is fail-fast:
        // LEYLINE_CARD_DB must be set explicitly, no autodetect. The same env
        // var the production server honours. Lazy keeps spec instantiation
        // cheap on testGate runs that filter SimClientTag out and never invoke
        // the body.
        val cardRepo: CardRepository by lazy {
            val cardDbPath =
                requireNotNull(envOrProp("LEYLINE_CARD_DB")) {
                    "LEYLINE_CARD_DB is not set. Point it at the local Raw_CardDatabase_*.mtga / *.sqlite file. " +
                        "For just recipes: set LEYLINE_CARD_DB to your Raw_CardDatabase path before `just simclient`. " +
                        "Custom decks live in data/decks/<name>.txt and are selected by basename."
                }
            require(File(cardDbPath).exists()) { "Card database not found at: $cardDbPath" }
            ExposedCardRepository(
                Database.connect(
                    "jdbc:sqlite:${File(cardDbPath).absolutePath}",
                    "org.sqlite.JDBC",
                ),
            )
        }

        /**
         * Configurable matrix via env vars / system properties.
         *
         * - `SIMCLIENT_DECKS` — comma-separated deck names (must match the
         *   built-in deck table below or be a path under `data/decks/<name>.txt`).
         *   Defaults to "forest-only,bears,mono-g-curve,mono-r-burn".
         * - `SIMCLIENT_SEEDS` — comma-separated longs OR a `start..end` range
         *   (inclusive). Defaults to "7,13,42,99,314".
         *
         * Examples:
         *   SIMCLIENT_DECKS=mono-r-burn SIMCLIENT_SEEDS=1..20 ./gradlew :simclient
         *   SIMCLIENT_DECKS=Auras.txt SIMCLIENT_SEEDS=42 ./gradlew :simclient
         */
        fun simclientValidation(): InvariantSelection = InvariantSelection.protocolFacts()

        fun assertNoValidationViolations(all: List<Triple<String, Long, GameStats>>) {
            val violations =
                all.flatMap { (name, seed, stats) ->
                    stats.validationViolations.map { "$name s=$seed: $it" }
                }
            violations.shouldBeEmpty()
        }

        fun parseSeeds(spec: String): List<Long> {
            if (spec.contains("..")) {
                val (lo, hi) = spec.split("..").map { it.trim().toLong() }
                return (lo..hi).toList()
            }
            return spec.split(",").map { it.trim().toLong() }
        }

        fun gameTimeoutMs(): Long = ((envOrProp("SIMCLIENT_GAME_TIMEOUT_SECONDS") ?: "120").trim().toLong().coerceAtLeast(1)) * 1_000

        fun maxTurns(): Int = (envOrProp("SIMCLIENT_MAX_TURNS") ?: "25").trim().toInt().coerceAtLeast(1)

        /** Read a deckfile from leyline's data/decks/, return its body. */
        fun readDeck(name: String): String {
            val candidates =
                listOf(
                    Paths.get("data/decks/$name"),
                    Paths.get("../data/decks/$name"),
                    Paths.get("../../data/decks/$name"),
                )
            val path =
                candidates.firstOrNull { Files.exists(it) }
                    ?: error("deck not found: $name in any of $candidates (cwd=${Paths.get("").toAbsolutePath()})")
            return Files.readString(path)
        }

        // SIMCLIENT_POLICY=greedy (default) | forge-ai
        // forge-ai consults a parallel PlayerControllerAi for ActionsAvailableReq
        // decisions; falls through to greedy for unhandled prompts.
        val usingForgeAi: Boolean =
            (envOrProp("SIMCLIENT_POLICY") ?: "greedy").trim().equals("forge-ai", ignoreCase = true)

        fun jsonString(s: String): String =
            buildString {
                append('"')
                s.forEach { c ->
                    when (c) {
                        '\\', '"' -> append('\\').append(c)
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        in '\u0000'..'\u001f' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
                append('"')
            }

        fun mapToJson(m: Map<String, Int>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:$v" }

        fun longMapToJson(m: Map<String, Long>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:$v" }

        fun stringMapToJson(m: Map<String, String>): String =
            m.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:${jsonString(v)}" }

        fun stringsToJson(values: List<String>): String = values.joinToString(",", "[", "]") { jsonString(it) }

        fun routeFindingsToJson(values: List<PromptRouteFinding>): String =
            values.joinToString(",", "[", "]") { finding ->
                buildString {
                    append('{')
                    append("\"bucket\":${jsonString(finding.bucket)},")
                    append("\"routeKey\":${jsonString(finding.routeKey)},")
                    append("\"enginePromptType\":${jsonString(finding.enginePromptType)},")
                    append("\"semantic\":${jsonString(finding.semantic)},")
                    append("\"expectedGreType\":${jsonString(finding.expectedGreType)},")
                    append("\"expectedCount\":${finding.expectedCount},")
                    append("\"emittedCount\":${finding.emittedCount},")
                    append("\"outcomeCounts\":${mapToJson(finding.outcomeCounts)},")
                    append("\"sampleMessage\":${jsonString(finding.sampleMessage)}")
                    append('}')
                }
            }

        fun simFindingsToJson(values: List<SimClientFinding>): String =
            values.joinToString(",", "[", "]") { finding ->
                buildString {
                    append('{')
                    append("\"kind\":${jsonString(finding.kind)},")
                    append("\"key\":${jsonString(finding.key)},")
                    append("\"count\":${finding.count},")
                    append("\"sample\":${jsonString(finding.sample)}")
                    append('}')
                }
            }

        fun fileSafeName(value: String): String =
            value
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-')
                .ifBlank { "deck" }

        fun statsToJson(
            deckName: String,
            opponentDeckName: String?,
            seed: Long,
            stats: GameStats,
        ): String {
            val policy = if (usingForgeAi) "forge-ai" else "greedy"
            val histo =
                stats.promptHistogram.entries.joinToString(",", "{", "}") { (k, v) ->
                    "${jsonString(k.name)}:$v"
                }
            return buildString {
                append('{')
                append("\"deck\":${jsonString(deckName)},")
                opponentDeckName?.let { append("\"opponentDeck\":${jsonString(it)},") }
                append("\"seed\":$seed,")
                append("\"policy\":${jsonString(policy)},")
                append("\"durationMs\":${stats.durationMs},")
                append("\"turn\":${stats.turn},")
                append("\"gameOver\":${stats.gameOver},")
                append("\"winnerSeat\":${stats.winnerSeat ?: "null"},")
                append("\"loserSeat\":${stats.loserSeat ?: "null"},")
                append("\"finalLifeBySeat\":${mapToJson(stats.finalLifeBySeat)},")
                append("\"finalStatusBySeat\":${stringMapToJson(stats.finalStatusBySeat)},")
                append("\"completionReason\":${jsonString(stats.completionReason)},")
                append("\"cleanupConcede\":${stats.cleanupConcede},")
                append("\"iterations\":${stats.iterations},")
                append("\"totalMessages\":${stats.totalMessages},")
                append("\"hitIterCap\":${stats.hitIterCap},")
                append("\"aiConsulted\":${stats.aiConsulted},")
                append("\"aiChose\":${stats.aiChose},")
                append("\"aiConsultedByPrompt\":${mapToJson(stats.aiConsultedByPrompt)},")
                append("\"aiChoseByPrompt\":${mapToJson(stats.aiChoseByPrompt)},")
                append("\"aiTotalMs\":${stats.aiTotalMs},")
                append("\"aiTotalMsByPrompt\":${longMapToJson(stats.aiTotalMsByPrompt)},")
                append("\"aiMaxMsByPrompt\":${longMapToJson(stats.aiMaxMsByPrompt)},")
                append("\"targetChoiceCounts\":${mapToJson(stats.targetChoiceCounts)},")
                append("\"targetChoiceSamples\":${stringMapToJson(stats.targetChoiceSamples)},")
                append("\"promptHistogram\":$histo,")
                append("\"promptRequestsByKind\":${mapToJson(stats.promptRequestsByKind)},")
                append("\"promptRequestSamplesByKind\":${stringMapToJson(stats.promptRequestSamplesByKind)},")
                append("\"promptRouteFindings\":${routeFindingsToJson(stats.promptRouteFindings)},")
                append("\"simFindings\":${simFindingsToJson(stats.simFindings)},")
                append("\"warnsByLogger\":${mapToJson(stats.warnsByLogger)},")
                append("\"errorsByType\":${mapToJson(stats.errorsByType)},")
                append("\"validationViolationsByCheck\":${mapToJson(stats.validationViolationsByCheck)},")
                append("\"validationViolations\":${stringsToJson(stats.validationViolations)},")
                append("\"promptRetiredByReason\":${mapToJson(stats.promptRetiredByReason)},")
                append("\"decisionOutcomes\":${mapToJson(stats.decisionOutcomes)},")
                append("\"actionAttemptsByType\":${mapToJson(stats.actionAttemptsByType)},")
                append("\"noPendingByDecision\":${mapToJson(stats.noPendingByDecision)},")
                append("\"skippedAlreadyTried\":${stats.skippedAlreadyTried},")
                append("\"connectMs\":${stats.connectMs},")
                append("\"stepTotalMs\":${stats.stepTotalMs},")
                append("\"stepMaxMs\":${stats.stepMaxMs},")
                append("\"flushTotalMs\":${stats.flushTotalMs},")
                append("\"flushMaxMs\":${stats.flushMaxMs},")
                append("\"autoPassTotalMs\":${stats.autoPassTotalMs},")
                append("\"autoPassMaxMs\":${stats.autoPassMaxMs},")
                append("\"policyTotalMsByPrompt\":${longMapToJson(stats.policyTotalMsByPrompt)},")
                append("\"policyMaxMsByPrompt\":${longMapToJson(stats.policyMaxMsByPrompt)},")
                append("\"submitTotalMsByDecision\":${longMapToJson(stats.submitTotalMsByDecision)},")
                append("\"submitMaxMsByDecision\":${longMapToJson(stats.submitMaxMsByDecision)},")
                append("\"stalledPrompt\":${stats.stalledPrompt?.let(::jsonString) ?: "null"},")
                append("\"stalledFingerprint\":${stats.stalledFingerprint?.let(::jsonString) ?: "null"}")
                append('}')
            }
        }

        fun timeoutStats(
            harness: MatchFlowHarness?,
            timeoutMs: Long,
        ): GameStats {
            val messages = runCatching { harness?.allMessages?.toList().orEmpty() }.getOrDefault(emptyList())
            val histogram =
                messages
                    .filter { isSimPrompt(it) }
                    .groupingBy { it.type }
                    .eachCount()
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

        fun runWithTimeout(
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
            val timeoutMs = gameTimeoutMs()
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "simclient-$tag").apply { isDaemon = true }
                }
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
            val stats =
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    val statsAtTimeout = timeoutStats(harnessRef.get(), timeoutMs)
                    future.cancel(true)
                    runCatching { harnessRef.get()?.shutdown() }
                    statsAtTimeout
                } finally {
                    executor.shutdownNow()
                }
            writeSimClientSidecar(
                logFile = logFile,
                matchId = matchId,
                runLabel = runLabel,
                opponentRunLabel = opponentRunLabel,
                seed = seed,
                generatedAt = LocalDateTime.now(),
                runKind = runKind,
            )
            File(outDir, "$tag.stats.json").writeText(statsToJson(runLabel, opponentRunLabel, seed, stats))
            if (stats.completionReason == "wall-timeout") {
                error("simclient game timed out after ${timeoutMs}ms: $tag")
            }
            return stats
        }

        fun runOne(
            deckName: String,
            deckList: String,
            opponentDeckName: String?,
            opponentDeckList: String?,
            seed: Long,
            maxTurns: Int,
            maxIterations: Int = 3_000,
        ): GameStats {
            val runName = opponentDeckName?.let { "$deckName-vs-$it" } ?: deckName
            val tag = "${fileSafeName(runName)}-s$seed"
            val logFile = File(outDir, "$tag.log")
            return runWithTimeout(
                tag = tag,
                logFile = logFile,
                runLabel = deckName,
                opponentRunLabel = opponentDeckName,
                seed = seed,
                runKind = "deck",
                createHarness = {
                    MatchFlowHarness(
                        seed = seed,
                        deckList = deckList,
                        opponentDeckList = opponentDeckList,
                        validation = simclientValidation(),
                        validationStrict = false,
                        cardRepositoryOverride = cardRepo,
                    )
                },
                runGame = { harness, playerLog ->
                    val forgeAi =
                        if (usingForgeAi) {
                            ForgeAiPolicy(harness, leyline.bridge.types.SeatId(1))
                        } else {
                            null
                        }
                    SimClientDriver(
                        harness,
                        playerLog,
                        maxTurns = maxTurns,
                        maxIterations = maxIterations,
                        forgeAi = forgeAi,
                    ).runOneGame()
                },
            )
        }

        /**
         * Drive a single puzzle-initialised game.
         *
         * Reads `puzzles/<name>` (resolved via `readPuzzle`) and starts the
         * harness through [MatchFlowHarness.connectAndKeepPuzzleText]. The
         * SimClientDriver loop is otherwise unchanged — same greedy responder,
         * same termination guards. Output goes under build/simclient/ tagged
         * `puzzle:<name>` for downstream filtering.
         */
        fun runOnePuzzle(
            puzzleName: String,
            puzzleText: String,
            seed: Long,
            maxTurns: Int = 30,
            maxIterations: Int = 3_000,
        ): GameStats {
            val tag = "$puzzleName-s$seed"
            val logFile = File(outDir, "$tag.log")
            return runWithTimeout(
                tag = tag,
                logFile = logFile,
                runLabel = puzzleName,
                opponentRunLabel = null,
                seed = seed,
                runKind = "puzzle",
                createHarness = {
                    MatchFlowHarness(
                        seed = seed,
                        deckList = null,
                        validation = simclientValidation(),
                        validationStrict = false,
                        cardRepositoryOverride = cardRepo,
                    )
                },
                runGame = { harness, playerLog ->
                    SimClientDriver(
                        harness,
                        playerLog,
                        maxTurns = maxTurns,
                        maxIterations = maxIterations,
                        connect = { harness.connectAndKeepPuzzleText(puzzleText) },
                    ).runOneGame()
                },
            )
        }

        /** Read a puzzle file from leyline's puzzles/, return its body. */
        fun readPuzzle(name: String): String {
            val candidates =
                listOf(
                    Paths.get("src/test/resources/puzzles/$name"),
                    Paths.get("puzzles/$name"),
                    Paths.get("../puzzles/$name"),
                    Paths.get("../../puzzles/$name"),
                )
            val path =
                candidates.firstOrNull { Files.exists(it) }
                    ?: error("puzzle not found: $name in any of $candidates (cwd=${Paths.get("").toAbsolutePath()})")
            return Files.readString(path)
        }

        /** Built-in deck table — names map to deck-list bodies. */
        val builtinDecks: Map<String, String> =
            mapOf(
                "forest-only" to "60 Forest",
                "bears" to "24 Forest\n36 Grizzly Bears",
                "mono-g-curve" to "24 Forest\n18 Grizzly Bears\n18 Centaur Courser",
                // Density + variety: mono-R burn-aggro. Instants + sorceries +
                // creatures of varying CMC. Forces SelectTargetsReq, casts at
                // instant-speed during combat, multi-creature board.
                "mono-r-burn" to
                    "20 Mountain\n4 Lightning Bolt\n4 Shock\n4 Burst Lightning\n" +
                    "4 Fiery Temper\n4 Lava Axe\n4 Raging Goblin\n4 Goblin Fireslinger\n" +
                    "4 Hurloon Minotaur\n4 Crackling Cyclops\n4 Monastery Swiftspear",
                // ETB-trigger density. All four creatures sit on simple ETB
                // triggers (cataloged notable instances) so the greedy responder
                // doesn't need to handle modal / alt-cost prompts. Shuffled with
                // 24 Plains + 12 vanilla creatures to keep games progressing
                // when triggers stall waiting on follow-up prompts.
                "etb-triggers" to
                    "24 Plains\n4 Reigning Victor\n4 Dalkovan Packbeasts\n" +
                    "4 Furious Forebear\n4 Stormchaser's Talent\n" +
                    "12 Savannah Lions\n4 Wall of Omens\n4 Soul Warden",
                // Kicker density — drives CastingTimeOption type=Kicker emission.
                // All non-land slots are cards with the Kicker keyword that exist
                // in the production card DB (verified against ability ids 1868 /
                // 2722 / etc.) so simclient seat 1 actually surfaces the prompt
                // instead of depending on the opposing seat to kick. Greedy
                // responder declines kicker (ctoId=0); forge-ai considers it.
                "kicker" to
                    "24 Forest\n4 Gnarlid Colony\n4 Territorial Allosaurus\n" +
                    "4 Cragplate Baloth\n4 Inscription of Abundance\n" +
                    "4 Llanowar Elves\n8 Grizzly Bears\n8 Centaur Courser",
            )

        /** Resolve a deck name → (name, list). Built-in first, then `data/decks/<name>`. */
        fun resolveDeck(name: String): Pair<String, String> {
            builtinDecks[name]?.let { return name to it }
            return name.removeSuffix(".txt") to readDeck(if (name.endsWith(".txt")) name else "$name.txt")
        }

        test("batch — configurable deck × seed matrix").config(timeout = batchTimeoutMinutes.minutes) {
            // Mutually exclusive with the puzzle-matrix test below.
            if (envOrProp("SIMCLIENT_PUZZLE") != null) return@config
            val deckSpec = envOrProp("SIMCLIENT_DECKS") ?: "forest-only,bears,mono-g-curve,mono-r-burn"
            val opponentDeck =
                envOrProp("SIMCLIENT_OPPONENT_DECK")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { resolveDeck(it) }
            val seedSpec = envOrProp("SIMCLIENT_SEEDS") ?: "7,13,42,99,314"
            val maxTurns = maxTurns()
            val decks = deckSpec.split(",").map { it.trim() }.map { resolveDeck(it) }
            val seeds = parseSeeds(seedSpec)
            println(
                "=== matrix: ${decks.size} deck(s) × ${seeds.size} seed(s) = ${decks.size * seeds.size} games " +
                    "maxTurns=$maxTurns gameTimeoutMs=${gameTimeoutMs()} " +
                    "opponent=${opponentDeck?.first ?: "mirror"} ===",
            )
            val all = mutableListOf<Triple<String, Long, GameStats>>()
            for ((deckName, deckList) in decks) {
                for (seed in seeds) {
                    val stats =
                        runOne(
                            deckName,
                            deckList,
                            opponentDeckName = opponentDeck?.first,
                            opponentDeckList = opponentDeck?.second,
                            seed = seed,
                            maxTurns = maxTurns,
                        )
                    all.add(Triple(deckName, seed, stats))
                    val warnTotal = stats.warnsByLogger.values.sum()
                    val errTotal = stats.errorsByType.values.sum()
                    val validationTotal = stats.validationViolationsByCheck.values.sum()
                    val aiSummary =
                        if (stats.aiConsulted > 0) {
                            "ai=${stats.aiChose}/${stats.aiConsulted} aiMs=${stats.aiTotalMs} "
                        } else {
                            ""
                        }
                    println(
                        "[$deckName s=$seed] ${stats.durationMs}ms turn=${stats.turn} " +
                            "gameOver=${stats.gameOver} reason=${stats.completionReason} iter=${stats.iterations} " +
                            "msgs=${stats.totalMessages} hitCap=${stats.hitIterCap} " +
                            "prompts=${stats.promptHistogram.size} " +
                            "${aiSummary}warns=$warnTotal errs=$errTotal validation=$validationTotal",
                    )
                    if (stats.aiConsulted > 0) {
                        stats.aiConsultedByPrompt.entries.sortedBy { it.key }.forEach { (prompt, consulted) ->
                            val chose = stats.aiChoseByPrompt[prompt] ?: 0
                            val totalMs = stats.aiTotalMsByPrompt[prompt] ?: 0
                            val maxMs = stats.aiMaxMsByPrompt[prompt] ?: 0
                            println("    ai    $prompt = $chose / $consulted totalMs=$totalMs maxMs=$maxMs")
                        }
                    }
                    stats.submitTotalMsByDecision.entries
                        .sortedByDescending { it.value }
                        .take(4)
                        .forEach { (decision, totalMs) ->
                            val maxMs = stats.submitMaxMsByDecision[decision] ?: 0
                            println("    submit $decision totalMs=$totalMs maxMs=$maxMs")
                        }
                    if (warnTotal > 0) {
                        stats.warnsByLogger.entries
                            .sortedByDescending { it.value }
                            .take(4)
                            .forEach { (logger, n) ->
                                println("    warn  $logger = $n")
                            }
                    }
                    if (errTotal > 0) {
                        stats.errorsByType.entries.sortedByDescending { it.value }.forEach { (cls, n) ->
                            println("    err   $cls = $n")
                        }
                    }
                    if (validationTotal > 0) {
                        stats.validationViolationsByCheck.entries
                            .sortedByDescending { it.value }
                            .forEach { (check, n) -> println("    inv   $check = $n") }
                    }
                    if (stats.promptRetiredByReason.isNotEmpty()) {
                        stats.promptRetiredByReason.entries
                            .sortedByDescending { it.value }
                            .forEach { (reason, n) -> println("    retire $reason = $n") }
                    }
                    if (stats.promptRouteFindings.isNotEmpty()) {
                        stats.promptRouteFindings.forEach { finding ->
                            println(
                                "    route ${finding.bucket} ${finding.routeKey} -> ${finding.expectedGreType} " +
                                    "expected=${finding.expectedCount} emitted=${finding.emittedCount} outcomes=${finding.outcomeCounts}",
                            )
                        }
                    }
                }
            }

            // Aggregate across all games
            println("\n=== aggregate prompt histogram across batch ===")
            val agg = mutableMapOf<String, Int>()
            all.forEach { (_, _, s) ->
                s.promptHistogram.forEach { (k, v) ->
                    agg.merge(k.name, v) { a, b -> a + b }
                }
            }
            agg.entries.sortedByDescending { it.value }.forEach { (k, v) -> println("  $k = $v") }

            val routeAgg = mutableMapOf<String, Int>()
            all.forEach { (_, _, s) ->
                s.promptRequestsByKind.forEach { (k, v) -> routeAgg.merge(k, v) { a, b -> a + b } }
            }
            if (routeAgg.isNotEmpty()) {
                println("\n=== aggregate engine prompt requests ===")
                routeAgg.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    val sample = all.firstNotNullOfOrNull { it.third.promptRequestSamplesByKind[k] }
                    val suffix = sample?.let { " sample=$it" }.orEmpty()
                    println("  $k = $v$suffix")
                }
            }

            val routeFindings = all.flatMap { (deck, seed, stats) -> stats.promptRouteFindings.map { Triple(deck, seed, it) } }
            if (routeFindings.isNotEmpty()) {
                println("\n=== prompt route audit findings ===")
                routeFindings.forEach { (deck, seed, finding) ->
                    println(
                        "  [$deck s=$seed] ${finding.bucket}: ${finding.routeKey} -> ${finding.expectedGreType} " +
                            "expected=${finding.expectedCount} emitted=${finding.emittedCount} sample=${finding.sampleMessage}",
                    )
                }
            }

            println("\n=== summary ===")
            println("games run: ${all.size}")
            println("games ended (gameOver): ${all.count { it.third.gameOver }}")
            println("games hit iter cap: ${all.count { it.third.hitIterCap }}")
            println("avg turns: ${"%.1f".format(all.map { it.third.turn }.average())}")
            println("avg msgs: ${"%.0f".format(all.map { it.third.totalMessages }.average())}")
            assertNoValidationViolations(all)
        }

        /**
         * Puzzle-driven matrix.
         *
         * Activated when `SIMCLIENT_PUZZLE` is set: comma-separated list of
         * puzzle filenames under `puzzles/` (e.g. `bolt-face.pzl`). Same
         * `SIMCLIENT_SEEDS` parsing as the deck path. Each (puzzle × seed)
         * starts the harness from the puzzle's declared board state, then
         * runs the greedy responder until game-over or termination guard.
         *
         * The puzzle path skips mulligan + turn advancement, so seeds influence
         * later RNG decisions (random-target selection, AI choices) rather
         * than the opening hand.
         */
        test("batch — puzzle matrix").config(timeout = batchTimeoutMinutes.minutes) {
            val puzzleSpec = envOrProp("SIMCLIENT_PUZZLE") ?: return@config
            val seedSpec = envOrProp("SIMCLIENT_SEEDS") ?: "7,13,42,99,314"
            val puzzleNames = puzzleSpec.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val seeds = parseSeeds(seedSpec)
            println("=== puzzle matrix: ${puzzleNames.size} puzzle(s) × ${seeds.size} seed(s) = ${puzzleNames.size * seeds.size} games ===")
            val all = mutableListOf<Triple<String, Long, GameStats>>()
            for (puzzleName in puzzleNames) {
                val puzzleText =
                    try {
                        readPuzzle(puzzleName)
                    } catch (e: Exception) {
                        println("[$puzzleName] skipping: ${e.message}")
                        continue
                    }
                val basename = puzzleName.removeSuffix(".pzl").substringAfterLast('/')
                for (seed in seeds) {
                    try {
                        val stats = runOnePuzzle(basename, puzzleText, seed)
                        all.add(Triple(basename, seed, stats))
                        println(
                            "[$basename s=$seed] turn=${stats.turn} gameOver=${stats.gameOver} " +
                                "iter=${stats.iterations} msgs=${stats.totalMessages} " +
                                "hitCap=${stats.hitIterCap} prompts=${stats.promptHistogram.size}",
                        )
                    } catch (t: Throwable) {
                        if (t.message?.contains("simclient game timed out") == true) throw t
                        println("[$basename s=$seed] CRASH: ${t::class.qualifiedName}: ${t.message}")
                        t.stackTrace.take(15).forEach { println("    at $it") }
                    }
                }
            }
            println("\n=== puzzle summary ===")
            println("games run: ${all.size}")
            println("games ended (gameOver): ${all.count { it.third.gameOver }}")
            println("games hit iter cap: ${all.count { it.third.hitIterCap }}")
            require(all.isNotEmpty()) { "no requested simclient puzzles were found or run" }
            assertNoValidationViolations(all)
        }
    })
