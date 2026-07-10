package leyline.game.event

import leyline.bridge.types.ForgeCardId

/** Immutable Forge-native cause identity frozen when a card moves zones. */
data class ZoneMoveCause(
    val sourceCardId: ForgeCardId?,
    val abilityForgeId: Int,
    val rootAbilityForgeId: Int,
    val api: String?,
    val costPayment: Boolean,
    val stackAbilityForgeId: Int = 0,
)

/** One actual Forge zone operation, ordered within its frame. */
data class ZoneMove(
    val order: Int,
    val cardId: ForgeCardId,
    val from: Zone,
    val to: Zone,
    val cause: ZoneMoveCause?,
)
