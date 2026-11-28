package leyline.tooling.simclient

import leyline.bridge.OfferDiffBucket
import leyline.bridge.OfferDiffDiagnostics

/**
 * Simclient report section for [leyline.bridge.OfferDiff]: differences between
 * published priority windows and Forge's ability enumeration, by bucket, rule,
 * card and Forge API type. Unnamed differences fail a strict run.
 */
internal object OfferDiffReport {
    /** Run-wide totals: windows summed, each finding's window count summed across rows. */
    fun totals(stats: List<GameStats>): OfferDiffDiagnostics.Snapshot {
        val findings =
            stats
                .flatMap { it.offerDiff?.findings.orEmpty() }
                .groupingBy { it.key }
                .aggregate { _, acc: OfferDiffDiagnostics.Finding?, finding, _ ->
                    acc?.copy(windows = acc.windows + finding.windows) ?: finding
                }.values
                .sortedWith(compareBy({ it.key.bucket }, { it.key.rule?.name ?: "" }, { it.key.card }, { it.key.apiType }))
        val failures =
            stats
                .flatMap {
                    it.offerDiff
                        ?.failures
                        ?.entries
                        .orEmpty()
                }.groupingBy { it.key }
                .fold(0) { total, entry -> total + entry.value }
                .toSortedMap()
        return OfferDiffDiagnostics.Snapshot(stats.sumOf { it.offerDiff?.windows ?: 0 }, findings, failures)
    }

    fun toJson(snapshot: OfferDiffDiagnostics.Snapshot): String =
        buildString {
            append('{')
            append("\"windows\":${snapshot.windows},")
            append("\"unnamed\":${snapshot.unnamed.size},")
            append("\"byBucket\":${mapToJson(byBucket(snapshot))},")
            append("\"failures\":${mapToJson(snapshot.failures)},")
            append(
                snapshot.findings.joinToString(",", "\"findings\":[", "]") { finding ->
                    val key = finding.key
                    buildString {
                        append('{')
                        append("\"bucket\":${simJsonString(key.bucket.name)},")
                        append("\"rule\":${key.rule?.let { simJsonString(it.name) } ?: "null"},")
                        append("\"card\":${simJsonString(key.card)},")
                        append("\"zone\":${simJsonString(key.zone)},")
                        append("\"apiType\":${simJsonString(key.apiType)},")
                        append("\"ability\":${simJsonString(key.ability)},")
                        append("\"description\":${simJsonString(key.description)},")
                        append("\"windows\":${finding.windows},")
                        append("\"sample\":${simJsonString(finding.sample)}")
                        append('}')
                    }
                },
            )
            append('}')
        }

    fun printSummary(snapshot: OfferDiffDiagnostics.Snapshot) {
        println("offerDiff: windows=${snapshot.windows} unnamed=${snapshot.unnamed.size} failures=${snapshot.failures.values.sum()}")
        // Counts are distinct findings; each unnamed line gives its window count.
        byBucket(snapshot).forEach { (bucket, count) -> println("  $bucket findings=$count") }
        snapshot.findings
            .filter { it.key.rule != null }
            .groupingBy { "${it.key.bucket.name}/${it.key.rule?.name}" }
            .eachCount()
            .forEach { (rule, count) -> println("  named $rule findings=$count") }
        snapshot.unnamed.forEach { finding ->
            val key = finding.key
            println(
                "  unnamed ${key.bucket.name} ${key.card} [${key.zone}] ${key.apiType} \"${key.ability}\" windows=${finding.windows} (${finding.sample})",
            )
        }
        snapshot.failures.forEach { (type, count) -> println("  failed $type = $count") }
    }

    private fun byBucket(snapshot: OfferDiffDiagnostics.Snapshot): Map<String, Int> =
        OfferDiffBucket.entries.associate { bucket -> bucket.name to snapshot.findings.count { it.key.bucket == bucket } }
}
