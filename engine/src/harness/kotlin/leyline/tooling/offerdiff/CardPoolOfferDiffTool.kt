package leyline.tooling.offerdiff

import forge.game.zone.ZoneType
import leyline.tooling.simclient.SimClientCardCatalog
import leyline.tooling.simclient.mapToJson
import leyline.tooling.simclient.openSimClientCardRepository
import leyline.tooling.simclient.simJsonString
import java.io.File
import kotlin.system.exitProcess

/**
 * Command-line entry for [CardPoolOfferDiff]: reads card names, runs one state
 * per card and zone, and writes `results.jsonl` (one line per card and zone)
 * and `summary.json` (verdicts, gap counts by bucket and API type, coverage).
 */
fun main(args: Array<String>) {
    exitProcess(CardPoolOfferDiffTool.run(args.toList()))
}

internal object CardPoolOfferDiffTool {
    fun run(args: List<String>): Int {
        val options =
            try {
                parse(args) ?: return 0
            } catch (ex: IllegalArgumentException) {
                return usageError(ex)
            } catch (ex: IllegalStateException) {
                return usageError(ex)
            }
        val cards = options.cards
        options.outDir.mkdirs()
        val repository = openSimClientCardRepository(options.catalog, System.getenv("LEYLINE_CARD_DB"))
        val pool = CardPoolOfferDiff(repository, options.zones)
        val started = System.nanoTime()
        val resultsFile = File(options.outDir, "results.jsonl")
        resultsFile.writeText("")
        val results =
            pool.runAll(cards) { result ->
                resultsFile.appendText(resultToJson(result) + "\n")
                println("[${result.verdict}] ${result.card} ${result.zone}${result.error?.let { " $it" }.orEmpty()}")
            }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        val summary = summaryJson(cards, results, elapsedMs, options)
        File(options.outDir, "summary.json").writeText(summary)
        printSummary(cards, results, elapsedMs)
        // A run that compared nothing is broken, not clean.
        return if (results.isEmpty() || results.all { it.verdict == CardPoolOfferDiff.Verdict.Error }) 1 else 0
    }

    private fun usageError(ex: RuntimeException): Int {
        System.err.println("offer-diff-pool: ${ex.message}")
        System.err.println(USAGE)
        return 2
    }

    private data class Options(
        val cards: List<String>,
        val zones: List<ZoneType>,
        val catalog: SimClientCardCatalog,
        val outDir: File,
    )

    private fun parse(args: List<String>): Options? {
        var cards = emptyList<String>()
        var zones = CardPoolOfferDiff.DEFAULT_ZONES
        var catalog = SimClientCardCatalog.Client
        var outDir = File("engine/build/offer-diff-pool")
        var i = 0
        while (i < args.size) {
            val value = { requireNotNull(args.getOrNull(i + 1)) { "missing value for ${args[i]}" } }
            when (args[i]) {
                "--cards" -> cards = cards + value().split(";").map { it.trim() }.filter { it.isNotEmpty() }
                "--cards-file" ->
                    cards = cards + readCardsFile(File(value()))
                "--zones" -> zones = parseZones(value())
                "--card-catalog" -> catalog = SimClientCardCatalog.parse(value())
                "--out-dir" -> outDir = File(value())
                "--help", "-h" -> {
                    println(USAGE)
                    return null
                }
                else -> throw IllegalArgumentException("unknown argument: ${args[i]}")
            }
            i += 2
        }
        require(cards.isNotEmpty()) { "no cards given: use --cards or --cards-file" }
        return Options(cards.distinct(), zones, catalog, outDir)
    }

    private fun readCardsFile(file: File): List<String> {
        require(file.isFile) { "no such cards file: $file" }
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun parseZones(value: String): List<ZoneType> =
        value.split(",").map { name ->
            CardPoolOfferDiff.DEFAULT_ZONES.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "unsupported zone: $name; use ${CardPoolOfferDiff.DEFAULT_ZONES.joinToString(",") { it.name.lowercase() }}",
                )
        }

