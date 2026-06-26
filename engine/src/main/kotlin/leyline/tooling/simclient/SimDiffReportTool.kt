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

        val model = buildReportModel(joinedTags, refRows, candRows)
        config.outDir.resolve("coverage-report.md").writeText(buildMarkdown(model))
        config.outDir.resolve("coverage-report.json").writeText(buildJson(model))
        println("sim-diff report: ${config.outDir.resolve("coverage-report.md")}")
    }
}

private fun buildReportModel(
    joinedTags: Set<String>,
    refRows: Map<String, RefRow>,
    candRows: Map<String, CandidateRow>,
): DiffReport {
    val callbackCounts = mutableMapOf<String, Int>()
    val candidatePromptCounts = mutableMapOf<String, Int>()
    val callbackMatchedPromptCounts = mutableMapOf<String, Int>()
    val missingRows = mutableMapOf<String, MutableList<String>>()
    val unmapped = mutableMapOf<String, Int>()
    val rowReports = mutableListOf<RowCoverage>()
    val refRowIssues = mutableListOf<RefRowIssue>()
    val advisorConsulted = mutableMapOf<String, Int>()
    val advisorChose = mutableMapOf<String, Int>()
    val advisorDisagreements = mutableMapOf<String, Int>()
    val advisorMatches = mutableMapOf<String, Int>()
    val advisorSamples = mutableMapOf<String, String>()

    for (tag in joinedTags.sorted()) {
        val ref = refRows.getValue(tag)
        val cand = candRows.getValue(tag)
        cand.promptHistogram.forEach { (prompt, count) -> candidatePromptCounts.merge(prompt, count, Int::plus) }
        cand.aiConsultedByPrompt.forEach { (prompt, count) -> advisorConsulted.merge(prompt, count, Int::plus) }
        cand.aiChoseByPrompt.forEach { (prompt, count) -> advisorChose.merge(prompt, count, Int::plus) }
        cand.advisorDisagreementsByPrompt.forEach { (prompt, count) -> advisorDisagreements.merge(prompt, count, Int::plus) }
        cand.advisorMatchesByPrompt.forEach { (prompt, count) -> advisorMatches.merge(prompt, count, Int::plus) }
        cand.advisorDisagreementSamples.forEach { (prompt, sample) -> advisorSamples.putIfAbsent(prompt, sample) }
        if (ref.health.isIssue) refRowIssues += RefRowIssue(tag, ref.completionReason, ref.exceptionMessage, ref.errorsByType)

        val rowMissing = mutableListOf<String>()
        for ((callback, count) in ref.callbackCounts) {
            callbackCounts.merge(callback, count, Int::plus)
            val expected = expectedGreFor(callback, ref.callbackSamples[callback].orEmpty())
            if (expected == null) {
                unmapped.merge(callback, count, Int::plus)
                continue
            }
            val emitted = expected.countIn(cand.promptHistogram)
            callbackMatchedPromptCounts.merge(callback, emitted, Int::plus)
            if (emitted == 0) {
                rowMissing += "$callback->${expected.label}"
                missingRows.getOrPut(callback) { mutableListOf() } += tag
            }
        }
        rowReports += ref.toRowCoverage(tag, cand, rowMissing)
    }

    val aggregateZeroMissing =
        callbackCounts.filterKeys { callback ->
            callback in callbackMatchedPromptCounts && (callbackMatchedPromptCounts[callback] ?: 0) == 0
        }
    return DiffReport(
        rows = joinedTags.size,
        rowHealthCounts = rowReports.rowHealthCounts(),
        callbackCounts = callbackCounts,
        candidatePromptCounts = candidatePromptCounts,
        aggregateZeroMissing = aggregateZeroMissing,
        coverageGaps = buildCoverageGaps(aggregateZeroMissing, callbackMatchedPromptCounts, rowReports),
        missingRows = missingRows,
        unmapped = unmapped,
        callbackDispositions = buildCallbackDispositions(callbackCounts, callbackMatchedPromptCounts.keys),
        rowReports = rowReports,
        refRowIssues = refRowIssues,
        advisorGaps = buildAdvisorGaps(advisorConsulted, advisorChose, advisorDisagreements, advisorMatches, advisorSamples),
    )
}

