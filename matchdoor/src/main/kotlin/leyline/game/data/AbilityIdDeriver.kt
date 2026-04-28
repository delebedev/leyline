package leyline.game.data

/**
 * Well-known ability identifiers shared between production and test code.
 *
 * Historically this object also derived synthetic ability grpIds from Forge
 * card objects (used by the previous test-only `CardDataDeriver` path); that
 * derivation has been removed in favor of YAML fixtures under
 * `matchdoor/src/test/resources/test-cards/` which stamp Arena identity
 * directly. The constant below stays — it's the leyline-internal mapping
 * for basic land mana abilities that production wire-emit code (e.g.
 * `ZoneTransferDetector`) checks against.
 */
object AbilityIdDeriver {
    /** Well-known ability IDs for basic land mana abilities. */
    val BASIC_LAND_ABILITIES =
        listOf(
            "plains" to 1001,
            "island" to 1002,
            "swamp" to 1003,
            "mountain" to 1004,
            "forest" to 1005,
        )
}
