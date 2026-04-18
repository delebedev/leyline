package leyline.game.snapshot

import leyline.bridge.ForgeCardId

/**
 * Combat declarations. Null on [GsmSnapshot] outside combat phases. Fields grow
 * as ObjectMapper's combat logic migrates.
 */
data class CombatSnapshot(
    val attackers: Map<ForgeCardId, ForgeCardId>,
    val blockers: Map<ForgeCardId, List<ForgeCardId>>,
)
