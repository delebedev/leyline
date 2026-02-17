# Annotation Ordering & Diff Fixes — Root Causes & Methodology

## The Rule

**Annotation order in GameStateMessage Diffs is semantically significant.** The client animation pipeline processes annotations sequentially. Wrong order → duplicate rendering, stuck objects, missing animations.

The pattern is consistent across all zone transfer categories:
- **ZoneTransfer must come early** (position #2, right after ObjectIdChanged)
- If ZoneTransfer is late, the client sees the new instanceId in both the source and destination zones simultaneously → duplicate visual

## Bug 1: Double Land on Battlefield

Playing a land showed two copies on the battlefield.

**Root cause** — four field-level differences vs real server:

1. **`prevGameStateId = 0`** — snapshot built with gsId=0; client couldn't chain diffs. Fixed by passing actual gsId through `snapshotState()`.
2. **Annotation order** — ObjectIdChanged → UserActionTaken → ZoneTransfer. Real: ObjectIdChanged → ZoneTransfer → UserActionTaken.
3. **ZoneTransfer `affectorId` = actingSeat** — real server uses 0 for PlayLand. Only UserActionTaken carries the acting seat.
4. **Mirror included Private Limbo objects** — `mirrorToFamiliar` didn't strip Private objects. Real server omits them for non-owner seat.

## Bug 2: Duplicate Creature on Stack

Casting a creature showed two copies on the stack.

**Root cause** — annotation order + duplicate annotation:

1. **ZoneTransfer at position #8** (last) instead of #2. Same root cause as double-land.
2. **Duplicate UserActionTaken(Cast)** — we emitted two Cast annotations. Real server: one ActivateMana(actionType=4) for the mana ability, one Cast(actionType=1) for the spell.
3. **Annotation order wrong** — real server order for CastSpell:
   ```
   ObjectIdChanged → ZoneTransfer → AbilityInstanceCreated →
   TappedUntappedPermanent → UserActionTaken(ActivateMana) →
   ManaPaid → AbilityInstanceDeleted → UserActionTaken(Cast)
   ```

## Bug 2b: Resolve Order

Spell stuck mid-resolution.

**Root cause** — ResolutionComplete came before ZoneTransfer. Real server: ResolutionStart → ResolutionComplete → ZoneTransfer. Also: Resolve ZoneTransfer uses `actingSeat` as affectorId (unlike PlayLand/CastSpell which use 0).

## Reference: Real Server Annotation Orders

### PlayLand (Hand → Battlefield)
```
ObjectIdChanged(affected=[origId])
ZoneTransfer(affected=[newId], affector=0, category=PlayLand)
UserActionTaken(affected=[newId], affector=seat, actionType=3)
```

### CastSpell (Hand → Stack)
```
ObjectIdChanged(affected=[origId])
ZoneTransfer(affected=[newId], affector=0, category=CastSpell)
AbilityInstanceCreated(affected=[manaAbilityId], affector=landId, source_zone=28)
TappedUntappedPermanent(affected=[landId], affector=manaAbilityId, tapped=1)
UserActionTaken(affected=[manaAbilityId], affector=seat, actionType=4, abilityGrpId=1002)
ManaPaid(affected=[spellId], affector=landId, id=N, color=N)
AbilityInstanceDeleted(affected=[manaAbilityId], affector=landId)
UserActionTaken(affected=[spellId], affector=seat, actionType=1)
```

### Resolve (Stack → Battlefield)
```
ResolutionStart(affected=[spellId], affector=spellId, grpid=N)
ResolutionComplete(affected=[spellId], affector=spellId, grpid=N)
ZoneTransfer(affected=[spellId], affector=seat, category=Resolve)
```

## Debugging Methodology

This compare-against-recording approach was effective and should be reused:

1. **Enable proto dumps**: `ARENA_DUMP=1 just serve` — writes `.txt` + `.bin` to `/tmp/arena-dump/` per outbound message.

2. **Capture one action**: Play one land / cast one spell. Files come in pairs (seat 1 + mirror seat 2).

3. **Find real server equivalent**: Search `decoded.jsonl` for the same annotation category:
   ```bash
   python3 -c "
   import json
   for i, line in enumerate(open('decoded.jsonl')):
       msg = json.loads(line)
       if 'CastSpell' in json.dumps(msg):
           print(f'Line {i}: gsId={msg.get(\"gameStateId\", \"?\")}')
           break
   "
   ```

4. **Structured comparison** — key fields:
   - `prevGameStateId` (must chain correctly)
   - Annotation **order** (position matters!)
   - Annotation field values (`affectorId`, `affectedIds`, `details`)
   - Object count per seat (owner gets Limbo Private, viewer doesn't)
   - `update` type (SendAndRecord for owner, SendHiFi for viewer)

5. **Owner vs viewer**: Real server sends different object sets per seat. Owner gets Limbo gameObject (Private, viewers=[owner]); viewer gets only Public objects.

## Files Changed
- `StateMapper.kt` — annotation order for PlayLand/CastSpell/Resolve, prevGameStateId, affectorId
- `MatchSession.kt` — mirrorToFamiliar strips Private objects for non-owner seat
- `GameBridge.kt` — snapshotState accepts gsId parameter (callers now pass it)
- `AnnotationBuilder.kt` — added affectorId/details to ResolutionStart/Complete/TappedUntapped
