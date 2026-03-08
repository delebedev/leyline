package leyline.bridge

// Type-safe ID wrappers for the matchdoor module.
//
// Not wrapped (low confusion risk):
//   grpId, abilityGrpId — "lookup → pass to proto" types that don't
//   participate in bidirectional mapping (getOrAlloc/getForgeCardId).
//   Distinct value ranges (~50k+ for grpId) and one-way proto flow.
//   Wire them if a bug proves otherwise.

/** Forge engine card identity (`Card.id`). Stable within a game. */
@JvmInline value class ForgeCardId(val value: Int)

/** Forge engine player identity (`Player.id`). Stable within a game. */
@JvmInline value class ForgePlayerId(val value: Int)

/** Client protocol object identity. Reallocated on zone transfer. */
@JvmInline value class InstanceId(val value: Int)

/** Player seat (1 = human, 2 = AI). Constant within a match. */
@JvmInline value class SeatId(val value: Int)

/**
 * Proto-layer identity — "something identifiable on the wire."
 *
 * Proto fields like `affectorId`, `affectedIds`, and `targetInstanceId`
 * accept both card [InstanceId]s and player [SeatId]s. [WireId] models
 * this union at the proto boundary. Grep for `toWireId` to audit every crossing.
 */
@JvmInline value class WireId(val value: Int)

fun InstanceId.toWireId() = WireId(value)
fun SeatId.toWireId() = WireId(value)
