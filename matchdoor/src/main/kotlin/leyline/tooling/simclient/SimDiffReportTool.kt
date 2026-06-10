package leyline.tooling.simclient

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = SimDiffReportMain.run(args)
    if (exitCode != 0) exitProcess(exitCode)
}

object SimDiffReportMain {
    fun run(args: Array<String>): Int {
        val config = SimDiffReportConfig.parse(args.toList()) ?: return 0
        SimDiffReporter(config).run()
        return 0
    }
}

data class SimDiffReportConfig(
    val refDir: File,
    val candDir: File,
    val outDir: File,
) {
    companion object {
        fun parse(args: List<String>): SimDiffReportConfig? {
            var refDir = File("matchdoor/build/sim-ref")
            var candDir = File("matchdoor/build/simclient")
            var outDir = File("matchdoor/build/sim-diff-report")
            var i = 0
            while (i < args.size) {
                fun value(): String {
                    require(i + 1 < args.size) { "${args[i]} requires a value" }
                    i += 1
                    return args[i]
                }
                when (args[i]) {
                    "--ref-dir" -> refDir = File(value())
                    "--cand-dir" -> candDir = File(value())
                    "--out-dir" -> outDir = File(value())
                    "--help", "-h" -> {
                        printUsage()
                        return null
                    }
                    else -> error("unknown simDiffReport arg: ${args[i]}")
                }
                i += 1
            }
            return SimDiffReportConfig(refDir, candDir, outDir)
        }

        private fun printUsage() {
            println(
                """
                Usage: simDiffReport [options]

                  --ref-dir <path>    Directory containing *.refdecisions.json.
                  --cand-dir <path>   Directory containing simclient *.stats.json.
                  --out-dir <path>    Report output directory.
                """.trimIndent(),
            )
        }
    }
}

class SimDiffReporter(
    private val config: SimDiffReportConfig,
) {
    fun run() {
        require(config.refDir.isDirectory) { "ref dir not found: ${config.refDir}" }
        require(config.candDir.isDirectory) { "candidate dir not found: ${config.candDir}" }
        config.outDir.mkdirs()

        val refRows = readRefRows(config.refDir)
        val candRows = readCandidateRows(config.candDir)
        val joinedTags = refRows.keys.intersect(candRows.keys)
        require(joinedTags.isNotEmpty()) {
            "no matching row tags between ${config.refDir} and ${config.candDir}"
        }

        val callbackCounts = mutableMapOf<String, Int>()
        val emittedCounts = mutableMapOf<String, Int>()
        val missingRows = mutableMapOf<String, MutableList<String>>()
        val unmapped = mutableMapOf<String, Int>()
        val rowReports = mutableListOf<RowCoverage>()

        for (tag in joinedTags.sorted()) {
            val ref = refRows.getValue(tag)
            val cand = candRows.getValue(tag)
            val rowMissing = mutableListOf<String>()
            for ((callback, count) in ref.callbackCounts) {
                callbackCounts.merge(callback, count, Int::plus)
                val expected = callbackExpectedGre[callback]
                if (expected == null) {
                    unmapped.merge(callback, count, Int::plus)
                    continue
                }
                val emitted = cand.promptHistogram[expected] ?: 0
                emittedCounts.merge(expected, emitted, Int::plus)
                if (emitted == 0) {
                    rowMissing += "$callback->$expected"
                    missingRows.getOrPut(callback) { mutableListOf() } += tag
                }
            }
            rowReports += RowCoverage(tag, ref.deck, ref.seed, ref.callbackCounts, ref.callbackSamples, cand.promptHistogram, rowMissing)
        }

        val report = buildMarkdown(joinedTags.size, callbackCounts, emittedCounts, missingRows, unmapped, rowReports)
        config.outDir.resolve("coverage-report.md").writeText(report)
        config.outDir.resolve("coverage-report.json").writeText(
            buildJson(joinedTags.size, callbackCounts, emittedCounts, missingRows, unmapped, rowReports),
        )
        println("sim-diff report: ${config.outDir.resolve("coverage-report.md")}")
    }
}

private data class RefRow(
    val tag: String,
    val deck: String,
    val seed: Long,
    val callbackCounts: Map<String, Int>,
    val callbackSamples: Map<String, List<String>>,
)