private fun RefRow.toRowCoverage(
    tag: String,
    cand: CandidateRow,
    rowMissing: List<String>,
): RowCoverage =
    RowCoverage(
        tag = tag,
        deck = deck,
        seed = seed,
        callbackCounts = callbackCounts,
        callbackSamples = callbackSamples,
        promptHistogram = cand.promptHistogram,
        missing = rowMissing,
        completionReason = completionReason,
        health = health,
        errorsByType = errorsByType,
    )

private data class DiffReport(
    val rows: Int,
    val rowHealthCounts: Map<String, Int>,
    val callbackCounts: Map<String, Int>,
    val candidatePromptCounts: Map<String, Int>,
    val aggregateZeroMissing: Map<String, Int>,
    val coverageGaps: List<CoverageGap>,
    val missingRows: Map<String, List<String>>,
    val unmapped: Map<String, Int>,
    val callbackDispositions: List<CallbackDispositionReport>,
    val rowReports: List<RowCoverage>,
    val refRowIssues: List<RefRowIssue>,
    val advisorGaps: List<AdvisorGap>,
)

private data class RefRow(
    val tag: String,
    val deck: String,
    val seed: Long,
    val callbackCounts: Map<String, Int>,
    val callbackSamples: Map<String, List<String>>,
    val completionReason: String,
    val exceptionMessage: String?,
    val errorsByType: Map<String, Int>,
    val health: SimDiffRowHealth,
)

private data class CandidateRow(
    val tag: String,
    val deck: String,
    val seed: Long,
    val promptHistogram: Map<String, Int>,
    val aiConsultedByPrompt: Map<String, Int>,
    val aiChoseByPrompt: Map<String, Int>,
    val advisorDisagreementsByPrompt: Map<String, Int>,
    val advisorMatchesByPrompt: Map<String, Int>,
    val advisorDisagreementSamples: Map<String, String>,
)

private data class RowCoverage(
    val tag: String,
    val deck: String,
    val seed: Long,
    val callbackCounts: Map<String, Int>,
    val callbackSamples: Map<String, List<String>>,
    val promptHistogram: Map<String, Int>,
    val missing: List<String>,
    val completionReason: String,
    val health: SimDiffRowHealth,
    val errorsByType: Map<String, Int>,
)

private data class RefRowIssue(
    val tag: String,
    val completionReason: String,
    val exceptionMessage: String?,
    val errorsByType: Map<String, Int>,
)

private data class AdvisorGap(
    val prompt: String,
    val consulted: Int,
    val chose: Int,
    val disagreements: Int,
    val matches: Int,
    val sample: String?,
    val category: String,
    val sampleSummary: String?,
) {
    val disagreementRate: Double = if (chose == 0) 0.0 else disagreements.toDouble() / chose.toDouble()
}

private data class CoverageGap(
    val callback: String,
    val referenceCallbacks: Int,
    val expectedLabel: String,
    val candidateEmitted: Int,
    val healthyRows: Int,
    val issueRows: Int,
    val sampleRows: List<String>,
    val issueSummaries: List<String>,
)

private data class CallbackDispositionReport(
    val callback: String,
    val count: Int,
    val status: String,
    val note: String,
)

private data class ExpectedGre(
    val promptTypes: Set<String>,
) {
    val label: String = promptTypes.joinToString("|")

    fun countIn(promptHistogram: Map<String, Int>): Int = promptTypes.sumOf { promptHistogram[it] ?: 0 }
}

private fun expectedGre(vararg promptTypes: String): ExpectedGre = ExpectedGre(promptTypes.toSet())

private fun expectedGreFor(
    callback: String,
    samples: List<String>,
): ExpectedGre? =
    when {
        callback == "chooseColor" && samples.any { it.isManaColorChoice() } ->
            expectedGre("ActionsAvailableReq")
        callback == "chooseColor" && samples.isNotEmpty() -> expectedGre("SelectNReq")
        else -> callbackExpectedGre[callback]
    }

private fun String.isManaColorChoice(): Boolean = contains("api=Mana") || contains("prompt=Select Mana to Produce")

