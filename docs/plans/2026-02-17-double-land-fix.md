# Double Land Fix — Root Cause & Methodology

## Bug
Playing a land showed two copies on the MTGA client battlefield.

## Root Cause
Three field-level differences vs real server in the PlayLand GameStateMessage Diff:

1. **`prevGameStateId = 0`** — snapshot was built with gsId=0; client couldn't chain diffs correctly. Fixed by passing actual gsId through `snapshotState()`.

2. **Annotation order wrong** — we sent ObjectIdChanged → UserActionTaken → ZoneTransfer. Real server sends ObjectIdChanged → ZoneTransfer → UserActionTaken. Client animation pipeline is order-sensitive.

3. **ZoneTransfer `affectorId` = actingSeat** — real server sets 0 for PlayLand/CastSpell zone transfers. Only UserActionTaken carries the acting seat.

4. **Mirror included Private Limbo gameObject** — `mirrorToFamiliar` sent the same objects to seat 2 (Familiar) as seat 1 (Player). Real server strips Private objects not visible to the target seat. Fixed by filtering in `mirrorToFamiliar()`.

## Methodology: Comparing Against Server Recordings

This debugging approach was effective and should be reused:

1. **Enable proto dumps**: `ARENA_DUMP=1 just serve` — writes `.txt` (text format) + `.bin` (binary) to `/tmp/arena-dump/` for every outbound message.

2. **Capture the action**: Play one land (or cast one spell). Files come in pairs (seat 1 + mirror seat 2).

3. **Find the real server equivalent**: Search `decoded.jsonl` recordings for the same annotation category:
   ```python
   # Find PlayLand messages in recording
   python3 -c "
   import json
   for line in open('decoded.jsonl'):
       msg = json.loads(line)
       if 'PlayLand' in json.dumps(msg):
           print(json.dumps(msg, indent=2)[:3000])
           break
   "
   ```

4. **Structured comparison**: Extract zones, objects, annotations from both. Key fields to compare:
   - `prevGameStateId` (must chain correctly)
   - Annotation order and annotation field values (`affectorId`, `affectedIds`, `details`)
   - Object count per seat (owner gets Limbo Private, viewer doesn't)
   - `diffDeletedInstanceIds` (we don't use yet, real server uses for transient abilities)

5. **Owner vs viewer**: Real server sends different object sets to different seats. Owner gets Limbo gameObject (Private, viewers=[owner]), viewer gets only Public objects. Our mirror must match this.

## Files Changed
- `StateMapper.kt` — annotation order, prevGameStateId via snapshotState, zone transfer affectorId
- `MatchSession.kt` — mirrorToFamiliar strips Private objects not visible to mirror seat
- `GameBridge.kt` — snapshotState accepts gsId parameter (already had it, now callers pass it)
