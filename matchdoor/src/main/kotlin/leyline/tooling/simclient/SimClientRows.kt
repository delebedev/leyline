package leyline.tooling.simclient

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.absoluteValue

sealed interface SimClientRow {
    val name: String
    val seed: Long
    val runKind: String
    val runLabel: String
    val opponentRunLabel: String?
    val useCardDb: Boolean

    val tag: String
        get() {
            val base = opponentRunLabel?.let { "$runLabel-vs-$it" } ?: runLabel
            return "${fileSafeName(base)}-s$seed"
        }

    val identity: String
        get() = listOf(runKind, runLabel, opponentRunLabel.orEmpty(), seed.toString()).joinToString("|")
}

data class DeckSimClientRow(
    override val name: String,
    val deckList: String,
    val opponentName: String?,
    val opponentDeckList: String?,
    override val useCardDb: Boolean,
    override val seed: Long,
) : SimClientRow {
    override val runKind: String = "deck"
    override val runLabel: String = name
    override val opponentRunLabel: String? = opponentName
}

data class PuzzleSimClientRow(
    override val name: String,
    val puzzleText: String,
    override val useCardDb: Boolean,
    override val seed: Long,
) : SimClientRow {
    override val runKind: String = "puzzle"
    override val runLabel: String = name
    override val opponentRunLabel: String? = null
}

fun expandSimClientRows(config: SimClientConfig): List<SimClientRow> = expandRows(config).filter { row -> includedInShard(config, row) }

fun fileSafeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "deck" }

private fun expandRows(config: SimClientConfig): List<SimClientRow> {
    val seeds = parseSeeds(config.seedSpec)
    val puzzleSpec = config.puzzleSpec
    if (puzzleSpec != null) {
        return puzzleSpec
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { puzzleName ->
                val puzzleText = readPuzzle(puzzleName)
                val basename = puzzleName.removeSuffix(".pzl").substringAfterLast('/')
                seeds.map { seed ->
                    PuzzleSimClientRow(basename, puzzleText, useCardDb = config.cardDbPath != null, seed)
                }
            }
    }
    val opponent = config.opponentDeck?.let { resolveDeck(it) }
    return config.deckSpec
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { resolveDeck(it) }
        .flatMap { deck ->
            seeds.map { seed ->
                DeckSimClientRow(
                    name = deck.name,
                    deckList = deck.deckList,
                    opponentName = opponent?.name,
                    opponentDeckList = opponent?.deckList,
                    useCardDb = deck.useCardDb || (opponent?.useCardDb ?: false),
                    seed = seed,
                )
            }
        }
}

private fun includedInShard(
    config: SimClientConfig,
    row: SimClientRow,
): Boolean {
    val shardCount = config.shardCount ?: return true
    val shardIndex = config.shardIndex ?: return true
    return row.identity.hashCode().absoluteValue % shardCount == shardIndex
}

private fun parseSeeds(spec: String): List<Long> {
    if (spec.contains("..")) {
        val (lo, hi) = spec.split("..").map { it.trim().toLong() }
        return (lo..hi).toList()
    }
    return spec.split(",").map { it.trim().toLong() }
}

private fun resolveDeck(name: String): ResolvedDeck {
    builtinDecks[name]?.let { return ResolvedDeck(name, it, useCardDb = false) }
    return ResolvedDeck(
        name.removeSuffix(".txt"),
        readDeck(if (name.endsWith(".txt")) name else "$name.txt"),
        useCardDb = true,
    )
}

private data class ResolvedDeck(
    val name: String,
    val deckList: String,
    val useCardDb: Boolean,
)

private fun readDeck(name: String): String {
    val candidates =
        listOf(
            Paths.get("data/decks/$name"),
            Paths.get("../data/decks/$name"),
            Paths.get("../../data/decks/$name"),
        )
    val path = candidates.firstOrNull { Files.exists(it) } ?: error("deck not found: $name in any of $candidates")
    return Files.readString(path)
}

private fun readPuzzle(name: String): String {
    val candidates =
        listOf(
            Paths.get("src/test/resources/puzzles/$name"),
            Paths.get("puzzles/$name"),
            Paths.get("../puzzles/$name"),
            Paths.get("../../puzzles/$name"),
        )
    val path = candidates.firstOrNull { Files.exists(it) } ?: error("puzzle not found: $name in any of $candidates")
    return Files.readString(path)
}

private val builtinDecks: Map<String, String> =
    mapOf(
        "forest-only" to "60 Forest",
        "bears" to "24 Forest\n36 Grizzly Bears",
        "mono-g-curve" to "24 Forest\n18 Grizzly Bears\n18 Centaur Courser",
        "mono-r-burn" to
            "20 Mountain\n4 Lightning Bolt\n4 Shock\n4 Burst Lightning\n" +
            "4 Fiery Temper\n4 Lava Axe\n4 Raging Goblin\n4 Goblin Fireslinger\n" +
            "4 Hurloon Minotaur\n4 Crackling Cyclops\n4 Monastery Swiftspear",
        "etb-triggers" to
            "24 Plains\n4 Reigning Victor\n4 Dalkovan Packbeasts\n" +
            "4 Furious Forebear\n4 Stormchaser's Talent\n" +
            "12 Savannah Lions\n4 Wall of Omens\n4 Soul Warden",
        "kicker" to
            "24 Forest\n4 Gnarlid Colony\n4 Territorial Allosaurus\n" +
            "4 Cragplate Baloth\n4 Inscription of Abundance\n" +
            "4 Llanowar Elves\n8 Grizzly Bears\n8 Centaur Courser",
    )