private val callbackExpectedGre =
    mapOf(
        "chooseSpellAbilityToPlay" to expectedGre("ActionsAvailableReq"),
        "declareAttackers" to expectedGre("DeclareAttackersReq"),
        "declareBlockers" to expectedGre("DeclareBlockersReq"),
        "assignCombatDamage" to expectedGre("AssignDamageReq"),
        "chooseModeForAbility" to expectedGre("CastingTimeOptionsReq"),
        "chooseCardsForEffect" to expectedGre("SelectNReq"),
        "chooseEntitiesForEffect" to expectedGre("SelectNReq", "GroupReq"),
        "chooseSingleEntityForEffect" to expectedGre("SelectTargetsReq"),
        "choosePermanentsToSacrifice" to expectedGre("SelectNReq"),
        "choosePermanentsToDestroy" to expectedGre("SelectNReq"),
        "chooseCardsToDiscardFrom" to expectedGre("SelectNReq"),
        "confirmTrigger" to expectedGre("OptionalActionMessage"),
        "confirmAction" to expectedGre("OptionalActionMessage"),
        "chooseOptionalCosts" to expectedGre("CastingTimeOptionsReq"),
    )

private fun readRefRows(dir: File): Map<String, RefRow> =
    dir
        .listFiles { file -> file.name.endsWith(".refdecisions.json") }
        .orEmpty()
        .associate { file ->
            val text = file.readText()
            val tag = file.name.removeSuffix(".refdecisions.json")
            val completionReason =
                text.stringField("completionReason")
                    ?: if (text.booleanField("gameOver") == true) "natural" else "incomplete"
            val exceptionMessage = text.stringField("exceptionMessage")
            val errorsByType = text.objectIntMap("errorsByType")
            tag to
                RefRow(
                    tag = tag,
                    deck = text.stringField("deck") ?: tag.substringBefore("-s"),
                    seed = text.longField("seed") ?: tag.substringAfterLast("-s").toLongOrNull() ?: 0L,
                    callbackCounts = text.decisionCallbackCounts(seat = 1),
                    callbackSamples = text.decisionCallbackSamples(seat = 1),
                    completionReason = completionReason,
                    exceptionMessage = exceptionMessage,
                    errorsByType = errorsByType,
                    health = SimDiffRowHealth.from(completionReason, exceptionMessage, errorsByType),
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
                    aiConsultedByPrompt = text.objectIntMap("aiConsultedByPrompt"),
                    aiChoseByPrompt = text.objectIntMap("aiChoseByPrompt"),
                    advisorDisagreementsByPrompt = text.objectIntMap("advisorDisagreementsByPrompt"),
                    advisorMatchesByPrompt = text.objectIntMap("advisorMatchesByPrompt"),
                    advisorDisagreementSamples = text.objectStringMap("advisorDisagreementSamples"),
                )
        }

private fun buildCoverageGaps(
    aggregateZeroMissing: Map<String, Int>,
    matchedPromptCounts: Map<String, Int>,
    rowReports: List<RowCoverage>,
): List<CoverageGap> =
    aggregateZeroMissing.entries
        .sortedByDescending { it.value }
        .map { (callback, referenceCallbacks) ->
            val rowsWithCallback = rowReports.filter { (it.callbackCounts[callback] ?: 0) > 0 }
            CoverageGap(
                callback = callback,
                referenceCallbacks = referenceCallbacks,
                expectedLabel = rowsWithCallback.expectedLabelFor(callback),
                candidateEmitted = matchedPromptCounts[callback] ?: 0,
                healthyRows = rowsWithCallback.count { !it.health.isIssue },
                issueRows = rowsWithCallback.count { it.health.isIssue },
                sampleRows = rowsWithCallback.map { it.tag }.take(5),
                issueSummaries = rowsWithCallback.filter { it.health.isIssue }.map { it.issueSummary() }.take(5),
            )
        }

private fun List<RowCoverage>.expectedLabelFor(callback: String): String =
    mapNotNull { row -> expectedGreFor(callback, row.callbackSamples[callback].orEmpty())?.label }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|")
        ?: "unknown"