private data class CandidateRow(
    val tag: String,
    val deck: String,
    val seed: Long,
    val promptHistogram: Map<String, Int>,
)

private data class RowCoverage(
    val tag: String,
    val deck: String,
    val seed: Long,
    val callbackCounts: Map<String, Int>,
    val callbackSamples: Map<String, List<String>>,
    val promptHistogram: Map<String, Int>,
    val missing: List<String>,
)

private val callbackExpectedGre =
    mapOf(
        "chooseSpellAbilityToPlay" to "ActionsAvailableReq",
        "declareAttackers" to "DeclareAttackersReq",
        "declareBlockers" to "DeclareBlockersReq",
        "assignCombatDamage" to "AssignDamageReq",
        "chooseModeForAbility" to "CastingTimeOptionsReq",
        "chooseCardsForEffect" to "SelectNReq",
        "chooseSingleEntityForEffect" to "SelectTargetsReq",
        "choosePermanentsToSacrifice" to "SelectNReq",
        "choosePermanentsToDestroy" to "SelectNReq",
        "chooseCardsToDiscardFrom" to "SelectNReq",
        "confirmTrigger" to "OptionalActionMessage",
        "confirmAction" to "OptionalActionMessage",
        "chooseOptionalCosts" to "CastingTimeOptionsReq",
    )

private fun readRefRows(dir: File): Map<String, RefRow> =
    dir
        .listFiles { file -> file.name.endsWith(".refdecisions.json") }
        .orEmpty()
        .associate { file ->
            val text = file.readText()
            val tag = file.name.removeSuffix(".refdecisions.json")
            tag to
                RefRow(
                    tag = tag,
                    deck = text.stringField("deck") ?: tag.substringBefore("-s"),
                    seed = text.longField("seed") ?: tag.substringAfterLast("-s").toLongOrNull() ?: 0L,
                    callbackCounts = text.decisionCallbackCounts(seat = 1),
                    callbackSamples = text.decisionCallbackSamples(seat = 1),
                )
        }

private fun readCandidateRows(dir: File): Map<String, CandidateRow> =
    dir
        .listFiles { file -> file.name.endsWith(".stats.json") }
        .orEmpty()
        .associate { file ->
            val text = file.readText()
            val tag = file.name.removeSuffix(".stats.json")
            tag to
                CandidateRow(
                    tag = tag,
                    deck = text.stringField("deck") ?: tag.substringBefore("-s"),
                    seed = text.longField("seed") ?: tag.substringAfterLast("-s").toLongOrNull() ?: 0L,
                    promptHistogram = text.objectIntMap("promptHistogram").mapKeys { it.key.removeEnumSuffix() },
                )
        }

private fun buildMarkdown(
    rows: Int,
    callbackCounts: Map<String, Int>,
    emittedCounts: Map<String, Int>,
    missingRows: Map<String, List<String>>,
    unmapped: Map<String, Int>,
    rowReports: List<RowCoverage>,
): String =
    buildString {
        appendLine("# simclient differential coverage report")
        appendLine()
        appendLine("Rows joined: $rows")
        appendLine()
        appendLine("## Reference callbacks")
        callbackCounts.entries.sortedByDescending { it.value }.forEach { (callback, count) ->
            appendLine("- `$callback`: $count")
        }
        appendLine()
        appendLine("## Candidate prompts")
        emittedCounts.entries.sortedByDescending { it.value }.forEach { (prompt, count) ->
            appendLine("- `$prompt`: $count")
        }
        appendLine()
        appendLine("## Missing mapped coverage")
        if (missingRows.isEmpty()) {
            appendLine("none")
        } else {
            missingRows.entries.sortedByDescending { it.value.size }.forEach { (callback, tags) ->
                appendLine("- `$callback` missing in ${tags.size} row(s): ${tags.take(8).joinToString()}")
            }
        }
        appendLine()
        appendLine("## Unmapped callbacks")
        if (unmapped.isEmpty()) {
            appendLine("none")
        } else {
            unmapped.entries.sortedByDescending { it.value }.forEach { (callback, count) ->
                appendLine("- `$callback`: $count")
            }
        }
        appendLine()
        appendLine("## Row samples")
        rowReports.take(20).forEach { row ->
            appendLine("- `${row.tag}` callbacks=${row.callbackCounts} prompts=${row.promptHistogram} missing=${row.missing}")
            if (row.callbackSamples.isNotEmpty()) {
                appendLine("  samples=${row.callbackSamples}")
            }
        }
    }

