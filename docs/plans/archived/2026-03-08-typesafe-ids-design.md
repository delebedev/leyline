# Type-Safe IDs for matchdoor

**Status:** Approved
**Date:** 2026-03-08

## Problem

matchdoor uses bare `Int` for 6+ distinct ID concepts. Functions with multiple `Int` parameters silently accept wrong ID types. Real bugs have occurred from passing `grpId` where `instanceId` is expected, and the `seatId`-as-`instanceId` convention for player targets is documented only in comments.

## ID Taxonomy

Two layers, six types:

### Forge Domain (internal)

| Type | Meaning | Range | Lifecycle |
|------|---------|-------|-----------|
| `ForgeCardId` | Forge `Card.id` | Forge-assigned | Stable within game |
| `ForgePlayerId` | Forge `Player.id` | Forge-assigned | Stable within game |

### Arena Wire (proto/client)

| Type | Meaning | Range | Lifecycle |
|------|---------|-------|-----------|
| `InstanceId` | Client object identity | Server-allocated, monotonic | Reallocated on zone transfer |
| `SeatId` | Player seat | 1 or 2 | Constant |
| `GrpId` | Card database ref | 50000+ | Permanent (card identity) |
| `AbilityGrpId` | Ability database ref | Card-dependent | Permanent per card |

### The Wire Boundary

Proto fields like `affectorId`, `affectedIds`, and `targetInstanceId` accept **both** `InstanceId` (for cards) and `SeatId` (for players). This is an Arena protocol convention, not a leyline design choice.

A seventh type models this boundary:

| Type | Meaning | Converts from |
|------|---------|---------------|
| `WireId` | "Something identifiable in proto" | `InstanceId.toWireId()`, `SeatId.toWireId()` |

## Design

### Value Classes

```kotlin
// matchdoor/src/main/kotlin/leyline/game/Ids.kt

@JvmInline value class ForgeCardId(val value: Int)
@JvmInline value class ForgePlayerId(val value: Int)
@JvmInline value class InstanceId(val value: Int)
@JvmInline value class SeatId(val value: Int)
@JvmInline value class GrpId(val value: Int)
@JvmInline value class AbilityGrpId(val value: Int)
@JvmInline value class WireId(val value: Int)

// Explicit boundary crossings — grep for "toWireId" to audit every crossing
fun InstanceId.toWireId() = WireId(value)
fun SeatId.toWireId() = WireId(value)
```

Zero runtime cost. All six compile to `Int` on JVM — no boxing unless used as generic type parameter (rare in our code).

### Target Sealed Class

Delete `TargetDto`. Despite its `@Serializable` annotation, it's never serialized — `MatchSession` constructs `PlayerAction` from proto fields, `WebPlayerController` consumes it. The "DTO" is just an untyped internal data class. Replace with:

```kotlin
sealed class Target {
    data class Card(val cardId: ForgeCardId) : Target()
    data class Player(val playerId: ForgePlayerId) : Target()
}
```

`CardLookup.resolveTarget` becomes:

```kotlin
internal fun resolveTarget(game: Game, target: Target): GameObject? = when (target) {
    is Target.Card -> findCard(game, target.cardId)
    is Target.Player -> game.getPlayer(target.playerId.value)
}
```

### PlayerAction Typed Fields

```kotlin
sealed class PlayerAction {
    data object PassPriority : PlayerAction()
    data class CastSpell(
        val cardId: ForgeCardId,
        val abilityId: Int? = null,
        val targets: List<Target> = emptyList(),
    ) : PlayerAction()
    data class ActivateAbility(
        val cardId: ForgeCardId,
        val abilityId: Int,
        val targets: List<Target> = emptyList(),
    ) : PlayerAction()
    data class ActivateMana(val cardId: ForgeCardId) : PlayerAction()
    data class PlayLand(val cardId: ForgeCardId) : PlayerAction()
    data class DeclareAttackers(
        val attackerIds: List<ForgeCardId>,
        val defender: Target? = null,  // replaces defenderPlayerId + defenderCardId
    ) : PlayerAction()
    data class DeclareBlockers(
        val blockAssignments: Map<ForgeCardId, ForgeCardId>,  // blocker → attacker
    ) : PlayerAction()
    data object EndTurn : PlayerAction()
}
```

`DeclareAttackers.defender` collapses the `defenderPlayerId: Int? / defenderCardId: Int?` pair into one tagged field.

### Bridge Contracts

```kotlin
interface IdMapping {
    fun getOrAllocInstanceId(forgeCardId: ForgeCardId): InstanceId
    fun getForgeCardId(instanceId: InstanceId): ForgeCardId?
    fun reallocInstanceId(forgeCardId: ForgeCardId): InstanceId
}

interface PlayerLookup {
    fun getPlayer(seatId: SeatId): Player?
}
```

### Proto Boundary Pattern

Proto builders receive `.value` at the last mile:

```kotlin
// ObjectMapper — card object
builder.setInstanceId(instanceId.value)
builder.setGrpId(grpId.value)
builder.setOwnerSeatId(seatId.value)

// AnnotationBuilder — zone transfer
builder.setAffectorId(affectorId.value)  // affectorId: WireId
builder.addAffectedIds(affectedId.value) // affectedId: WireId

// RequestBuilder — target list (the seatId-as-instanceId crossing)
val wireId: WireId = if (ref.kind == "player") {
    seatId.toWireId()   // explicit crossing
} else {
    bridge.getOrAllocInstanceId(ForgeCardId(ref.entityId)).toWireId()
}
target.setTargetInstanceId(wireId.value)
```

