package forge.nexus.conformance

import forge.nexus.game.CardDb
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Registers the test deck cards with synthetic Arena-like metadata.
 *
 * Provides just enough data for grpId lookups, buildObjectInfo (cardTypes,
 * subtypes, uniqueAbilities, manaCost) to work without the real Arena SQLite DB.
 *
 * Cards match [forge.nexus.game.GameBridge.DEFAULT_DECK]: mono-green stompy.
 * Proto enum values from messages.proto.
 */
object TestCardRegistry {

    // Synthetic grpIds — stable across runs, don't collide with real Arena IDs
    const val FOREST_GRPID = 70000
    const val LLANOWAR_ELVES_GRPID = 70001
    const val ELVISH_MYSTIC_GRPID = 70002
    const val GIANT_GROWTH_GRPID = 70003

    fun ensureRegistered() {
        if (CardDb.registeredCount > 0) return
        // Forest: Basic Land — Forest, mana ability {T}: Add {G}
        CardDb.registerData(
            CardDb.CardData(
                grpId = FOREST_GRPID,
                titleId = 1,
                power = "",
                toughness = "",
                colors = emptyList(),
                types = listOf(5), // CardType.Land_a80b = 5
                subtypes = listOf(29), // SubType.Forest = 29
                supertypes = listOf(1), // SuperType.Basic = 1
                abilityIds = listOf(1005 to 0), // implicit basic land mana ability
                manaCost = emptyList(),
            ),
            "Forest",
        )
        // Llanowar Elves: Creature — Elf Druid, 1/1, {G}
        CardDb.registerData(
            CardDb.CardData(
                grpId = LLANOWAR_ELVES_GRPID,
                titleId = 2,
                power = "1",
                toughness = "1",
                colors = listOf(5), // CardColor.Green_a3b0 = 5
                types = listOf(2), // CardType.Creature = 2
                subtypes = listOf(27, 23), // SubType.Elf = 27, SubType.Druid = 23
                supertypes = emptyList(),
                abilityIds = listOf(2001 to 0), // {T}: Add {G}
                manaCost = listOf(ManaColor.Green_afc9 to 1),
            ),
            "Llanowar Elves",
        )
        // Elvish Mystic: Creature — Elf Druid, 1/1, {G}
        CardDb.registerData(
            CardDb.CardData(
                grpId = ELVISH_MYSTIC_GRPID,
                titleId = 3,
                power = "1",
                toughness = "1",
                colors = listOf(5),
                types = listOf(2),
                subtypes = listOf(27, 23),
                supertypes = emptyList(),
                abilityIds = listOf(2002 to 0),
                manaCost = listOf(ManaColor.Green_afc9 to 1),
            ),
            "Elvish Mystic",
        )
        // Giant Growth: Instant, {G}
        CardDb.registerData(
            CardDb.CardData(
                grpId = GIANT_GROWTH_GRPID,
                titleId = 4,
                power = "",
                toughness = "",
                colors = listOf(5),
                types = listOf(4), // CardType.Instant = 4
                subtypes = emptyList(),
                supertypes = emptyList(),
                abilityIds = emptyList(),
                manaCost = listOf(ManaColor.Green_afc9 to 1),
            ),
            "Giant Growth",
        )
    }
}
