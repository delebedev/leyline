package leyline.game.data

/**
 * Well-known Arena ability identifiers for the five basic-land mana
 * abilities. The integer is the row's `Id` in Arena's `Abilities` table
 * (also the value that appears verbatim in `Cards.AbilityIds` for any
 * basic of that type). Used by `ZoneTransferDetector` to tag mana-ability
 * activations.
 */
object BasicLandAbilities {
    /** (forge subtype name lowercase) → Arena ability id. */
    val BY_SUBTYPE: List<Pair<String, Int>> =
        listOf(
            "plains" to 1001,
            "island" to 1002,
            "swamp" to 1003,
            "mountain" to 1004,
            "forest" to 1005,
        )
}