    private fun resultToJson(result: CardPoolOfferDiff.ZoneResult): String =
        buildString {
            append('{')
            append("\"card\":${simJsonString(result.card)},")
            append("\"zone\":${simJsonString(result.zone.name)},")
            append("\"verdict\":${simJsonString(result.verdict.name)},")
            append("\"extraPlays\":${result.extraPlays.joinToString(",", "[", "]") { simJsonString(it) }},")
            append(
                result.findings.joinToString(",", "\"findings\":[", "],") { finding ->
                    val key = finding.key
                    "{\"bucket\":${simJsonString(key.bucket.name)},\"rule\":${key.rule?.let { simJsonString(it.name) } ?: "null"}," +
                        "\"apiType\":${simJsonString(key.apiType)},\"ability\":${simJsonString(key.ability)}}"
                },
            )
            append("\"baselineFindings\":${result.baselineFindings.size},")
            append("\"failures\":${mapToJson(result.failures)},")
            append("\"error\":${result.error?.let { simJsonString(it) } ?: "null"}")
            append('}')
        }

    private fun cardVerdicts(results: List<CardPoolOfferDiff.ZoneResult>): Map<String, CardPoolOfferDiff.Verdict> =
        results.groupBy { it.card }.mapValues { (_, zones) ->
            // An errored zone does not hide what the card's other zones found.
            zones.map { it.verdict }.filter { it != CardPoolOfferDiff.Verdict.Error }.minOrNull()
                ?: CardPoolOfferDiff.Verdict.Error
        }

    /** Unnamed differences on the cards under test, by bucket and Forge API type. */
    private fun gapsByBucketAndApi(results: List<CardPoolOfferDiff.ZoneResult>): Map<String, Int> =
        results
            .flatMap { result ->
                result.findings.filter { it.key.rule == null }.map {
                    "${it.key.bucket.name}/${it.key.apiType}" to
                        result.card
                }
            }.distinct()
            .groupingBy { it.first }
            .eachCount()
            .toSortedMap()

    private fun summaryJson(
        cards: List<String>,
        results: List<CardPoolOfferDiff.ZoneResult>,
        elapsedMs: Long,
        options: Options,
    ): String {
        val verdicts = cardVerdicts(results)
        val covered = results.filter { it.extraPlays.isNotEmpty() }.map { it.card }.toSet()
        return buildString {
            append('{')
            append("\"cards\":${cards.size},")
            append("\"states\":${results.size},")
            append("\"erroredStates\":${results.count { it.verdict == CardPoolOfferDiff.Verdict.Error }},")
            append("\"zones\":${options.zones.joinToString(",", "[", "]") { simJsonString(it.name) }},")
            append("\"catalog\":${simJsonString(options.catalog.name)},")
            append("\"elapsedMs\":$elapsedMs,")
            append("\"coveredCards\":${covered.size},")
            append(
                "\"verdicts\":${mapToJson(
                    verdicts.values
                        .groupingBy { it.name }
                        .eachCount()
                        .toSortedMap(),
                )},",
            )
            append("\"cardsWithUnnamedGapByBucketAndApi\":${mapToJson(gapsByBucketAndApi(results))},")
            append("\"baselineStatesWithFindings\":${results.count { it.baselineFindings.isNotEmpty() }},")
            append(
                "\"cardVerdicts\":{${verdicts.entries.joinToString(
                    ",",
                ) { (card, v) -> "${simJsonString(card)}:${simJsonString(v.name)}" }}}",
            )
            append('}')
        }
    }

    private fun printSummary(
        cards: List<String>,
        results: List<CardPoolOfferDiff.ZoneResult>,
        elapsedMs: Long,
    ) {
        val verdicts = cardVerdicts(results)
        println("\n=== offer diff card pool ===")
        println(
            "cards: ${cards.size} states: ${results.size} " +
                "errored states: ${results.count { it.verdict == CardPoolOfferDiff.Verdict.Error }} elapsedMs: $elapsedMs",
        )
        println(
            "covered cards (a play beyond the base cast): ${results.filter { it.extraPlays.isNotEmpty() }.map { it.card }.toSet().size}",
        )
        verdicts.values
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .forEach { (verdict, count) -> println("  $verdict = $count") }
        gapsByBucketAndApi(results).forEach { (key, count) -> println("  gap $key = $count card(s)") }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
    private const val USAGE =
        """Usage: offer-diff-pool [options]
  --cards "<a;b>"           Card names, semicolon-separated.
  --cards-file <path>       One card name per line; # starts a comment.
  --zones <hand,...>        Zones to place each card in; default hand,battlefield,graveyard,exile,command.
  --card-catalog <client|forge>  Card identities; defaults to the native client catalog.
  --out-dir <path>          Output directory; default engine/build/offer-diff-pool."""
}