### AnnotationBuilder

Most annotation methods take mixed ID types. With `WireId`:

```kotlin
fun zoneTransfer(affectorId: WireId, affectedId: WireId, ...) = ...
fun modifiedLife(playerSeatId: SeatId, lifeDelta: Int) =
    ...setAffectorId(playerSeatId.toWireId().value)...
fun userActionTaken(instanceId: InstanceId, seatId: SeatId, actionType: Int) =
    ...setAffectorId(seatId.toWireId().value)...
       .addAffectedIds(instanceId.toWireId().value)...
```

### TargetingHandler Resolution

The current `resolvePlayerTarget(instanceId: Int)` becomes type-safe:

```kotlin
fun resolvePlayerTarget(wireId: WireId, ...): Int? {
    // Try parsing as seat (1 or 2)
    val player = bridge.getPlayer(SeatId(wireId.value)) ?: return null
    ...
}

// Caller in handleSelectTargetsResp:
val wireId = WireId(target.targetInstanceId)
val playerIdx = resolvePlayerTarget(wireId, bridge, pendingPrompt)
if (playerIdx != null) return@mapNotNull playerIdx

val forgeCardId = bridge.getForgeCardId(InstanceId(wireId.value)) ?: ...
```

The `WireId → SeatId` and `WireId → InstanceId` conversions at resolution time are explicit: "I'm interpreting this wire value as a seat" vs "as a card instance."

### WebPlayerController

`findCard(cardId)` and combat methods take typed IDs:

```kotlin
private fun findCard(cardId: ForgeCardId): Card? =
    game.findCardById(cardId.value)

override fun declareAttackers(attacker: Player, combat: Combat) {
    ...
    is PlayerAction.DeclareAttackers -> {
        for (cardId in action.attackerIds) {
            val card = findCard(cardId) ?: continue
            ...
            val defender = when (val d = action.defender) {
                is Target.CardTarget -> findCard(d.cardId)?.takeIf { it.isPlaneswalker }
                is Target.PlayerTarget -> game.players.firstOrNull { it.id == d.playerId.value }
                null -> combat.defenders.firstOrNull()
            } as? GameEntity ?: continue
            ...
        }
    }
}
```

## What Doesn't Change

- **Proto schema** — `messages.proto` stays `uint32`. Value classes unwrap at the `.set*()` call.
- **Forge API** — `Card.id`, `Player.id` stay `Int` (we don't own Forge). Wrapping happens at our boundary.
- **GameEvent sealed variants** — already use `forgeCardId: Int` naming. Becomes `forgeCardId: ForgeCardId`.

## What Gets Deleted

- **`TargetDto`** — replaced by `Target` sealed class. The `@Serializable` annotation was vestigial (never serialized to JSON).
- **`DeclareAttackers.defenderPlayerId` / `defenderCardId`** — collapsed into `defender: Target?`.

## Staging

### Phase 1: Core Types + High-Confusion Paths

Introduce all 7 types in `Ids.kt`. Apply to:

- `InstanceIdRegistry` (allocation/lookup signatures)
- `BridgeContracts` (`IdMapping`, `PlayerLookup`)
- `GameBridge` (implements contracts)
- `PlayerAction` + `Target` sealed class
- `TargetDto.toDomain()` conversion
- `WebPlayerController` (`findCard`, `executeCastSpell`, `declareAttackers/Blockers`)
- `MatchSession` (action construction from proto)
- `CombatHandler` + `TargetingHandler` (reverse lookups)
- `RequestBuilder` (the `seatId.toWireId()` crossing)

~15 files, ~150 call sites. All in bridge/ + match/ + game core.

### Phase 2: Mappers + Annotations

- `ObjectMapper` (all `buildCardObject` / `buildSharedCardObject` signatures)
- `ActionMapper` (all builder methods — 4+ `Int` param signatures)
- `ZoneMapper` (zone population, `addPlayerZones` signature cleanup)
- `PlayerMapper`
- `AnnotationBuilder` (all builder methods → `WireId`/`SeatId`/`InstanceId`)
- `AnnotationPipeline` (`AppliedTransfer` fields, effect builders)
- `StateMapper` (battlefield scan, P/T resolver)
- `CardProtoBuilder` + `GsmBuilder`
- `GameEvent` sealed variants (`forgeCardId: Int` → `ForgeCardId`)

~20 files, ~200 call sites. Mechanical — each is a type annotation change + `.value` at proto boundary.

## Testing

No new tests needed — existing tests catch regressions. Compiler enforces correctness at every call site. If it compiles, the types are consistent.

Run `just test-matchdoor` after each phase.

## Risks

- **Generic collections:** `List<InstanceId>` boxes on JVM (Kotlin inline class limitation). Acceptable — these lists are small (hand size, attacker count). If profiling shows overhead, use `IntArray` wrappers.
- **Serialization:** `TargetDto` uses kotlinx.serialization with `id: Int`. No change needed — domain conversion happens after deserialization.
- **Forge boundary:** Every `card.id` access needs `ForgeCardId(card.id)` wrapping. Verbose but explicit. Could add extension `val Card.forgeCardId get() = ForgeCardId(id)` if too noisy.