private fun RowCoverage.issueSummary(): String {
    val errors = errorsByType.takeIf { it.isNotEmpty() }?.let { ", errors=$it" }.orEmpty()
    return "$tag:$completionReason$errors"
}

private fun buildCallbackDispositions(
    callbackCounts: Map<String, Int>,
    mappedCallbacks: Set<String>,
): List<CallbackDispositionReport> =
    callbackCounts.entries
        .sortedBy { it.key }
        .mapNotNull { (callback, count) ->
            simDiffCallbackDisposition(callback, mapped = callback in mappedCallbacks)?.let { disposition ->
                CallbackDispositionReport(
                    callback = callback,
                    count = count,
                    status = disposition.status,
                    note = disposition.note,
                )
            }
        }

private fun List<RowCoverage>.rowHealthCounts(): Map<String, Int> =
    groupingBy { it.health.label }
        .eachCount()
        .toSortedMap(compareBy { healthSortOrder(it) })

private fun healthSortOrder(label: String): Int =
    when (label) {
        SimDiffRowHealth.Natural.label -> 0
        SimDiffRowHealth.MaxTurns.label -> 1
        SimDiffRowHealth.Issue.label -> 2
        else -> 3
    }

private fun buildAdvisorGaps(
    consulted: Map<String, Int>,
    chose: Map<String, Int>,
    disagreements: Map<String, Int>,
    matches: Map<String, Int>,
    samples: Map<String, String>,
): List<AdvisorGap> =
    consulted.keys
        .union(chose.keys)
        .union(disagreements.keys)
        .map { prompt ->
            AdvisorGap(
                prompt = prompt,
                consulted = consulted[prompt] ?: 0,
                chose = chose[prompt] ?: 0,
                disagreements = disagreements[prompt] ?: 0,
                matches = matches[prompt] ?: 0,
                sample = samples[prompt],
                category = advisorGapCategory(prompt, samples[prompt]),
                sampleSummary = advisorSampleSummary(samples[prompt]),
            )
        }.sortedWith(
            compareByDescending<AdvisorGap> { it.disagreements }
                .thenByDescending { it.disagreementRate }
                .thenByDescending { it.consulted },
        )

private fun buildMarkdown(report: DiffReport): String =
    buildString {
        appendLine("# simclient differential coverage report")
        appendLine()
        appendLine("Rows joined: ${report.rows}")
        appendLine("Row health: ${report.rowHealthCounts.ifEmpty { mapOf(SimDiffRowHealth.Natural.label to report.rows) }}")
        appendReferenceRowIssues(report.refRowIssues)
        appendCountSection("Reference callbacks", report.callbackCounts)
        appendCountSection("Candidate prompts", report.candidatePromptCounts)
        appendCoverageGaps(report.coverageGaps)
        appendMissingRows(report.missingRows)
        appendAdvisorGaps(report.advisorGaps)
        appendUnmappedCallbacks(report.unmapped, report.callbackDispositions)
        appendCallbackDispositionNotes(report.callbackDispositions)
        appendRowSamples(report.rowReports)
    }

private fun StringBuilder.appendReferenceRowIssues(refRowIssues: List<RefRowIssue>) {
    appendLine()
    appendLine("## Reference row issues")
    if (refRowIssues.isEmpty()) {
        appendLine("none")
        return
    }
    refRowIssues.forEach { issue ->
        val details =
            listOfNotNull(
                issue.exceptionMessage,
                issue.errorsByType.takeIf { it.isNotEmpty() }?.let { "errors=$it" },
            ).joinToString("; ")
        appendLine("- `${issue.tag}`: ${issue.completionReason}${if (details.isBlank()) "" else " ($details)"}")
    }
}

private fun StringBuilder.appendCountSection(
    title: String,
    counts: Map<String, Int>,
) {
    appendLine()
    appendLine("## $title")
    counts.entries.sortedByDescending { it.value }.forEach { (name, count) ->
        appendLine("- `$name`: $count")
    }
}

