# Proto Debugging Cookbook

Checklist for diagnosing proto conformance failures — annotation shape, ID lifecycle, gsId chain.

## Annotation ordering

Arena client requires `ObjectIdChanged` before `ZoneTransfer` in the same GSM. If reversed, the client can't resolve the new instanceId and drops the zone-transfer animation.

Reference: from client decompilation (zone transfer pipeline)

## Category codes

`ZoneTransfer` annotation's `category` detail must match Arena's reason enum string exactly (`"PlayLand"`, `"CastSpell"`, `"Resolve"`, `"Destroy"`, etc.). Wrong category → client plays wrong animation or ignores the transfer.

Reference: from client decompilation (zone transfer reason codes)

Check `TransferCategory.label` values against the spec. `AnnotationBuilder.categoryFromEvents()` picks the most-specific event; if it falls through to `StateMapper.inferCategory()`, the zone-pair heuristic may pick a wrong default (e.g. BF→GY defaults to `Destroy` when it should be `Sacrifice`).

## instanceId lifecycle

- Zone transfers realloc instanceIds — `InstanceIdRegistry.realloc()` creates a new ID, old ID retires to Limbo zone.
- **Exception:** Stack→Battlefield resolve keeps the same instanceId (no realloc).
- Old ID must appear in Limbo zone's `objectInstanceIds` but NOT have a `GameObjectInfo` (real server omits it).
- `ObjectIdChanged` annotation bridges old→new for the client's object tracker.

Common failure: annotation references the old instanceId after realloc, or Limbo is missing the retired ID.

## gsId chain

- `gameStateId` must be strictly monotonic across all GSMs sent to a client.
- `prevGameStateId` must reference a gsId the client has already seen.
- Gaps cause the client to request resync (or silently drop state).
- `BundleBuilder` methods return `nextGsId` — always propagate it. Don't reuse or skip.

Common failure: two code paths increment `gameStateId` independently (e.g. `postAction` + `aiActionDiff` racing). Check `MatchSession.gameStateId` and `MessageCounter` ownership.

## Action instanceId consistency

Every `instanceId` in `ActionsAvailableReq` must exist in the client's accumulated `objects` map. After zone transfers realloc IDs, actions must reference the **new** instanceId.

Verify with `ClientAccumulator`:
```kotlin
val missing = accumulator.actionInstanceIdsMissingFromObjects()
assert(missing.isEmpty()) { "Actions reference unknown instanceIds: $missing" }
```

Also check zone→object consistency:
```kotlin
val zoneMissing = accumulator.zoneObjectsMissingFromObjects()
assert(zoneMissing.isEmpty()) { "Zones reference unknown objects: $zoneMissing" }
```

## Detail key types

Arena is strict about `KeyValuePairInfo` types. Common mistakes:
- `zone_src` / `zone_dest` → `Int32` (not `Uint32`)
- `category` → `String` type with `valueString`
- `grpid` → `Uint32`
- `damage` → `Uint32`
- `delta` (life) → `Int32` (signed, can be negative)

Check `AnnotationBuilder` helper methods: `int32Detail`, `uint32Detail`, `typedStringDetail`.

## Diff vs Full

- First GSM after connect must be `Full` (all zones, all visible objects).
- Subsequent GSMs can be `Diff` (only changed zones/objects).
- `Diff` must include `diffDeletedInstanceIds` for objects that disappeared (not just moved to Limbo).
- Missing deletions → stale ghost objects in client state.

## Quick triage flow

1. Run failing test with `just test-one FailingTest`
2. Check assertion — usually annotation shape, missing object, or gsId gap
3. Add `ValidatingMessageSink` if not already present (catches invariant violations automatically)
4. Check `ClientAccumulator` state at failure point
5. Enable debug logging: `AnnotationBuilder` and `StateMapper` log at DEBUG level
6. Compare against real server capture if available (`recording` group tests)
