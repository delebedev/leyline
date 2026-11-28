package leyline.bridge

import forge.ai.ComputerUtilAbility
import forge.card.CardStateName
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.Game
import forge.game.GameActionUtil
import forge.game.card.Card
import forge.game.cost.CostPartMana
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.PlayerAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Compares one published priority window with Forge's own enumeration of what
 * the priority player can play.
 *
 * - **F**: `Card.getAllPossibleAbilities(player, true)` over the cards Forge AI
 *   considers for that player ([ComputerUtilAbility.getAvailableCards]), plus
 *   every graveyard and command zone.
 * - **C**: Leyline's [PriorityActionCandidates] for the window.
 * - **O**: the published offers. Active offers map back to Forge abilities
 *   through [ActionOffer.command]; inactive offers carry no Forge ability and
 *   match by card and [Kind], each standing for at most one F ability. An F
 *   ability counts as offered inactive only
 *   when Leyline's own availability check rejects it; otherwise the inactive
 *   action belongs to another ability and this one was dropped.
 *
 * Abilities match by [signature], since Forge builds a fresh copy of every
 * alternative-cost ability on each enumeration. An F ability whose exact
 * signature is not offered still counts as offered when an active offer is one
 * of its cost variants ([isCostVariant]): Leyline offers additional-cost
 * choices and Jumpstart or Retrace payments as separate actions of the one
 * Forge spell. Abilities have no such split, so two abilities of one card never
 * stand in for each other.
 *
 * Every difference falls into one [OfferDiffBucket] and is named by the first
 * [OfferDiffRule] that matches it; a difference no rule names is a gap.
 */
object OfferDiff {
    enum class Kind { Spell, Land, Ability, Mana }

    /** One inactive action, by card, [Kind] and, for a land play, whether it plays the card's back face. */
    data class InactiveOffer(
        val cardId: Int,
        val kind: Kind,
        val backFace: Boolean = false,
    )

    fun compare(
        game: Game,
        player: Player,
        candidates: PriorityActionCandidates,
        offers: List<ActionOffer>,
        inactive: List<InactiveOffer>,
    ): List<OfferDiffEntry> =
        NonInteractiveScope.bestEffort {
            val forge = forgeEnumeration(game, player)
            classify(
                diff(
                    forge,
                    candidateAbilities(candidates),
                    offers,
                    inactive,
                    canExecute = { ActionAvailability.canExecute(it, player) },
                ),
            )
        }

    /** F: what Forge lists as playable by [player] from every card it could play from. */
    fun forgeEnumeration(
        game: Game,
        player: Player,
    ): List<SpellAbility> {
        val cards =
            (
                ComputerUtilAbility.getAvailableCards(game, player) +
                    game.getCardsIn(listOf(ZoneType.Graveyard, ZoneType.Command))
            ).distinctBy { it.id }
        return cards.flatMap { it.getAllPossibleAbilities(player, true) }
    }

    private fun candidateAbilities(candidates: PriorityActionCandidates): List<SpellAbility> =
        candidates.all.flatMap { candidate ->
            candidate.casts + candidate.activations + candidate.manaAbilities +
                listOfNotNull(candidate.landAbility, candidate.mdfcLandAbility)
        }

