# Type Safety Opportunities — forge-nexus

> 2026-02-17. Survey of places where Kotlin's type system can catch bugs at compile time.
> Not urgent — reference doc for when we touch these areas.

---

## 1. Value Classes for ID Domains

11 distinct `Int` domains flow through the codebase with zero compile-time distinction.

| Domain | Flows through | Risk |
|--------|---------------|------|
| `InstanceId` | StateMapper, AnnotationBuilder, protobuf boundary | Confused with ForgeCardId constantly |
| `ForgeCardId` | InstanceIdRegistry, GameEventCollector, engine callbacks | Confused with InstanceId |
| `GrpId` | CardDb, StateMapper (every card object), protobuf | Confused with InstanceId (both on GameObjectInfo) |
| `SeatId` | Everywhere (1 or 2) | Confused with teamId, zoneId, timerIds |
| `ZoneId` | StateMapper constants (18-38), zone tracking | Magic ints, confused with seatId |
| `AnnotationId` | GameBridge counters → protobuf | Two separate sequences (transient vs persistent) |
| `AbilityGrpId` | CardDb, action building | Confused with grpId |
| `MsgId` | MatchSession, BundleBuilder | Confused with gameStateId |
| `GameStateId` | MatchSession, BundleBuilder, snapshots | Confused with msgId |

**The dangerous pair:** `InstanceId` vs `ForgeCardId`. These are the two sides of InstanceIdRegistry's bimap and constantly appear in the same methods. A value class at this boundary catches the most bugs.

```kotlin
@JvmInline value class InstanceId(val raw: Int)
@JvmInline value class ForgeCardId(val raw: Int)
@JvmInline value class GrpId(val raw: Int)
@JvmInline value class SeatId(val raw: Int)  // could be enum: Seat1, Seat2
```

**Boxing notes:** `ConcurrentHashMap<ForgeCardId, InstanceId>` boxes both. Fine — registry maps are small, not hot-path. The hot path is protobuf serialization which always needs `.raw` anyway.

**Migration surface:** `getOrAllocInstanceId` has ~40 call sites in StateMapper alone. Start with InstanceIdRegistry's public API, let the types propagate outward. Protobuf boundary is always the explicit `.raw` extraction point.

---

## 2. Sealed Enums for String Categories

### Transfer categories

Six string values used in `AppliedTransfer.category`, `inferCategory()`, `categoryFromEvents()`, and `annotationsForTransfer()`:

```
"PlayLand" | "CastSpell" | "Resolve" | "Destroy" | "Exile" | "ZoneTransfer"
```

A typo is a silent bug — the `when` in `annotationsForTransfer` simply skips the branch.

```kotlin
enum class TransferCategory {
    PlayLand, CastSpell, Resolve, Destroy, Exile, ZoneTransfer;
}
```

**Files touched:** StateMapper.kt (inferCategory, annotationsForTransfer, AppliedTransfer), AnnotationBuilder.kt (categoryFromEvents).

### Mulligan bridge phases

`MulliganBridge.pendingPhase` is `String?` with values `"waiting_keep"`, `"waiting_tuck"`, `null`.
Checked by string comparison in GameBridge.kt:300.

```kotlin
enum class MulliganPhase { WaitingKeep, WaitingTuck }
// pendingPhase: MulliganPhase? = null
```

---

## 3. Named Return Types Instead of Pair

### reallocInstanceId → IdReallocation

```kotlin
// Current:
fun realloc(forgeCardId: Int): Pair<Int, Int>  // (oldId, newId) — which is which?

// Better:
data class IdReallocation(val oldInstanceId: InstanceId, val newInstanceId: InstanceId)
```

Destructuring `val (origId, newId) = ...` still works, but field names are visible in IDE hints and documentation.

### getCounters → MessageCounters

```kotlin
// Current (NexusGamePlayback):
fun getCounters(): Pair<Int, Int>  // (msgId, gsId) — easy to swap

// Better:
data class MessageCounters(val nextMsgId: Int, val nextGameStateId: Int)
```

---

## 4. Zone ID as Enum

The 17 zone constants (18-38) in StateMapper are `private const val Int`s. They're used in `inferCategory`, `annotationsForTransfer`, `addPlayerZones`, and throughout buildFromGame.

```kotlin
enum class ArenaZone(val id: Int) {
    RevealedP1(18), RevealedP2(19),
    Suppressed(24), Pending(25), Command(26),
    Stack(27), Battlefield(28), Exile(29), Limbo(30),
    P1Hand(31), P1Library(32), P1Graveyard(33), P1Sideboard(34),
    P2Hand(35), P2Library(36), P2Graveyard(37), P2Sideboard(38);

    companion object {
        fun handFor(seatId: SeatId) = if (seatId.raw == 1) P1Hand else P2Hand
        fun libraryFor(seatId: SeatId) = if (seatId.raw == 1) P1Library else P2Library
        fun graveyardFor(seatId: SeatId) = if (seatId.raw == 1) P1Graveyard else P2Graveyard
    }
}
```

