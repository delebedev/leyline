package leyline.copilot

import kotlinx.serialization.Serializable

/**
 * The Forge-AI decision brain's proposed response for one pending human-seat
 * prompt. The explanatory fields support local diagnostics; [responses] is
 * the deliverable form of the same decision.
 *
 * [intent] is the acceptance verb (`play_land`, `cast`, `cast_mdfc`,
 * `activate`, `target`, `pay_cost`, `select_n`, `modal`, `mana_type`,
 * `optional_cost`, `numeric`, `attack`, `attack_all`, `block`, `pass`,
 * `choose_starting_player`) or the sentinel `unrealizable` when the AI produced
 * no response the current decoder can map. [responseIds] is the flat list of
 * instance ids (or grpIds/ctoIds)
 * the client would submit — the surface for diffing a client-submitted
 * response against this proposed one.
 *
 * Public because it crosses the module boundary to the dev HTTP surface; the
 * decision vocabulary it is translated from stays engine-internal.
 */
@Serializable
data class CopilotProposal(
    val intent: String,
    val promptType: String,
    val seat: Int,
    /** Stable identity of the prompt this proposal answers. */
    val promptKey: String? = null,
    val gameStateId: Int? = null,
    val respId: Int? = null,
    /** Primary card the decision centres on (play_land / cast / activate). */
    val card: EntityRef? = null,
    /** Activated-ability grpId for `activate`. */
    val abilityGrpId: Int? = null,
    /** Alternative-cost grpId for `cast_mdfc` / other alt casts. */
    val alternativeGrpId: Int? = null,
    /** Selected entities: target / select_n / pay_cost / attack. */
    val targets: List<EntityRef> = emptyList(),
    /** Desired target ids keyed by the GRE target group's targetIdx. */
    val targetGroups: Map<String, List<Int>> = emptyMap(),
    /** Blocker→attacker assignments for `block`. */
    val blocks: List<BlockAssignment> = emptyList(),
    /** Chosen modal grpIds for `modal`. */
    val modalGrpIds: List<Int> = emptyList(),
    /** Per-cto mana-color picks for `mana_type`. */
    val manaTypes: List<ManaTypeChoice> = emptyList(),
    /** Chosen numeric value for `numeric`. */
    val numericValue: Int? = null,
    /** Fixed-total per-target amounts for `distribution`. */
    val distribution: List<DistributionAmount> = emptyList(),
    /** Accept (true) or decline (false) for `optional_action`. */
    val accept: Boolean? = null,
    /** Casting-time-option id for `optional_cost`. */
    val ctoId: Int? = null,
    /** Flat ids the client should submit — the client-vs-proposal diff surface. */
    val responseIds: List<Int> = emptyList(),
    /** Serialized response messages, in delivery order. Empty when no response can be built. */
    val responses: List<String> = emptyList(),
    /** Why the AI response could not be mapped, for `unrealizable`. */
    val reason: String? = null,
)

/** A resolved game entity (card or player) the client can locate a UI handle for. */
@Serializable
data class EntityRef(
    val instanceId: Int,
    /** `card` or `player`. */
    val kind: String = "card",
    val name: String? = null,
    val grpId: Int? = null,
    val zone: String? = null,
    val ownerSeat: Int? = null,
)

@Serializable
data class BlockAssignment(
    val blocker: EntityRef,
    val attacker: EntityRef,
)

@Serializable
data class ManaTypeChoice(
    val ctoId: Int,
    val color: String,
)

@Serializable
data class DistributionAmount(
    val instanceId: Int,
    val amount: Int,
)