    internal fun diff(
        forge: List<SpellAbility>,
        candidates: List<SpellAbility>,
        offers: List<ActionOffer>,
        inactive: Collection<InactiveOffer>,
        canExecute: (SpellAbility) -> Boolean,
    ): List<OfferDiffEntry> {
        val offered = offers.mapNotNull { offeredAbility(it.command) }
        // A land play names only its card; the action type says which face it plays.
        val offeredLands =
            offers.mapNotNullTo(mutableSetOf()) { offer ->
                (offer.command as? PlayerAction.PlayLand)?.let { it.cardId.value to (offer.action.actionType == ActionType.PlayMdfc) }
            }
        // Each inactive action stands for at most one F ability.
        val unclaimedInactive = inactive.groupingBy { it }.eachCount().toMutableMap()

        fun claimInactive(
            key: InactiveOffer,
            unavailable: () -> Boolean,
        ): Boolean {
            val left = unclaimedInactive[key] ?: 0
            if (left == 0 || !unavailable()) return false
            unclaimedInactive[key] = left - 1
            return true
        }
        val offeredSignatures = offered.mapTo(mutableSetOf()) { signature(it) }
        val offeredByFamily = offered.groupBy { family(it) }
        val candidateSignatures = candidates.mapTo(mutableSetOf()) { signature(it) }
        val candidatesByFamily = candidates.groupBy { family(it) }
        val forgeSignatures = forge.mapTo(mutableSetOf()) { signature(it) }
        val forgeByFamily = forge.groupBy { family(it) }

        fun hasVariant(
            base: SpellAbility,
            byFamily: Map<String, List<SpellAbility>>,
        ) = byFamily[family(base)].orEmpty().any { isCostVariant(it, base) }

        // A spell with required additional costs is played through Forge's variants of it, so those decide it.
        fun isExecutable(sa: SpellAbility): Boolean = GameActionUtil.getAdditionalCostSpell(sa).any(canExecute)

        val missing =
            forge.distinctBy { signature(it) }.mapNotNull { sa ->
                val card = sa.hostCard
                val kind = kindOf(sa)
                val bucket =
                    when {
                        signature(sa) in offeredSignatures || hasVariant(sa, offeredByFamily) -> return@mapNotNull null
                        kind == Kind.Land && (card.id to isBackFace(sa)) in offeredLands -> return@mapNotNull null
                        claimInactive(InactiveOffer(card.id, kind, kind == Kind.Land && isBackFace(sa))) { !isExecutable(sa) } ->
                            OfferDiffBucket.OfferedInactive
                        signature(sa) in candidateSignatures || hasVariant(sa, candidatesByFamily) -> OfferDiffBucket.ProjectionDrop
                        else -> OfferDiffBucket.EnumerationGap
                    }
                entry(
                    bucket,
                    sa,
                    costVariant = false,
                    unaffordable = bucket == OfferDiffBucket.ProjectionDrop && !isExecutable(sa),
                )
            }
        val extra =
            offered.distinctBy { signature(it) }.mapNotNull { sa ->
                if (signature(sa) in forgeSignatures) return@mapNotNull null
                entry(
                    OfferDiffBucket.OfferedNotInForge,
                    sa,
                    costVariant = forgeByFamily[family(sa)].orEmpty().any { isCostVariant(sa, it) },
                )
            }
        val forgeLands = forge.filter { it.isLandAbility }.mapTo(mutableSetOf()) { it.hostCard.id to isBackFace(it) }
        val extraLands =
            offeredLands.filterNot { it in forgeLands }.mapNotNull { face ->
                val land =
                    candidates.firstOrNull { it.isLandAbility && it.hostCard.id == face.first && isBackFace(it) == face.second }
                        ?: return@mapNotNull null
                entry(OfferDiffBucket.OfferedNotInForge, land, costVariant = false)
            }
        return missing + extra + extraLands
    }

    /** A land play of a modal double-faced card's back face. */
    private fun isBackFace(sa: SpellAbility): Boolean = sa.cardState?.stateName == CardStateName.Backside

    internal fun classify(entries: List<OfferDiffEntry>): List<OfferDiffEntry> =
        entries.map { entry -> entry.copy(rule = OfferDiffRule.entries.firstOrNull { it.matches(entry) }) }

    private fun offeredAbility(command: PlayerAction): SpellAbility? =
        (command as? PlayerAction.CastSpell)?.ability
            ?: (command as? PlayerAction.ActivateAbility)?.ability
            ?: (command as? PlayerAction.ActivateMana)?.ability

    fun kindOf(sa: SpellAbility): Kind =
        when {
            sa.isLandAbility -> Kind.Land
            sa.isManaAbility() -> Kind.Mana
            sa.isSpell -> Kind.Spell
            else -> Kind.Ability
        }

    /** Identity of one playable ability that survives Forge rebuilding its alternative-cost copies. */
    internal fun signature(sa: SpellAbility): String =
        listOf(
            family(sa),
            sa.payCosts?.toString().orEmpty(),
            sa.optionalCosts
                .map { it.name }
                .sorted()
                .joinToString("+"),
            sa.description.orEmpty(),
        ).joinToString("|")

