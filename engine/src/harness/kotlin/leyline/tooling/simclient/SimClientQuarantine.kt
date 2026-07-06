package leyline.tooling.simclient

import leyline.game.data.CardRepository

data class QuarantineSpec(
    val rawEntries: List<String>,
    val names: Set<String>,
    val grpIds: Set<Int>,
) {
    val isEmpty: Boolean = names.isEmpty() && grpIds.isEmpty()
}

data class DeckOverlayReport(
    val policy: SimClientExcludePolicy,
    val removed: List<DeckOverlayRemoval>,
    val replacement: String? = null,
) {
    val removedCount: Int = removed.sumOf { it.count }
    val removedCards: Int = removed.size
}

data class DeckOverlayRemoval(
    val name: String,
    val count: Int,
    val grpId: Int?,
    val matchedBy: String,
)

data class DeckOverlayResult(
    val deckList: String,
    val report: DeckOverlayReport?,
    val skipped: Boolean = false,
)

fun quarantineSpec(config: SimClientConfig): QuarantineSpec {
    val entries = mutableListOf<String>()
    entries += config.excludeCards.splitEntries()
    val file = config.excludeCardsFile
    if (file != null && file.exists()) entries += file.readLines().flatMap { it.lineEntries() }
    val names = mutableSetOf<String>()
    val grpIds = mutableSetOf<Int>()
    for (entry in entries) {
        val value = entry.trim()
        if (value.isEmpty()) continue
        value.toIntOrNull()?.let { grpIds += it } ?: run { names += normalizeCardName(value) }
    }
    return QuarantineSpec(entries, names, grpIds)
}

fun overlayDeck(
    deckList: String,
    spec: QuarantineSpec,
    policy: SimClientExcludePolicy,
    cardRepository: CardRepository?,
): DeckOverlayResult {
    if (spec.isEmpty) return DeckOverlayResult(deckList, report = null)
    val entries = parseDeckEntries(deckList)
    val kept = mutableListOf<DeckEntry>()
    val removed = mutableListOf<DeckOverlayRemoval>()
    for (entry in entries) {
        val cardName = entry.cardName
        val grpId = cardRepository?.findGrpIdByNameAnyFace(cardName)
        val nameMatches = normalizeCardName(cardName) in spec.names
        val grpMatches = grpId != null && grpId in spec.grpIds
        if (nameMatches || grpMatches) {
            removed += DeckOverlayRemoval(cardName, entry.count, grpId, if (grpMatches) "grpId" else "name")
        } else {
            kept += entry
        }
    }
    if (removed.isEmpty()) return DeckOverlayResult(deckList, report = null)
    val reportWithoutReplacement = DeckOverlayReport(policy, removed)
    if (policy == SimClientExcludePolicy.SkipDeck) return DeckOverlayResult(deckList, reportWithoutReplacement, skipped = true)
    val replacement = mostCommonBasic(kept) ?: "Forest"
    val replacementCount = removed.sumOf { it.count }
    val out = kept.toMutableList()
    out += DeckEntry(replacementCount, replacement)
    return DeckOverlayResult(
        deckList = out.joinToString("\n") { "${it.count} ${it.name}" },
        report = DeckOverlayReport(policy, removed, replacement),
    )
}

private data class DeckEntry(
    val count: Int,
    val name: String,
) {
    val cardName: String = stripArenaDeckSuffix(name)
}

private fun parseDeckEntries(deckList: String): List<DeckEntry> =
    buildList {
        for (rawLine in deckList.lineSequence()) {
            val line = rawLine.substringBefore("#").trim()
            if (line.isEmpty()) continue
            if (line.equals("Deck", ignoreCase = true)) continue
            if (line.equals("Sideboard", ignoreCase = true)) break
            val count = line.substringBefore(' ').toIntOrNull() ?: continue
            add(DeckEntry(count, line.substringAfter(' ').trim()))
        }
    }

private fun mostCommonBasic(entries: List<DeckEntry>): String? =
    entries
        .filter { it.name in basicLands }
        .groupingBy { it.name }
        .fold(0) { acc, entry -> acc + entry.count }
        .maxByOrNull { it.value }
        ?.key

private fun String.splitEntries(): List<String> = split(",").flatMap { it.lineEntries() }

private fun String.lineEntries(): List<String> =
    lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .toList()

fun normalizeCardName(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

private fun stripArenaDeckSuffix(value: String): String = value.replace(ARENA_SET_SUFFIX, "").trim()

private val basicLands = setOf("Plains", "Island", "Swamp", "Mountain", "Forest")
private val ARENA_SET_SUFFIX = Regex("\\s+\\([A-Z0-9]+\\)\\s+\\S+$")
