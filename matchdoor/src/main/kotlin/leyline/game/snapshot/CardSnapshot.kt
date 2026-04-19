package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

/**
 * Immutable snapshot of one card's observable state. Fields grow as mappers migrate
 * — identity, zone, object attributes (p/t, tapped, counters, combat state), action
 * flags (abilities, cost materials), and persistent-annotation inputs
 * (`isOnAdventure`, `endOfTurnLeavePlay`).
 */
data class CardSnapshot(
    val forgeCardId: ForgeCardId,
    val name: String,
    val grpId: Int,
    val owner: SeatId,
    val controller: SeatId,

    // --- Task 8 fields: ActionMapper shape construction ---

    /** True when Forge considers this card a land (type.isLand). */
    val isLand: Boolean = false,

    /** True when this is an adventure card (has a Secondary state with its own spell ability). */
    val isAdventureCard: Boolean = false,

    /** True when the card has at least one mana ability (used for ActivateMana action shape). */
    val hasManaAbilities: Boolean = false,

    /**
     * True when the card has at least one non-mana activated ability.
     * Needed to know whether to iterate spellAbilities during action enumeration.
     */
    val hasNonManaActivatedAbilities: Boolean = false,

    // --- Task 6 fields: ObjectMapper live state (applyFieldsFromSnapshot + applyCombatFromSnapshot) ---

    /** True when the card is in the Battlefield zone. */
    val isOnBattlefield: Boolean = false,

    /** Live net power from Forge (continuous effects/counters). Non-null only for creatures. */
    val netPower: Int? = null,

    /** Live net toughness from Forge (continuous effects/counters). Non-null only for creatures. */
    val netToughness: Int? = null,

    /** Whether the permanent is tapped (battlefield only; false off-battlefield). */
    val tapped: Boolean = false,

    /** Whether the creature has summoning sickness (battlefield creatures only). */
    val hasSickness: Boolean = false,

    /** Combat damage marked on this creature. */
    val damage: Int = 0,

    /** Current loyalty counter value for planeswalkers. */
    val currentLoyalty: Int = 0,

    /** True when Forge considers this a token. Used for GameObjectType selection. */
    val isToken: Boolean = false,

    /** True when this is a copy token (Forge copiedPermanent != null). */
    val isCopyToken: Boolean = false,

    /**
     * ForgeCardId of the card this is attached to (Aura/Equipment enchanted/equipped permanent).
     * Null when not attached.
     */
    val attachedTo: ForgeCardId? = null,

    /**
     * Live core card types from Forge (continuous effects can add/remove types).
     * Stored as proto [CardType] ordinal integers for easy comparison.
     */
    val liveCardTypeNumbers: List<Int> = emptyList(),

    /** True when the card is double-faced (has an Backside/Original alternate state). */
    val isDoubleFaced: Boolean = false,

    /**
     * Other face grpId for DFC cards; 0 for non-DFC.
     * Pre-resolved so [ObjectMapper.buildFromSnapshot] doesn't need CardRepository.
     */
    val othersideGrpId: Int = 0,

    /** Forge CardStateName of the current face, used to resolve the correct face in DFC logic. */
    val currentStateNameIsBackside: Boolean = false,

    /**
     * Combat role for battlefield creatures; null for non-creatures or non-combat cards.
     */
    val combatRole: CombatRole? = null,

    /**
     * True when this card is currently in Exile and was exiled "on Adventure"
     * (Forge's `card.isOnAdventure`). Drives Qualification pAnn for the
     * cast-from-exile eligibility marker.
     */
    val isOnAdventure: Boolean = false,

    /**
     * True when the card is a token with the `EndOfTurnLeavePlay` SVar set
     * (Forge's `card.isToken && card.hasSVar("EndOfTurnLeavePlay")`). Drives
     * TemporaryPermanent pAnn so the client renders EOT-sacrifice tokens.
     */
    val endOfTurnLeavePlay: Boolean = false,
)

/**
 * Per-card combat role, populated from Forge [forge.game.combat.Combat].
 * Sealed so ObjectMapper can exhaustively pattern-match without a default branch.
 */
sealed interface CombatRole {
    /**
     * This card is declared as an attacker.
     *
     * @param targetInstanceId Arena instance ID of the defending player or planeswalker/battle.
     *   0 means the target couldn't be resolved (should be treated as "no info").
     * @param isBlocked Whether the attacking band is blocked (true), unblocked (false), or unknown (null).
     */
    data class Attacker(
        val targetInstanceId: Int,
        val isBlocked: Boolean?,
    ) : CombatRole

    /**
     * This card is declared as a blocker.
     *
     * @param attackerInstanceIds Arena instance IDs of the attackers this card is blocking.
     */
    data class Blocker(
        val attackerInstanceIds: List<Int>,
    ) : CombatRole
}
