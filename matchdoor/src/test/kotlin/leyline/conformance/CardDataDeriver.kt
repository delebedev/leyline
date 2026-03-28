package leyline.conformance

import forge.card.CardType.CoreType
import forge.card.CardType.Supertype
import forge.card.mana.ManaCostShard
import forge.game.card.Card
import leyline.game.CardData
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Derives [CardData] from Forge's in-memory [forge.card.CardRules].
 *
 * Eliminates the need for the client SQLite DB in tests — all card metadata
 * is derived from Forge's own card database (already loaded at test startup).
 *
 * Synthetic grpIds start at 200000 (well above real Arena grpIds which reach ~100000+).
 */
object CardDataDeriver {
    @Suppress("UnusedPrivateProperty")
    private val log = LoggerFactory.getLogger(CardDataDeriver::class.java)

    private val nextGrpId = AtomicInteger(200000)
    private val nextTitleId = AtomicInteger(10000)
    private val nextAbilityGrpId = AtomicInteger(10000)

    /** name → grpId cache: same card name always gets same grpId within a JVM. */
    private val nameToGrpId = mutableMapOf<String, Int>()

    /** Derive CardData from a live Forge [Card] object. */
    fun fromForgeCard(card: Card): CardData {
        val name = card.name
        val grpId = nameToGrpId.getOrPut(name) { nextGrpId.getAndIncrement() }
        val titleId = nextTitleId.getAndIncrement()

        val type = card.type
        val rules = card.rules

        // Card types → proto int values
        val types = type.coreTypes.mapNotNull { CORE_TYPE_MAP[it] }

        // Supertypes → proto int values
        val supertypes = type.supertypes.mapNotNull { SUPERTYPE_MAP[it] }

        // Subtypes → proto int values (skip unknown)
        val subtypes = type.subtypes.mapNotNull { SUBTYPE_MAP[it.lowercase()] }

        // Colors → proto CardColor values
        val colorSet = card.rules.color
        val colors = mutableListOf<Int>()
        if (colorSet.hasWhite()) colors.add(1) // White
        if (colorSet.hasBlue()) colors.add(2) // Blue
        if (colorSet.hasBlack()) colors.add(3) // Black
        if (colorSet.hasRed()) colors.add(4) // Red
        if (colorSet.hasGreen()) colors.add(5) // Green

        // Power / Toughness
        val power = if (type.isCreature) (rules.intPower.let { if (it == Integer.MAX_VALUE) "0" else it.toString() }) else ""
        val toughness = if (type.isCreature) (rules.intToughness.let { if (it == Integer.MAX_VALUE) "0" else it.toString() }) else ""

        // Mana cost → (ManaColor, count) pairs
        val manaCost = deriveManaCost(rules.manaCost)

        // Abilities — assign synthetic sequential IDs per ability on the card
        val derived = deriveAbilityIds(card)
        val abilityIds = derived.abilityIds
        val keywordAbilityGrpIds = derived.keywordAbilityGrpIds

        val linkedFaces = resolveLinkedFaceGrpIds(card)

        return CardData(
            grpId = grpId,
            titleId = titleId,
            power = power,
            toughness = toughness,
            colors = colors,
            types = types,
            subtypes = subtypes,
            supertypes = supertypes,
            abilityIds = abilityIds,
            manaCost = manaCost,
            keywordAbilityGrpIds = keywordAbilityGrpIds,
            linkedFaceGrpIds = linkedFaces,
        )
    }

    private fun deriveManaCost(cost: forge.card.mana.ManaCost?): List<Pair<ManaColor, Int>> {
        if (cost == null || cost.isNoCost) return emptyList()
        val counts = mutableMapOf<ManaColor, Int>()
        val generic = cost.genericCost
        if (generic > 0) counts[ManaColor.Generic] = generic
        for (shard in cost) {
            val color = SHARD_MAP[shard] ?: continue
            counts.merge(color, 1, Int::plus)
        }
        return counts.toList()
    }

    private fun resolveLinkedFaceGrpIds(card: Card): List<Int> {
        val states = card.states ?: return emptyList()
        if (states.size <= 1) return emptyList()
        val linkedIds = mutableListOf<Int>()
        for (stateName in states) {
            if (stateName == forge.card.CardStateName.Original) continue
            if (stateName == forge.card.CardStateName.FaceDown) continue
            val altState = card.getState(stateName) ?: continue
            val altName = altState.name ?: continue
            if (altName == card.name) continue
            linkedIds.add(nameToGrpId.getOrPut(altName) { nextGrpId.getAndIncrement() })
        }
        return linkedIds
    }

    private fun deriveAbilityIds(card: Card) = leyline.game.AbilityIdDeriver.deriveAbilityIds(card, nextAbilityGrpId)

