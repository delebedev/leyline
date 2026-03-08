# Surveil Zone Transfer — Missing Diff Pattern

**Issue:** After surveil GroupResp, the client doesn't see the card move to graveyard (or stay on top). The card identity fix (candidateRefs) is done — this is the remaining half of #66.

## What the real server sends

From proxy recording `recordings/2026-03-07_23-50-22/`, index 247 (post-GroupResp diff for "put in graveyard"):

```
gsId=178  type=Diff  prevGsId=177

objects:
  instanceId:172  grpId:93948  zoneId:30(Limbo)  visibility:Private  viewers:[1]
  instanceId:329  grpId:93948  zoneId:33(Graveyard)  visibility:Public

zones:
  zoneId:30(Limbo)   objectIds: [172, ...]
  zoneId:32(Library)  objectIds: [173..221]   ← 172 removed
  zoneId:33(Graveyard) objectIds: [329, 326]  ← 329 added

annotations:
  ObjectIdChanged   affectorId:328  affectedIds:[172]  details:{orig_id:172, new_id:329}
  ZoneTransfer      affectorId:328  affectedIds:[329]  details:{zone_src:32, zone_dest:33, category:"Surveil"}
  MultistepEffectComplete
  ResolutionComplete
  AbilityInstanceDeleted

diffDeletedInstanceIds: [328]   ← the ability on stack
```

Key pattern: **ObjectIdChanged reallocs 172→329, old goes to Limbo, new goes to Graveyard.** Both objects included in the diff. ZoneTransfer references the NEW instanceId.

## What we send

From `logs/leyline.log` at 11:25:44 (our server, same scenario):

```
gsId=29  type=Diff  zones=5  objects=2
```

Only 2 objects, 5 zones. Missing:
- `ObjectIdChanged` annotation (no instanceId realloc)
- Old object retirement to Limbo
- New object in Graveyard with fresh instanceId
- `ZoneTransfer` with `category:"Surveil"`

The `ValidatingMessageSink` catches this as `affectedId N unresolvable` — the annotation references an instanceId not present as a gameObject.

## Root cause (verified by independent subagent)

**Correction:** `detectZoneTransfers` DOES track library cards' zones even without a GameObjectInfo entry — `previousZones[cardId] = Library` IS present. The Library→Graveyard transfer IS detected.

The actual gaps:

1. **`TransferCategory.Surveil` doesn't exist.** `inferCategory(Library→Graveyard)` returns `TransferCategory.Mill` (label `"Mill"`), not `"Surveil"`. The enum in `TransferCategory.kt` has no `Surveil` variant. The client likely ignores or misrenders the zone transfer because the category string is wrong.

2. **No `GameEvent.CardSurveiled` event.** `categoryFromEvents` needs a specific event to override `Mill` → `Surveil`. Either emit `GameEvent.CardSurveiled(forgeCardId)` from `GameEventCollector`, or special-case the category in `TargetingHandler` when we know it's a surveil prompt.

3. **`objectIdChanged` builder missing `affectorId`.** Real server sets `affectorId:328` (the stack ability). Our `AnnotationBuilder.objectIdChanged(origId, newId)` has no parameter for it. Minor shape gap.

## What needs to happen

1. Add `TransferCategory.Surveil` with label `"Surveil"`
2. Wire surveil events so `categoryFromEvents` returns `Surveil` instead of `Mill`
3. Add `affectorId` param to `AnnotationBuilder.objectIdChanged`
4. Verify the diff includes both objects (old in Limbo, new in Graveyard) — may already work if detection is correct
5. Verify the `ObjectIdChanged` + `ZoneTransfer` annotation pair is emitted with correct IDs

## Verification

### Recording tools

```bash
# Decode full recording to JSONL (one line per GRE message)
just proto-decode-recording recordings/2026-03-07_23-50-22/capture/payloads

# Trace a specific instanceId across all messages
just proto-trace 172 recordings/2026-03-07_23-50-22/capture/payloads

# Accumulate state snapshots (builds running game state from diffs)
just proto-accumulate recordings/2026-03-07_23-50-22/capture/payloads
```

### Key frames in the recording

- **Index 100-103**: First surveil (keep on top, instanceId 169)
  - 100: Pre-GroupReq reveal diff — object 169 in Library, Private+viewer
  - 101: GroupReq instanceIds=[169]
  - 102: GroupResp (client sends keep)
  - 103: Post-GroupResp diff — no ZoneTransfer (card stays), ResolutionComplete

- **Index 244-247**: Second surveil (put in graveyard, instanceId 172)
  - 244: Pre-GroupReq reveal diff — object 172 in Library, Private+viewer
  - 245: GroupReq instanceIds=[172]
  - 246: GroupResp (client sends graveyard)
  - 247: Post-GroupResp diff — ObjectIdChanged 172→329, ZoneTransfer Library→Graveyard

### Comparing our output

Run surveil puzzle, capture logs, compare our diff at the same point:
```bash
just serve-puzzle matchdoor/src/test/resources/puzzles/surveil-etb.pzl
# play through surveil, choose graveyard
grep "StateMapper\|buildFromGame\|ZoneTransfer\|ObjectIdChanged" logs/leyline.log
```

## Files involved

- `matchdoor/src/main/kotlin/leyline/game/TransferCategory.kt` — **needs `Surveil` variant**
- `matchdoor/src/main/kotlin/leyline/game/StateMapper.kt` — diff pipeline, `detectZoneTransfers`
- `matchdoor/src/main/kotlin/leyline/game/AnnotationBuilder.kt` — annotation construction, `objectIdChanged` needs `affectorId`
- `matchdoor/src/main/kotlin/leyline/game/GameEventCollector.kt` — may need `CardSurveiled` event
- `matchdoor/src/main/kotlin/leyline/game/GameEvent.kt` — sealed variant for surveil
- `matchdoor/src/main/kotlin/leyline/match/TargetingHandler.kt` — `onGroupResp`, `sendGroupReqForSurveilScry`
- `matchdoor/src/main/kotlin/leyline/game/InstanceIdRegistry.kt` — instanceId realloc

## Verification notes

See `docs/plans/2026-03-08-surveil-zone-transfer-verification.md` for independent subagent verification of these claims against the recording data.
