package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import leyline.SimClientTag
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.game.data.CardRepository
import leyline.game.data.ExposedCardRepository
import leyline.testkit.MatchFlowHarness
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
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

        // SQLite-backed card repo, built lazily on first test execution —
        // bypasses the YAML fixture path so any deck of installed cards runs
        // without a fixture emit step. The simclient policy is fail-fast:
        // LEYLINE_CARD_DB must be set explicitly, no autodetect. The same env
        // var the production server honours. Lazy keeps spec instantiation
        // cheap on testGate runs that filter SimClientTag out and never invoke
        // the body.
        val cardRepo: CardRepository by lazy {
            val cardDbPath =
                requireNotNull(System.getenv("LEYLINE_CARD_DB")) {
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
        fun envOrProp(name: String): String? = System.getenv(name) ?: System.getProperty(name.lowercase().replace('_', '.'))

        fun simclientRelaxedValidation(): InvariantSelection =
            InvariantSelection.except(
                "simclient driver can replay older queued ids around play-land diffs (leyline-qiws)",
                InvariantCheck.GsIdMonotonicity,
                InvariantCheck.MsgIdMonotonicity,
                InvariantCheck.GsIdPrevKnown,
            )

        fun parseSeeds(spec: String): List<Long> {
            if (spec.contains("..")) {
                val (lo, hi) = spec.split("..").map { it.trim().toLong() }
                return (lo..hi).toList()
            }
            return spec.split(",").map { it.trim().toLong() }
        }

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

        fun stringsToJson(values: List<String>): String = values.joinToString(",", "[", "]") { jsonString(it) }

        fun statsToJson(
            deckName: String,
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
                append("\"seed\":$seed,")
                append("\"policy\":${jsonString(policy)},")
                append("\"durationMs\":${stats.durationMs},")
                append("\"turn\":${stats.turn},")
                append("\"gameOver\":${stats.gameOver},")
                append("\"completionReason\":${jsonString(stats.completionReason)},")
                append("\"cleanupConcede\":${stats.cleanupConcede},")
                append("\"iterations\":${stats.iterations},")
                append("\"totalMessages\":${stats.totalMessages},")
                append("\"hitIterCap\":${stats.hitIterCap},")
                append("\"aiConsulted\":${stats.aiConsulted},")
                append("\"aiChose\":${stats.aiChose},")
                append("\"aiConsultedByPrompt\":${mapToJson(stats.aiConsultedByPrompt)},")
                append("\"aiChoseByPrompt\":${mapToJson(stats.aiChoseByPrompt)},")
                append("\"promptHistogram\":$histo,")
                append("\"warnsByLogger\":${mapToJson(stats.warnsByLogger)},")
                append("\"errorsByType\":${mapToJson(stats.errorsByType)},")
                append("\"validationViolationsByCheck\":${mapToJson(stats.validationViolationsByCheck)},")
                append("\"validationViolations\":${stringsToJson(stats.validationViolations)},")
                append("\"promptRetiredByReason\":${mapToJson(stats.promptRetiredByReason)},")
                append("\"decisionOutcomes\":${mapToJson(stats.decisionOutcomes)},")
                append("\"actionAttemptsByType\":${mapToJson(stats.actionAttemptsByType)},")
                append("\"noPendingByDecision\":${mapToJson(stats.noPendingByDecision)},")
                append("\"skippedAlreadyTried\":${stats.skippedAlreadyTried},")
                append("\"stalledPrompt\":${stats.stalledPrompt?.let(::jsonString) ?: "null"},")
                append("\"stalledFingerprint\":${stats.stalledFingerprint?.let(::jsonString) ?: "null"}")
                append('}')
            }
        }

        fun runOne(
            deckName: String,
            deckList: String,
            seed: Long,
            maxTurns: Int,
            maxIterations: Int = 3_000,
        ): GameStats {
            val harness =
                MatchFlowHarness(
                    seed = seed,
                    deckList = deckList,
                    validation = simclientRelaxedValidation(),
                    validationStrict = false,
                    cardRepositoryOverride = cardRepo,
                )
            val tag = "$deckName-s$seed"
            val logFile = File(outDir, "$tag.log")
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val writer = logFile.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "simclient-$tag",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            val forgeAi =
                if (usingForgeAi) {
                    ForgeAiPolicy(harness, leyline.bridge.types.SeatId(1))
                } else {
                    null
                }
            val driver =
                SimClientDriver(
                    harness,
                    playerLog,
                    maxTurns = maxTurns,
                    maxIterations = maxIterations,
                    forgeAi = forgeAi,
                )
            val stats = driver.runOneGame()
            writer.close()
            // Tag the log so scry-ts (and any downstream harness) can filter
            // synthetic logs out of conformance baselines.
            writeSimClientSidecar(
                logFile = logFile,
                matchId = "simclient-$tag",
                runLabel = deckName,
                seed = seed,
                generatedAt = LocalDateTime.now(),
                runKind = "deck",
            )
            // Per-game telemetry sidecar — tools/playwheel reads these to
            // attribute warns/errors and timing back to (deck × seed).
            File(outDir, "$tag.stats.json").writeText(statsToJson(deckName, seed, stats))
            return stats
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
            val harness =
                MatchFlowHarness(
                    seed = seed,
                    deckList = null,
                    validation = simclientRelaxedValidation(),
                    validationStrict = false,
                    cardRepositoryOverride = cardRepo,
                )
            val tag = "$puzzleName-s$seed"
            val logFile = File(outDir, "$tag.log")
            var fakeNow = LocalDateTime.of(2026, 5, 1, 12, 0, 0)
            val writer = logFile.bufferedWriter()
            val playerLog =
                PlayerLogWriter(
                    out = writer,
                    matchId = "simclient-$tag",
                    clock = {
                        fakeNow = fakeNow.plusSeconds(1)
                        fakeNow
                    },
                )
            val driver =
                SimClientDriver(
                    harness,
                    playerLog,
                    maxTurns = maxTurns,
                    maxIterations = maxIterations,
                    connect = { harness.connectAndKeepPuzzleText(puzzleText) },
                )
            val stats = driver.runOneGame()
            writer.close()
            writeSimClientSidecar(
                logFile = logFile,
                matchId = "simclient-$tag",
                runLabel = puzzleName,
                seed = seed,
                generatedAt = LocalDateTime.now(),
                runKind = "puzzle",
            )
            return stats
        }

        /** Read a puzzle file from leyline's puzzles/, return its body. */
        fun readPuzzle(name: String): String {
            val candidates =
                listOf(
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

        test("batch — configurable deck × seed matrix").config(timeout = 8.minutes) {
            // Mutually exclusive with the puzzle-matrix test below.
            if (envOrProp("SIMCLIENT_PUZZLE") != null) return@config
            val deckSpec = envOrProp("SIMCLIENT_DECKS") ?: "forest-only,bears,mono-g-curve,mono-r-burn"
            val seedSpec = envOrProp("SIMCLIENT_SEEDS") ?: "7,13,42,99,314"
            val decks = deckSpec.split(",").map { it.trim() }.map { resolveDeck(it) }
            val seeds = parseSeeds(seedSpec)
            println("=== matrix: ${decks.size} deck(s) × ${seeds.size} seed(s) = ${decks.size * seeds.size} games ===")
            val all = mutableListOf<Triple<String, Long, GameStats>>()
            for ((deckName, deckList) in decks) {
                for (seed in seeds) {
                    val stats = runOne(deckName, deckList, seed, maxTurns = 30)
                    all.add(Triple(deckName, seed, stats))
                    val warnTotal = stats.warnsByLogger.values.sum()
                    val errTotal = stats.errorsByType.values.sum()
                    val validationTotal = stats.validationViolationsByCheck.values.sum()
                    val aiSummary =
                        if (stats.aiConsulted > 0) {
                            "ai=${stats.aiChose}/${stats.aiConsulted} "
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
                            println("    ai    $prompt = $chose / $consulted")
                        }
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

            println("\n=== summary ===")
            println("games run: ${all.size}")
            println("games ended (gameOver): ${all.count { it.third.gameOver }}")
            println("games hit iter cap: ${all.count { it.third.hitIterCap }}")
            println("avg turns: ${"%.1f".format(all.map { it.third.turn }.average())}")
            println("avg msgs: ${"%.0f".format(all.map { it.third.totalMessages }.average())}")
        }

        test("batch — Simple test.txt (real seed deck) ×3 seeds").config(timeout = 5.minutes) {
            if (envOrProp("SIMCLIENT_PUZZLE") != null) return@config
            val deckList =
                try {
                    readDeck("Simple test.txt")
                } catch (e: Exception) {
                    println("skipping: ${e.message}")
                    null
                }
            if (deckList == null) return@config
            val seeds = listOf(42L, 7L, 99L)
            val results = mutableListOf<GameStats>()
            for (seed in seeds) {
                try {
                    val stats = runOne("simple", deckList, seed, maxTurns = 25, maxIterations = 1_500)
                    results.add(stats)
                    println(
                        "[simple s=$seed] turn=${stats.turn} gameOver=${stats.gameOver} " +
                            "iter=${stats.iterations} msgs=${stats.totalMessages} hitCap=${stats.hitIterCap}",
                    )
                    println("  prompt mix:")
                    stats.promptHistogram.entries.sortedByDescending { it.value }.take(8).forEach {
                        println("    ${it.key.name} = ${it.value}")
                    }
                } catch (t: Throwable) {
                    println("[simple s=$seed] CRASH: ${t::class.qualifiedName}: ${t.message}")
                    t.stackTrace.take(15).forEach { println("    at $it") }
                    t.cause?.let { println("  caused by: ${it::class.qualifiedName}: ${it.message}") }
                }
            }
            println("\n=== Simple test summary ===")
            println("games completed: ${results.size}/${seeds.size}")
            if (results.isNotEmpty()) {
                println("avg turns: ${"%.1f".format(results.map { it.turn }.average())}")
                println("hit iter cap: ${results.count { it.hitIterCap }}")
            }
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
        test("batch — puzzle matrix").config(timeout = 8.minutes) {
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
                        println("[$basename s=$seed] CRASH: ${t::class.qualifiedName}: ${t.message}")
                        t.stackTrace.take(15).forEach { println("    at $it") }
                    }
                }
            }
            println("\n=== puzzle summary ===")
            println("games run: ${all.size}")
            println("games ended (gameOver): ${all.count { it.third.gameOver }}")
            println("games hit iter cap: ${all.count { it.third.hitIterCap }}")
        }
    })