    // ---- Static mapping tables ----

    /** Forge CoreType → proto CardType int value. */
    private val CORE_TYPE_MAP = mapOf(
        CoreType.Artifact to 1,
        CoreType.Creature to 2,
        CoreType.Enchantment to 3,
        CoreType.Instant to 4,
        CoreType.Land to 5,
        CoreType.Phenomenon to 6,
        CoreType.Plane to 7,
        CoreType.Planeswalker to 8,
        CoreType.Scheme to 9,
        CoreType.Sorcery to 10,
        CoreType.Kindred to 11,
        CoreType.Vanguard to 12,
        CoreType.Dungeon to 13,
        CoreType.Battle to 14,
    )

    /** Forge Supertype → proto SuperType int value. */
    private val SUPERTYPE_MAP = mapOf(
        Supertype.Basic to 1,
        Supertype.Legendary to 2,
        Supertype.Ongoing to 3,
        Supertype.Snow to 4,
        Supertype.World to 5,
    )

    /**
     * Forge subtype name (lowercase) → proto SubType int value.
     * Covers the ~80 most common subtypes; unknown subtypes are silently skipped.
     * Extend on demand when tests need specific subtypes.
     */
    private val SUBTYPE_MAP = mapOf(
        // Basic land types
        "forest" to 29,
        "island" to 43,
        "mountain" to 49,
        "plains" to 54,
        "swamp" to 69,
        // Common creature types
        "angel" to 1,
        "archer" to 2,
        "archon" to 3,
        "artificer" to 4,
        "assassin" to 5,
        "aura" to 6,
        "basilisk" to 7,
        "bat" to 8,
        "bear" to 9,
        "beast" to 10,
        "berserker" to 11,
        "bird" to 12,
        "cat" to 14,
        "centaur" to 116,
        "cleric" to 16,
        "construct" to 17,
        "demon" to 19,
        "dinosaur" to 342,
        "djinn" to 20,
        "dragon" to 21,
        "drake" to 22,
        "druid" to 23,
        "dwarf" to 130,
        "elemental" to 25,
        "elephant" to 26,
        "elf" to 27,
        "equipment" to 28,
        "faerie" to 140,
        "fox" to 144,
        "frog" to 145,
        "giant" to 32,
        "goblin" to 34,
        "golem" to 35,
        "griffin" to 36,
        "horse" to 37,
        "human" to 39,
        "hydra" to 40,
        "illusion" to 41,
        "insect" to 42,
        "knight" to 45,
        "merfolk" to 46,
        "minotaur" to 47,
        "monk" to 48,
        "ogre" to 50,
        "ooze" to 51,
        "pegasus" to 52,
        "phoenix" to 53,
        "pirate" to 228,
        "plant" to 229,
        "rat" to 235,
        "rhino" to 55,
        "rogue" to 56,
        "scout" to 58,
        "serpent" to 59,
        "shade" to 60,
        "shaman" to 61,
        "shapeshifter" to 251,
        "skeleton" to 63,
        "snake" to 256,
        "soldier" to 64,
        "sphinx" to 66,
        "spider" to 67,
        "spirit" to 68,
        "treefolk" to 71,
        "troll" to 72,
        "vampire" to 74,
        "wall" to 76,
        "warrior" to 77,
        "wizard" to 78,
        "wolf" to 79,
        "wurm" to 80,
        "zombie" to 81,
        // Non-creature subtypes
        "vehicle" to 331,
        "saga" to 347,
        "shrine" to 253,
        "class" to 400,
        "curse" to 121,
        "trap" to 272,
        "clue" to 324,
        "food" to 363,
        "treasure" to 343,
        "blood" to 403,
        // Land subtypes
        "gate" to 31,
        "desert" to 123,
        "cave" to 419,
        // Additional common types
        "avatar" to 85,
        "devil" to 124,
        "dog" to 379,
        "horror" to 160,
        "imp" to 162,
        "ninja" to 215,
        "orc" to 221,
        "saproling" to 84,
        "squirrel" to 264,
        "thopter" to 269,
        "unicorn" to 275,
        "werewolf" to 283,
    )

    /** ManaCostShard → proto ManaColor. Only simple shards mapped; hybrids skipped. */
    private val SHARD_MAP = mapOf(
        ManaCostShard.WHITE to ManaColor.White_afc9,
        ManaCostShard.BLUE to ManaColor.Blue_afc9,
        ManaCostShard.BLACK to ManaColor.Black_afc9,
        ManaCostShard.RED to ManaColor.Red_afc9,
        ManaCostShard.GREEN to ManaColor.Green_afc9,
        ManaCostShard.COLORLESS to ManaColor.Colorless_afc9,
        ManaCostShard.X to ManaColor.X,
    )
}