    /**
     * [variant] is the spell [base] with costs added: an additional-cost choice or a Jumpstart
     * or Retrace payment. It shares [base]'s [family] and pays everything [base]
     * pays. A cost that replaces the mana cost, such as one granted by a static
     * ability, is a different play.
     */
    internal fun isCostVariant(
        variant: SpellAbility,
        base: SpellAbility,
    ): Boolean {
        if (kindOf(variant) != Kind.Spell || family(variant) != family(base) || signature(variant) == signature(base)) return false
        val variantCost = variant.payCosts ?: return false
        val baseCost = base.payCosts ?: return true
        val variantParts = variantCost.costParts.filterNot { it is CostPartMana }.map { it.toString() }
        val baseParts = baseCost.costParts.filterNot { it is CostPartMana }.map { it.toString() }
        return variantParts.containsAll(baseParts) && manaCovers(variantCost.totalMana, baseCost.totalMana)
    }

    private fun manaCovers(
        cost: ManaCost,
        base: ManaCost,
    ): Boolean {
        val shards = cost.groupingBy { it }.eachCount()
        val coloured = base.filter { it != ManaCostShard.GENERIC }.groupingBy { it }.eachCount()
        return cost.cmc >= base.cmc && coloured.all { (shard, count) -> (shards[shard] ?: 0) >= count }
    }

    /** [signature] without the cost: the one Forge ability every cost variant of it shares. */
    internal fun family(sa: SpellAbility): String =
        listOf(
            sa.hostCard?.id ?: -1,
            kindOf(sa).name,
            sa.cardState
                ?.stateName
                ?.name
                .orEmpty(),
            sa.api?.name.orEmpty(),
            sa.alternativeCost?.name.orEmpty(),
            sa.mayPlay?.id ?: -1,
            sa.isCastFaceDown,
        ).joinToString("|")

    private fun entry(
        bucket: OfferDiffBucket,
        sa: SpellAbility,
        costVariant: Boolean,
        unaffordable: Boolean = false,
    ): OfferDiffEntry {
        val card: Card = sa.hostCard
        return OfferDiffEntry(
            bucket = bucket,
            card = card.name,
            zone = card.zone?.zoneType?.name ?: "None",
            kind = kindOf(sa),
            apiType = sa.api?.name ?: kindOf(sa).name,
            ability = describe(sa),
            description = sa.description.orEmpty(),
            alternativeCost = sa.alternativeCost?.name,
            optionalCosts = sa.optionalCosts.map { it.name }.sorted(),
            keywordAbility = sa.keyword?.keyword?.name,
            costVariant = costVariant,
            unaffordable = unaffordable,
            castFaceDown = sa.isCastFaceDown,
            turnFaceUp = sa.isTurnFaceUp,
            illegalOnStack = sa.isSpell && !permitsFixedCostSpell(sa),
            noPayableAdditionalCost = bucket == OfferDiffBucket.EnumerationGap && hasNoPayableAdditionalCost(sa),
            sample = sample(card),
        )
    }

    /**
     * A spell that requires one of several additional costs, none of which can be
     * paid now. Forge lists the spell itself; casting it would fail at payment.
     * Building the variants allocates ability copies, so this runs only for gaps.
     */
    private fun hasNoPayableAdditionalCost(sa: SpellAbility): Boolean =
        sa.isSpell &&
            sa.hostCard.keywords.any { it.original.startsWith("AlternateAdditionalCost") } &&
            GameActionUtil.getAdditionalCostSpell(sa).isEmpty()

    /** The window and card state the difference appeared in, for triage. */
    private fun sample(card: Card): String {
        val game = card.game
        val flags =
            listOfNotNull(
                "tapped".takeIf { card.isTapped },
                "entered-this-turn".takeIf { card.enteredThisTurn() },
                "face-down".takeIf { card.isFaceDown },
            )
        return "turn=${game.phaseHandler.turn} phase=${game.phaseHandler.phase} stack=${game.stack.size()}" +
            flags.joinToString("") { " $it" }
    }

