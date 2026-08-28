package leyline.game.generator

/**
 * Thrown when one or more card names from a generated pack/pool have no
 * grpId in the injected name-to-grpId lookup. A partial
 * pack or pool is worse than a loud failure — callers should not catch this
 * to silently continue.
 */
class UnmappedCardNamesException(
    val names: List<String>,
) : IllegalStateException("No grpId mapping for card name(s): ${names.joinToString(", ")}")
