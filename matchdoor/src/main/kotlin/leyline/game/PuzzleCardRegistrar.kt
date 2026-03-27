package leyline.game

import forge.card.CardType.CoreType
import forge.card.CardType.Supertype
import forge.card.mana.ManaCostShard
import forge.game.card.Card
import forge.model.FModel
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registers puzzle cards in an [InMemoryCardRepository] at runtime.
 *
 * Derives [CardData] from Forge's in-memory [forge.card.CardRules] —
 * no client SQLite needed. Synthetic grpIds start at 300000 (above test range
 * at 200000 and real Arena grpIds which reach ~100000+).
 *
 * Production counterpart of the test-only `CardDataDeriver` / `TestCardRegistry`.
 */
class PuzzleCardRegistrar(
    private val repo: InMemoryCardRepository,
    /** Optional client DB repo — checked first for real grpIds (art/text). */
    private val clientRepo: CardRepository? = null,
) {
    private val log = LoggerFactory.getLogger(PuzzleCardRegistrar::class.java)

    private val nextGrpId = AtomicInteger(300000)
    private val nextTitleId = AtomicInteger(30000)
    private val nextAbilityGrpId = AtomicInteger(30000)

    /** name -> grpId cache: same card name always gets same grpId within a JVM. */
    private val nameToGrpId = mutableMapOf<String, Int>()

    /**
     * Ensure a card is registered in the repository. Idempotent.
     * If already known, returns existing grpId.
     * Checks [clientRepo] first for a real grpId (with art), then falls back to synthetic.
     */
    fun ensureCardRegistered(card: Card): Int {
        repo.findGrpIdByName(card.name)?.let { return it }

        // Try client DB for real grpId (has art/text assets)
        val clientGrpId = clientRepo?.findGrpIdByName(card.name)
        if (clientGrpId != null) {
            val clientData = clientRepo.findByGrpId(clientGrpId)
            if (clientData != null) {
                repo.registerData(clientData, card.name)
                log.debug("Registered puzzle card '{}' grpId={} (client DB)", card.name, clientGrpId)
                // Adventure cards: also register the secondary face (e.g. "Pest Problem")
                // so token grpId resolution can find it.
                registerAdventureFace(card)
                return clientGrpId
            }
        }

        val data = fromForgeCard(card)
        repo.registerData(data, card.name)
        log.debug("Registered puzzle card '{}' grpId={} (synthetic)", card.name, data.grpId)
        registerAdventureFace(card)
        return data.grpId
    }

    /** Register the adventure face name if the card is an adventure card. */
    private fun registerAdventureFace(card: Card) {
        if (!card.isAdventureCard) return
        val secondaryName = card.getState(forge.card.CardStateName.Secondary)?.name ?: return
        if (repo.findGrpIdByName(secondaryName) != null) return
        // Adventure faces have IsPrimaryCard=0 — use findGrpIdByNameAnyFace
        val clientGrpId = clientRepo?.findGrpIdByNameAnyFace(secondaryName)
        if (clientGrpId != null) {
            val clientData = clientRepo.findByGrpId(clientGrpId)
            if (clientData != null) {
                repo.registerData(clientData, secondaryName)
                log.debug("Registered adventure face '{}' grpId={}", secondaryName, clientGrpId)
            }
        }
    }

    /**
     * Ensure a card is registered by name. Creates a temporary Card object
     * from the paper card database if needed.
     */
    fun ensureCardRegisteredByName(cardName: String): Int {
        repo.findGrpIdByName(cardName)?.let { return it }

        // Try client DB first
        val clientGrpId = clientRepo?.findGrpIdByName(cardName)
        if (clientGrpId != null) {
            val clientData = clientRepo.findByGrpId(clientGrpId)
            if (clientData != null) {
                repo.registerData(clientData, cardName)
                log.debug("Registered puzzle card '{}' grpId={} (client DB)", cardName, clientGrpId)
                return clientGrpId
            }
        }

        val db = FModel.getMagicDb()?.commonCards ?: return 0
        val paperCard = db.getCard(cardName)
            ?: run {
                forge.StaticData.instance().attemptToLoadCard(cardName)
                db.getCard(cardName)
            }
            ?: run {
                log.warn("Card '{}' not found in Forge DB", cardName)
                return 0
            }
        val tempCard = Card.fromPaperCard(paperCard, null)
        return ensureCardRegistered(tempCard)
    }

    /** Derive [CardData] from a live Forge [Card]. */
    private fun fromForgeCard(card: Card): CardData {
        val name = card.name
        val grpId = nameToGrpId.getOrPut(name) { nextGrpId.getAndIncrement() }
        val titleId = nextTitleId.getAndIncrement()

        val type = card.type
        val rules = card.rules

        val types = type.coreTypes.mapNotNull { CORE_TYPE_MAP[it] }
        val supertypes = type.supertypes.mapNotNull { SUPERTYPE_MAP[it] }
        val subtypes = type.subtypes.mapNotNull { SUBTYPE_MAP[it.lowercase()] }

        val colorSet = rules.color
        val colors = mutableListOf<Int>()
        if (colorSet.hasWhite()) colors.add(1)
        if (colorSet.hasBlue()) colors.add(2)
        if (colorSet.hasBlack()) colors.add(3)
        if (colorSet.hasRed()) colors.add(4)
        if (colorSet.hasGreen()) colors.add(5)

        val power = if (type.isCreature) rules.intPower.let { if (it == Integer.MAX_VALUE) "0" else it.toString() } else ""
        val toughness = if (type.isCreature) rules.intToughness.let { if (it == Integer.MAX_VALUE) "0" else it.toString() } else ""

        val manaCost = deriveManaCost(rules.manaCost)
        val (abilityIds, keywordAbilityGrpIds) = deriveAbilityIds(card)

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

    private fun deriveAbilityIds(card: Card) = AbilityIdDeriver.deriveAbilityIds(card, nextAbilityGrpId)

    companion object {
        private val CORE_TYPE_MAP = mapOf(
            CoreType.Artifact to 1, CoreType.Creature to 2, CoreType.Enchantment to 3,
            CoreType.Instant to 4, CoreType.Land to 5, CoreType.Phenomenon to 6,
            CoreType.Plane to 7, CoreType.Planeswalker to 8, CoreType.Scheme to 9,
            CoreType.Sorcery to 10, CoreType.Kindred to 11, CoreType.Vanguard to 12,
            CoreType.Dungeon to 13, CoreType.Battle to 14,
        )

        private val SUPERTYPE_MAP = mapOf(
            Supertype.Basic to 1,
            Supertype.Legendary to 2,
            Supertype.Ongoing to 3,
            Supertype.Snow to 4,
            Supertype.World to 5,
        )

        private val SUBTYPE_MAP = mapOf(
            "forest" to 29, "island" to 43, "mountain" to 49, "plains" to 54, "swamp" to 69,
            "angel" to 1, "beast" to 10, "bird" to 12, "cat" to 14, "cleric" to 16,
            "construct" to 17, "demon" to 19, "dragon" to 21, "druid" to 23,
            "elemental" to 25, "elf" to 27, "equipment" to 28, "giant" to 32,
            "goblin" to 34, "golem" to 35, "human" to 39, "insect" to 42,
            "knight" to 45, "merfolk" to 46, "ogre" to 50, "phoenix" to 53,
            "rogue" to 56, "shaman" to 61, "skeleton" to 63, "soldier" to 64,
            "spirit" to 68, "vampire" to 74, "wall" to 76, "warrior" to 77,
            "wizard" to 78, "wolf" to 79, "zombie" to 81,
            "aura" to 6, "vehicle" to 331, "saga" to 347, "treasure" to 343,
        )

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
}