private fun StringBuilder.appendCoverageGaps(coverageGaps: List<CoverageGap>) {
    appendLine()
    appendLine("## Aggregate-zero mapped coverage gaps")
    if (coverageGaps.isEmpty()) {
        appendLine("none")
        return
    }
    coverageGaps.forEach { gap ->
        appendLine(
            "- `${gap.callback}`: ${gap.referenceCallbacks} reference callbacks, " +
                "expected `${gap.expectedLabel}`, candidate emitted ${gap.candidateEmitted}, " +
                "healthyRows=${gap.healthyRows}, issueRows=${gap.issueRows}, " +
                "sampleRows=${gap.sampleRows.joinToString()}",
        )
        if (gap.issueSummaries.isNotEmpty()) {
            appendLine("  issueRows=${gap.issueSummaries.joinToString()}")
        }
    }
}

private fun StringBuilder.appendMissingRows(missingRows: Map<String, List<String>>) {
    appendLine()
    appendLine("## Per-row mapped shortfalls")
    if (missingRows.isEmpty()) {
        appendLine("none")
        return
    }
    missingRows.entries.sortedByDescending { it.value.size }.forEach { (callback, tags) ->
        appendLine("- `$callback` missing in ${tags.size} row(s): ${tags.take(8).joinToString()}")
    }
}

private fun StringBuilder.appendAdvisorGaps(advisorGaps: List<AdvisorGap>) {
    appendLine()
    appendLine("## Advisor-gap ranking")
    val rankedAdvisorGaps = advisorGaps.filter { it.disagreements > 0 }
    if (rankedAdvisorGaps.isEmpty()) {
        appendLine("none")
        return
    }
    rankedAdvisorGaps.forEach { gap ->
        appendLine(
            "- `${gap.prompt}`: category=${gap.category} disagreements=${gap.disagreements}/${gap.chose} " +
                "consulted=${gap.consulted} rate=${"%.2f".format(gap.disagreementRate)}",
        )
        gap.sampleSummary?.let { appendLine("  sample=$it") }
    }
}

private fun StringBuilder.appendUnmappedCallbacks(
    unmapped: Map<String, Int>,
    callbackDispositions: List<CallbackDispositionReport>,
) {
    appendLine()
    appendLine("## Unmapped callbacks")
    if (unmapped.isEmpty()) {
        appendLine("none")
        return
    }
    unmapped.entries.sortedByDescending { it.value }.forEach { (callback, count) ->
        val disposition = callbackDispositions.firstOrNull { it.callback == callback }
        val suffix = disposition?.let { " (${it.status}: ${it.note})" }.orEmpty()
        appendLine("- `$callback`: $count$suffix")
    }
}

private fun StringBuilder.appendCallbackDispositionNotes(callbackDispositions: List<CallbackDispositionReport>) {
    appendLine()
    appendLine("## Callback disposition notes")
    if (callbackDispositions.isEmpty()) {
        appendLine("none")
        return
    }
    callbackDispositions.forEach { disposition ->
        appendLine("- `${disposition.callback}`: ${disposition.status}; count=${disposition.count}; ${disposition.note}")
    }
}

private fun StringBuilder.appendRowSamples(rowReports: List<RowCoverage>) {
    appendLine()
    appendLine("## Row samples")
    rowReports.take(20).forEach { row ->
        appendLine(
            "- `${row.tag}` health=${row.health.label} completion=${row.completionReason} " +
                "callbacks=${row.callbackCounts} prompts=${row.promptHistogram} missing=${row.missing}",
        )
        if (row.callbackSamples.isNotEmpty()) {
            appendLine("  samples=${row.callbackSamples}")
        }
    }
}