    private fun describe(sa: SpellAbility): String {
        val cost = sa.payCosts?.toSimpleString().orEmpty()
        val variant = sa.alternativeCost?.name ?: sa.optionalCosts.joinToString("+") { it.name }.ifEmpty { null }
        return listOfNotNull(kindOf(sa).name, variant, cost.ifEmpty { null }).joinToString(" ")
    }
}

/**
 * Where an F ability and the published window disagree.
 *
 * [EnumerationGap] and [ProjectionDrop] are both plays Forge lists and the
 * player is not offered; they differ by the stage that lost the play.
 */
enum class OfferDiffBucket {
    /** In F, not a Leyline candidate. */
    EnumerationGap,

    /** In F and a candidate, but neither an active nor an inactive offer. */
    ProjectionDrop,

    /** In F and offered only as an inactive action. */
    OfferedInactive,

    /** Offered as an active action, not in F: an intended split or an over-offer. */
    OfferedNotInForge,
}

/** One ability on which F and the published window disagree, with the rule that names it. */
data class OfferDiffEntry(
    val bucket: OfferDiffBucket,
    val card: String,
    val zone: String,
    val kind: OfferDiff.Kind,
    val apiType: String,
    val ability: String,
    /** Forge's text for the ability, which tells apart abilities whose kind and cost read the same. */
    val description: String = "",
    val alternativeCost: String? = null,
    val optionalCosts: List<String> = emptyList(),
    val keywordAbility: String? = null,
    /** An offered ability that shares its Forge ability with an F entry and differs only in cost. */
    val costVariant: Boolean = false,
    /** A dropped play that Leyline's availability check rejects: the player could not have made it. */
    val unaffordable: Boolean = false,
    val castFaceDown: Boolean = false,
    val turnFaceUp: Boolean = false,
    /** A spell whose cast permission Forge checks only once it is on the stack, and that would fail that check now. */
    val illegalOnStack: Boolean = false,
    /** A spell whose required additional costs all are unpayable now. */
    val noPayableAdditionalCost: Boolean = false,
    /** The window and card state the difference appeared in. */
    val sample: String = "",
    val rule: OfferDiffRule? = null,
)

/**
 * Expected differences between F and the published window. Each rule names the
 * bucket it applies to and why the difference is intended. A difference no rule
 * matches fails a strict run.
 */
enum class OfferDiffRule(
    val bucket: OfferDiffBucket,
    val reason: String,
) {
    InactiveUnaffordable(
        OfferDiffBucket.OfferedInactive,
        "Forge lists the ability without checking that its costs can be paid; Leyline's availability check rejects it, " +
            "and the client shows it as inactive",
    ) {
        override fun matches(entry: OfferDiffEntry) = entry.bucket == bucket
    },
    UnaffordableNotShown(
        OfferDiffBucket.ProjectionDrop,
        "Forge lists each cost of a card it cannot pay; Leyline shows one inactive action for the card " +
            "and none for the other unaffordable costs",
    ) {
        override fun matches(entry: OfferDiffEntry) = entry.bucket == bucket && entry.unaffordable
    },
    CostVariantSplit(
        OfferDiffBucket.OfferedNotInForge,
        "Leyline offers each additional-cost choice and each Jumpstart or Retrace payment as its own action; " +
            "Forge lists the ability once",
    ) {
        override fun matches(entry: OfferDiffEntry) = entry.bucket == bucket && entry.costVariant
    },
    IllegalOnStack(
        OfferDiffBucket.EnumerationGap,
        "Forge lists a cast whose permission it checks only after the spell is on the stack; " +
            "Leyline withholds the offer when that check would fail, such as a mana value condition",
    ) {
        override fun matches(entry: OfferDiffEntry) = entry.bucket == bucket && entry.illegalOnStack
    },
    NoPayableAdditionalCost(
        OfferDiffBucket.EnumerationGap,
        "Forge lists a spell that requires one of several additional costs without checking them; " +
            "Leyline offers one action per payable additional cost, and none is payable",
    ) {
        override fun matches(entry: OfferDiffEntry) = entry.bucket == bucket && entry.noPayableAdditionalCost
    },
    ;

    abstract fun matches(entry: OfferDiffEntry): Boolean
}