private fun buildJson(
    rows: Int,
    callbackCounts: Map<String, Int>,
    emittedCounts: Map<String, Int>,
    missingRows: Map<String, List<String>>,
    unmapped: Map<String, Int>,
    rowReports: List<RowCoverage>,
): String =
    buildString {
        append('{')
        append("\"schemaVersion\":1,")
        append("\"rowsJoined\":$rows,")
        append("\"callbackCounts\":${mapToJson(callbackCounts)},")
        append("\"candidatePromptCounts\":${mapToJson(emittedCounts)},")
        append("\"missingRows\":${stringListMapToJson(missingRows)},")
        append("\"unmappedCallbacks\":${mapToJson(unmapped)},")
        append("\"rows\":")
        append(
            rowReports.joinToString(",", "[", "]") { row ->
                "{\"tag\":${simJsonString(row.tag)},\"deck\":${simJsonString(row.deck)},\"seed\":${row.seed}," +
                    "\"callbacks\":${mapToJson(row.callbackCounts)},\"prompts\":${mapToJson(row.promptHistogram)}," +
                    "\"samples\":${stringListMapToJson(row.callbackSamples)}," +
                    "\"missing\":${row.missing.joinToString(",", "[", "]") { simJsonString(it) }}}"
            },
        )
        append('}')
    }

private fun stringListMapToJson(values: Map<String, List<String>>): String =
    values.entries.joinToString(",", "{", "}") { (key, list) ->
        "${simJsonString(key)}:${list.joinToString(",", "[", "]") { simJsonString(it) }}"
    }

private fun String.stringField(name: String): String? =
    Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)

private fun String.longField(name: String): Long? =
    Regex("\"${Regex.escape(name)}\"\\s*:\\s*(-?\\d+)")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()

private fun String.objectIntMap(name: String): Map<String, Int> {
    val body = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\\{([^}]*)}").find(this)?.groupValues?.get(1) ?: return emptyMap()
    return Regex("\"([^\"]+)\"\\s*:\\s*(-?\\d+)")
        .findAll(body)
        .associate { it.groupValues[1] to it.groupValues[2].toInt() }
}

private fun String.decisionCallbackCounts(seat: Int): Map<String, Int> {
    val out = mutableMapOf<String, Int>()
    for (match in decisionObjects()) {
        val decisionSeat = match.value.intField("seat") ?: continue
        if (decisionSeat != seat) continue
        val callback = match.value.stringField("callback") ?: continue
        if (callback in executionOnlyCallbacks) continue
        out.merge(callback, 1, Int::plus)
    }
    return out.ifEmpty { objectIntMap("callbackCounts").filterKeys { it !in executionOnlyCallbacks } }
}

private fun String.decisionCallbackSamples(seat: Int): Map<String, List<String>> {
    val samples = linkedMapOf<String, MutableList<String>>()
    for (match in decisionObjects()) {
        val body = match.value
        val decisionSeat = body.intField("seat") ?: continue
        if (decisionSeat != seat) continue
        val callback = body.stringField("callback") ?: continue
        if (callback in executionOnlyCallbacks) continue
        val sample =
            listOfNotNull(
                body.stringField("source")?.let { "source=$it" },
                body.stringField("api")?.let { "api=$it" },
                body.stringField("prompt")?.let { "prompt=$it" },
            ).joinToString(" ")
        if (sample.isBlank()) continue
        val values = samples.getOrPut(callback) { mutableListOf() }
        if (values.size < 3 && sample !in values) values += sample
    }
    return samples
}

private fun String.decisionObjects(): Sequence<MatchResult> = Regex("\\{[^{}]*\"callback\"[^{}]*}").findAll(this)

private fun String.intField(name: String): Int? = longField(name)?.toInt()

private val executionOnlyCallbacks = setOf("playChosenSpellAbility")

private fun String.removeEnumSuffix(): String =
    replace(Regex("_[a-f0-9]{4}$"), "")
        .replace("SelectNreq", "SelectNReq")
