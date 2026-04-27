package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

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
    /**
     * True when this card is prepared (Forge's `Card.isPrepared()` — the card has
     * an active prepared-spell exile copy). Drives the `Prepared` card-state
     * Designation persistent annotation (DesignationType=24).
     */
    val isPrepared: Boolean = false,
    /**
     * ForgeCardId of the prepare-spell exile copy associated with this card,
     * resolved from the prepared-effect's first remembered card. Non-null only
     * when [isPrepared] is true. Used to populate `PreparedCopyZcid` on the
     * persistent Designation annotation.
     */
    val preparedCopyForgeCardId: ForgeCardId? = null,
    /**
     * ForgeCardId of the source card whose prepared-effect created this card
     * as a prepared-spell exile copy. Non-null only on the copy itself, and
     * only while a live battlefield permanent owns the copy. Used to populate
     * `parentId` on the projected `GameObjectInfo` so the client can link the
     * exile copy back to its prepared source creature.
     */
    val preparedSourceForgeCardId: ForgeCardId? = null,
    /**
     * True when this card is a prepared-spell copy — Forge's `GamePieceType.TOKEN`
     * with an active `PreparedSpell` face state. Drives projection as a normal
     * `GameObjectType_Card` (not Token) and the by-name grpId resolution path.
     * Distinct from [preparedSourceForgeCardId], which is null mid-cast after
     * the unprepare trigger has fired or when the copy is on the stack with a
     * freshly reallocated Forge id.
     */
    val isPreparedCopy: Boolean = false,
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
