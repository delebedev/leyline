# ADR-003: Single-Owner MessageCounter

## Status
Accepted

## Context

The GRE protocol requires strictly monotonic `gameStateId` (gsId) and `msgId` on every outbound message. Two independent counter copies exist today:

- **Session thread:** `SessionOps.gameStateId` / `SessionOps.msgIdCounter` (plain `Int`)
- **Engine thread:** `NexusGamePlayback.gsIdCounter` / `NexusGamePlayback.msgIdCounter` (`AtomicInteger`)

Bidirectional sync via `seedCounters()` (17 callsites) and `getCounters()` (4 sync points) uses `max()` to prevent backwards clobbering. This prevents the obvious bug but cannot prevent both sides independently advancing to the same number — a race that produces duplicate gsIds.

Additional fragility: 5 inline `counter++` sites in CombatHandler/TargetingHandler bypass the `BundleResult`-based assignment pattern. The AI diff re-numbering loop in `onMulliganKeep` manually reconstructs what BundleBuilder does structurally.

## Decision

Replace both counter copies with a single shared `MessageCounter`:

```kotlin
class MessageCounter(initialGsId: Int = 3, initialMsgId: Int = 0) {
    private val gsId = AtomicInteger(initialGsId)
    private val msgId = AtomicInteger(initialMsgId)

    fun nextGsId(): Int = gsId.incrementAndGet()
    fun nextMsgId(): Int = msgId.incrementAndGet()
    fun currentGsId(): Int = gsId.get()
    fun currentMsgId(): Int = msgId.get()
}
```

Only operation: atomic increment-and-get. No setters. Both threads call the same instance.

## Consequences

**Removed:**
- `seedCounters()` — all 17 callsites
- `getCounters()` — all 4 sync points
- `max()` hack in `NexusGamePlayback`
- `SessionOps.gameStateId` / `msgIdCounter` mutable vars
- `BundleResult.nextMsgId` / `nextGsId` fields
- AI diff re-numbering loop in `onMulliganKeep`
- `NexusGamePlaybackCounterTest` seeding tests (no seeding exists)

**Changed:**
- `BundleBuilder` methods take `MessageCounter` instead of `(msgId, gsId)` pairs. Calls `counter.next*()` internally — no longer pure, but deterministic given counter state.
- Tests create a fresh `MessageCounter()` per test instead of passing literal ints.

**Invariant:** Duplicate gsIds become structurally impossible. `ValidatingMessageSink` continues to enforce monotonicity as a safety net.