private fun buildJson(report: DiffReport): String =
    buildString {
        val rows = report.rows
        val rowHealthCounts = report.rowHealthCounts
        val callbackCounts = report.callbackCounts
        val candidatePromptCounts = report.candidatePromptCounts
        val aggregateZeroMissing = report.aggregateZeroMissing
        val coverageGaps = report.coverageGaps
        val missingRows = report.missingRows
        val unmapped = report.unmapped
        val callbackDispositions = report.callbackDispositions
        val rowReports = report.rowReports
        val refRowIssues = report.refRowIssues
        val advisorGaps = report.advisorGaps
        append('{')
        append("\"schemaVersion\":4,")
        append("\"rowsJoined\":$rows,")
        append("\"rowHealth\":${mapToJson(rowHealthCounts)},")
        append("\"callbackCounts\":${mapToJson(callbackCounts)},")
        append("\"candidatePromptCounts\":${mapToJson(candidatePromptCounts)},")
        append("\"aggregateZeroMissing\":${mapToJson(aggregateZeroMissing)},")
        append("\"aggregateZeroGaps\":")
        append(
            coverageGaps.joinToString(",", "[", "]") { gap ->
                "{\"callback\":${simJsonString(gap.callback)},\"referenceCallbacks\":${gap.referenceCallbacks}," +
                    "\"expected\":${simJsonString(gap.expectedLabel)},\"candidateEmitted\":${gap.candidateEmitted}," +
                    "\"healthyRows\":${gap.healthyRows},\"issueRows\":${gap.issueRows}," +
                    "\"sampleRows\":${gap.sampleRows.joinToString(",", "[", "]") { simJsonString(it) }}," +
                    "\"issueSummaries\":${gap.issueSummaries.joinToString(",", "[", "]") { simJsonString(it) }}}"
            },
        )
        append(',')
        append("\"missingRows\":${stringListMapToJson(missingRows)},")
        append("\"unmappedCallbacks\":${mapToJson(unmapped)},")
        append("\"callbackDispositions\":")
        append(
            callbackDispositions.joinToString(",", "[", "]") { disposition ->
                "{\"callback\":${simJsonString(disposition.callback)},\"count\":${disposition.count}," +
                    "\"status\":${simJsonString(disposition.status)},\"note\":${simJsonString(disposition.note)}}"
            },
        )
        append(',')
        append("\"refRowIssues\":")
        append(
            refRowIssues.joinToString(",", "[", "]") { issue ->
                "{\"tag\":${simJsonString(issue.tag)},\"completionReason\":${simJsonString(issue.completionReason)}," +
                    "\"exceptionMessage\":${issue.exceptionMessage?.let(::simJsonString) ?: "null"}," +
                    "\"errorsByType\":${mapToJson(issue.errorsByType)}}"
            },
        )
        append(',')
        append("\"advisorGaps\":")
        append(
            advisorGaps.joinToString(",", "[", "]") { gap ->
                "{\"prompt\":${simJsonString(gap.prompt)},\"consulted\":${gap.consulted},\"chose\":${gap.chose}," +
                    "\"disagreements\":${gap.disagreements},\"matches\":${gap.matches}," +
                    "\"disagreementRate\":${"%.4f".format(gap.disagreementRate)}," +
                    "\"category\":${simJsonString(gap.category)}," +
                    "\"sampleSummary\":${gap.sampleSummary?.let(::simJsonString) ?: "null"}," +
                    "\"sample\":${gap.sample?.let(::simJsonString) ?: "null"}}"
            },
        )
        append(',')
        append("\"rows\":")
        append(
            rowReports.joinToString(",", "[", "]") { row ->
                "{\"tag\":${simJsonString(row.tag)},\"deck\":${simJsonString(row.deck)},\"seed\":${row.seed}," +
                    "\"health\":${simJsonString(row.health.label)},\"completionReason\":${simJsonString(row.completionReason)}," +
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

private fun String.booleanField(name: String): Boolean? =
    Regex("\"${Regex.escape(name)}\"\\s*:\\s*(true|false)")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.toBooleanStrictOrNull()

private fun String.objectIntMap(name: String): Map<String, Int> {
    val body = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\\{([^}]*)}").find(this)?.groupValues?.get(1) ?: return emptyMap()
    return Regex("\"([^\"]+)\"\\s*:\\s*(-?\\d+)")
        .findAll(body)
        .associate { it.groupValues[1] to it.groupValues[2].toInt() }
}

private fun String.objectStringMap(name: String): Map<String, String> {
    val body = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\\{([^}]*)}").find(this)?.groupValues?.get(1) ?: return emptyMap()
    return Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        .findAll(body)
        .associate { it.groupValues[1] to it.groupValues[2] }
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
