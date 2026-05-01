package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import leyline.SimClientTag
import leyline.conformance.MatchFlowHarness
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
class SimClientBatchTest : FunSpec({
    tags(SimClientTag)
    val outDir = File("build/simclient").also { it.mkdirs() }

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
    fun envOrProp(name: String): String? =
        System.getenv(name) ?: System.getProperty(name.lowercase().replace('_', '.'))

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

    fun runOne(
        deckName: String,
        deckList: String,
        seed: Long,
        maxTurns: Int,
        maxIterations: Int = 3_000,
    ): GameStats {
        val harness = MatchFlowHarness(seed = seed, deckList = deckList, validating = false)
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
        val driver = SimClientDriver(harness, playerLog, maxTurns = maxTurns, maxIterations = maxIterations)
        val stats = driver.runOneGame()
        writer.close()
        // Tag the log so scry-ts (and any downstream harness) can filter
        // synthetic logs out of conformance baselines.
        writeSimClientSidecar(
            logFile = logFile,
            matchId = "simclient-$tag",
            deckTag = deckName,
            seed = seed,
            generatedAt = LocalDateTime.now(),
        )
        return stats
    }

    /** Built-in deck table — names map to deck-list bodies. */
    val BUILTIN_DECKS: Map<String, String> =
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
        )

    /** Resolve a deck name → (name, list). Built-in first, then `data/decks/<name>`. */
    fun resolveDeck(name: String): Pair<String, String> {
        BUILTIN_DECKS[name]?.let { return name to it }
        return name.removeSuffix(".txt") to readDeck(if (name.endsWith(".txt")) name else "$name.txt")
    }

    test("batch — configurable deck × seed matrix").config(timeout = 8.minutes) {
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
                println(
                    "[$deckName s=$seed] turn=${stats.turn} gameOver=${stats.gameOver} " +
                        "iter=${stats.iterations} msgs=${stats.totalMessages} " +
                        "hitCap=${stats.hitIterCap} prompts=${stats.promptHistogram.size}",
                )
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
})
