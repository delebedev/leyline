package leyline.tooling.simclient

import leyline.game.generator.PuzzleLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.absoluteValue

sealed interface SimClientRow {
    val name: String
    val seed: Long
    val runKind: String
    val runLabel: String
    val opponentRunLabel: String?

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
    val overlay: DeckOverlayReport? = null,
    val opponentOverlay: DeckOverlayReport? = null,
    override val seed: Long,
) : SimClientRow {
    override val runKind: String = "deck"
    override val runLabel: String = name
    override val opponentRunLabel: String? = opponentName
}

data class PuzzleSimClientRow(
    override val name: String,
    val puzzleText: String,
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
                    PuzzleSimClientRow(basename, puzzleText, seed)
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

private fun resolveDeck(name: String): ResolvedDeck =
    ResolvedDeck(
        name.removeSuffix(".txt"),
        readDeck(if (name.endsWith(".txt")) name else "$name.txt"),
    )

private data class ResolvedDeck(
    val name: String,
    val deckList: String,
)

private fun readDeck(name: String): String {
    val contentRoot = Path.of(System.getProperty("leyline.content.root", ".")).toAbsolutePath().normalize()
    val path = contentRoot.resolve("data/decks/$name")
    require(Files.isRegularFile(path)) { "deck not found: $name in $path" }
    return Files.readString(path)
}

private fun readPuzzle(name: String): String {
    val identity = name.removePrefix("data/puzzles/")
    return PuzzleLibrary.fromConfiguredContentRoot().require(identity).content
}
