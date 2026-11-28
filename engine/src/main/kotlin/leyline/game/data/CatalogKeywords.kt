package leyline.game.data

/**
 * The keyword a catalog ability row stands for, read from its own id, its base id, or its localization.
 *
 * Names are normalized with [normalizedKeyword] so they compare against Forge's keyword names. An empty set
 * means the catalog does not say, which is not a disagreement.
 */
internal fun CardRepository.catalogKeywords(abilityGrpId: Int): Set<String> {
    if (abilityGrpId <= 0) return emptySet()
    val names = mutableSetOf<String>()
    EvergreenKeywords.fromAbilityId(abilityGrpId)?.let(names::add)
    findAbilityInfo(abilityGrpId)?.baseId?.takeIf { it != 0 }?.let { base ->
        EvergreenKeywords.fromAbilityId(base)?.let(names::add)
        names += KEYWORD_BASE_NAMES[base].orEmpty()
    }
    findAbilityLocalization(abilityGrpId)?.keyword?.let(names::add)
    return names.mapTo(mutableSetOf(), ::normalizedKeyword)
}

/** A keyword name without case, spaces or punctuation, so `Jump-start`, `JUMP_START` and `jumpstart` agree. */
internal fun normalizedKeyword(name: String): String = name.lowercase().filter(Char::isLetter)

/** Keyword base ids that per-printing rows chain to, with the keyword each one names. */
private val KEYWORD_BASE_NAMES: Map<Int, Set<String>> =
    (
        KeywordAbilityIds::class.java.fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) to it.name } + (PROTECTION_BASE_ID to "Protection")
    ).groupBy({ it.first }, { it.second })
        .mapValues { (_, names) -> names.toSet() }

private const val PROTECTION_BASE_ID = 21
