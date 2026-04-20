package leyline.bridge.types

// Type-safe ID wrappers for the matchdoor module.
//
// The annotation pipeline carries these through; builders take them at the edge
// and unwrap once when writing to proto. Data fields (amounts, deltas, counts,
// enum ordinals) stay Int — these types are for IDs only.
//
// Zones intentionally remain Int. The proto zone layer (GameObjectInfo.zoneId,
// ZoneInfo.zoneId, ZoneIds constants) is pervasive and tying it to a value
// class has a large ripple (~200 sites); named constants via ZoneIds.* already
// document intent at call sites.

/** Forge engine card identity (`Card.id`). Stable within a game. */
@JvmInline value class ForgeCardId(
    val value: Int,
)

/** Forge engine player identity (`Player.id`). Stable within a game. */
@JvmInline value class ForgePlayerId(
    val value: Int,
)

/** Client protocol object identity. Reallocated on zone transfer. */
@JvmInline value class InstanceId(
    val value: Int,
)

/** Player seat (1 = human, 2 = AI). Constant within a match. */
@JvmInline value class SeatId(
    val value: Int,
)

/** Card definition identifier ("group id" in the client's vocabulary). One per printed card. */
@JvmInline value class GrpId(
    val value: Int,
)

/** Layered-effect identity. Allocated when an effect starts; stable while it persists. */
@JvmInline value class EffectId(
    val value: Int,
)

/**
 * Proto-layer identity — "something identifiable on the wire."
 *
 * Proto fields like `affectorId`, `affectedIds`, and `targetInstanceId`
 * accept both card [InstanceId]s and player [SeatId]s. [WireId] models
 * this union at the proto boundary. Grep for `toWireId` to audit every crossing.
 */
@JvmInline value class WireId(
    val value: Int,
)

fun InstanceId.toWireId() = WireId(value)

fun SeatId.toWireId() = WireId(value)