**Bonus:** `handFor(seatId)` eliminates the scattered `if (seatId == 1) ZONE_P1_HAND else ZONE_P2_HAND` pattern.

---

## 5. Lifecycle State as Sealed Class

GameBridge has four nullable fields that are always `null` before `start()` and always non-null after:

```kotlin
private var game: Game? = null
private var loopController: GameLoopController? = null
var playback: NexusGamePlayback? = null
var eventCollector: GameEventCollector? = null
```

Every access requires `?.` or `!!`. The type system can't tell you "this is guaranteed non-null after start()."

```kotlin
sealed class BridgeLifecycle {
    data object NotStarted : BridgeLifecycle()
    data class Running(
        val game: Game,
        val loopController: GameLoopController,
        val playback: NexusGamePlayback,
        val eventCollector: GameEventCollector,
    ) : BridgeLifecycle()
}

private var lifecycle: BridgeLifecycle = BridgeLifecycle.NotStarted
```

**Tradeoff:** Makes `getGame()` slightly more verbose (`(lifecycle as? Running)?.game`). But it eliminates an entire class of "accessed before start" bugs and makes shutdown idempotent.

---

## 6. Interactive Prompt State Machine

MatchSession tracks prompt state with independent booleans + lists:

```kotlin
private var inInteractivePrompt = false
private var pendingLegalAttackers: List<Int> = emptyList()
```

These must be synchronized (can't be `inInteractivePrompt = true` with empty attackers for an attacker prompt). A sealed class enforces this:

```kotlin
sealed class PromptState {
    data object Idle : PromptState()
    data class DeclareAttackers(val legalAttackerIds: List<InstanceId>) : PromptState()
    data class DeclareBlockers(val legalBlockerIds: List<InstanceId>) : PromptState()
    data class SelectTargets(val req: SelectTargetsReq) : PromptState()
}
```

---

## 7. Exhaustive Proto Enum Handling

MatchSession switches on `ActionType` with an `else` clause:

```kotlin
when (action.actionType) {
    ActionType.Pass -> { ... }
    ActionType.Play_add3 -> { ... }
    ActionType.Cast -> { ... }
    ...
    else -> { ... }
}
```

New action types added to the proto are silently swallowed. Map proto enum → domain sealed class at the boundary, then `when` becomes exhaustive:

```kotlin
sealed class PlayerActionType {
    data object Pass : PlayerActionType()
    data class PlayLand(val instanceId: InstanceId) : PlayerActionType()
    data class CastSpell(val instanceId: InstanceId) : PlayerActionType()
    data class ActivateAbility(val instanceId: InstanceId) : PlayerActionType()
    data class ActivateMana(val instanceId: InstanceId) : PlayerActionType()
    data object Unknown : PlayerActionType()
}
```

Compiler warns on missing branches. `Unknown` handles genuinely new types without silent drops.

---

## 8. CardDb Lookup Result

`CardDb.lookupByName()` returns `Int?` where `null` means "not in Arena DB." Call sites do `?: FALLBACK_GRPID` 15+ times. The semantics of "not found" vs "found with ID 0" are conflated.

```kotlin
sealed class CardLookup {
    data class Found(val grpId: GrpId) : CardLookup()
    data object NotInArenaDb : CardLookup()
}
```

Or simpler — just return `GrpId?` once value classes exist. The `FALLBACK_GRPID` constant moves to a `GrpId.FALLBACK` companion.

---

## Priority Ranking

| # | Opportunity | Catches | Migration Cost |
|---|------------|---------|---------------|
| 1 | **TransferCategory enum** | Silent typo bugs in annotation building | Small — 6 files, <30 lines |
| 2 | **MulliganPhase enum** | String comparison bugs in bridge state | Tiny — 2 files |
| 3 | **IdReallocation named type** | Swapped Pair destructuring | Tiny — 1 type + 3 call sites |
| 4 | **InstanceId / ForgeCardId value classes** | Wrong-ID-domain bugs | Large — ~80 call sites, but mechanical |
| 5 | **ArenaZone enum** | Magic int confusion, seat→zone mapping | Medium — replaces 17 constants + scattered ifs |
| 6 | **BridgeLifecycle sealed class** | Null access before start | Medium — changes GameBridge internals |
| 7 | **PromptState sealed class** | Inconsistent boolean + list state | Medium — changes MatchSession flow |
| 8 | **GrpId value class** | Confused with instanceId at proto boundary | Large — pervasive in StateMapper |
| 9 | **Exhaustive action type mapping** | Silent new-action-type drops | Medium — new domain type + boundary mapper |

**Recommended approach:** Pick items 1-3 when touching those files anyway — they're tiny, self-contained, and immediately useful. Items 4-5 are the big wins but need a dedicated pass. Items 6-9 are when-you-need-it improvements.
