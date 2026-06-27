package leyline.game

import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.WireId

/**
 * Shorthand ID constructors for tests.
 *
 * Keep test fixtures readable: `100.iid` instead of `InstanceId(100)`.
 * The full `InstanceId(100)` constructor still works when a test benefits from
 * the explicit type at the call site; use the `.iid`/`.sid`/... shorthand when
 * a signature already documents the slot.
 */
val Int.iid: InstanceId get() = InstanceId(this)
val Int.sid: SeatId get() = SeatId(this)
val Int.grp: GrpId get() = GrpId(this)
val Int.eid: EffectId get() = EffectId(this)
val Int.wid: WireId get() = WireId(this)
