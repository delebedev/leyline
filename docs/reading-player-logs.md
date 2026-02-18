# Reading Client Player.log

Quick-reference for extracting game state and debugging annotation errors from the client's log.
These are ad-hoc investigative scripts, not `just` targets — they require editing (e.g. TARGET_GSID) per use.

**Location:** `~/Library/Logs/Wizards of the Coast/MTGA/Player.log` (macOS)

## Structure

The log mixes plain-text Unity engine output with JSON blobs on single (very long) lines. Game-relevant data is in `greToClientEvent` JSON objects.

## Key searches

### Find all game state messages with zone/object summaries

```bash
python3 -c "
import json
with open('$HOME/Library/Logs/Wizards of the Coast/MTGA/Player.log') as f:
    lines = f.readlines()
for i, line in enumerate(lines):
    if 'gameStateMessage' not in line or 'gameStateId' not in line:
        continue
    start = line.find('{')
    if start < 0: continue
    try:
        data = json.loads(line[start:])
    except: continue
    for m in data.get('greToClientEvent',{}).get('greToClientMessages',[]):
        gsm = m.get('gameStateMessage',{})
        if not gsm: continue
        gsid = gsm.get('gameStateId','?')
        gs_type = gsm.get('type','?')
        ti = gsm.get('turnInfo',{})
        phase = ti.get('phase','?')
        step = ti.get('step','?')
        zones = gsm.get('zones',[])
        objs = gsm.get('gameObjects',[])
        bf = [iid for z in zones if z.get('type')=='ZoneType_Battlefield' for iid in z.get('objectInstanceIds',[])]
        stack = [iid for z in zones if z.get('type')=='ZoneType_Stack' for iid in z.get('objectInstanceIds',[])]
        print(f'L{i+1}: gsId={gsid} {gs_type} {phase}/{step} zones={len(zones)} objs={len(objs)} BF={bf} Stack={stack}')
"
```

### Find client-side annotation parsing errors

```bash
grep -n "GreInterface Notices\|Exception while parsing annotation" \
  ~/Library/Logs/Wizards\ of\ the\ Coast/MTGA/Player.log
```

Common errors and what causes them:

| Error message | Annotation type | Root cause |
|---|---|---|
| "Annotation contains no details" | TappedUntappedPermanent | Missing `details: {tapped: 1}` |
| "Annotation does not contain affector id" | ResolutionStart | Missing `setAffectorId(instanceId)` |

### Extract full annotation JSON for a specific gsId

```bash
python3 -c "
import json
TARGET_GSID = 10  # change this
with open('$HOME/Library/Logs/Wizards of the Coast/MTGA/Player.log') as f:
    lines = f.readlines()
for line in lines:
    if 'gameStateMessage' not in line: continue
    start = line.find('{')
    if start < 0: continue
    try: data = json.loads(line[start:])
    except: continue
    for m in data.get('greToClientEvent',{}).get('greToClientMessages',[]):
        gsm = m.get('gameStateMessage',{})
        if gsm.get('gameStateId') == TARGET_GSID:
            for a in gsm.get('annotations',[]):
                print(json.dumps(a, indent=2))
                print('---')
"
```

### Extract full zones + objects for a specific gsId

Same pattern but print `gsm.get('zones')` and `gsm.get('gameObjects')`.

### Find all GRE message types (not just game state)

```bash
grep -oP '"type":"GREMessageType_\w+"' \
  ~/Library/Logs/Wizards\ of\ the\ Coast/MTGA/Player.log | sort | uniq -c | sort -rn
```

## Annotation field requirements (from client source)

These fields are **required** by the client's parsers (throws ArgumentException if missing):

| Annotation type | Required fields |
|---|---|
| ResolutionStart | `affectorId` (non-zero), `details.grpid` |
| ResolutionComplete | `affectorId` (non-zero), `details.grpid` |
| TappedUntappedPermanent | `affectorId` (non-zero), `details` (non-empty, needs `tapped` key) |
| UserActionTaken | `affectorId` (seat id), `details.actionType`, `details.abilityGrpId` |
| ZoneTransfer | `details.zone_src`, `details.zone_dest`, `details.category` |

Fields that are *optional* (client handles gracefully):
- ManaPaid: `affectorId`, `details.id`, `details.color`
- AbilityInstanceCreated: `affectorId`, `details.source_zone`
- ObjectIdChanged: always works with just `affectedIds` + details

## Cross-referencing with debug API

Our debug server on `:8090` captures the same messages server-side:

```bash
# All messages (ring buffer)
curl -s http://localhost:8090/api/messages | python3 -m json.tool

# Game state timeline
curl -s http://localhost:8090/api/game-states | python3 -m json.tool

# Diff between two states
curl -s 'http://localhost:8090/api/state-diff?from=6&to=10'

# Instance history
curl -s 'http://localhost:8090/api/instance-history?id=220'
```

Compare debug API output (what we sent) against Player.log (what client received) to isolate serialization vs logic issues.

## Tips

- Player.log is overwritten each launch. Copy it immediately after reproducing a bug.
- JSON lines are very long (10K+ chars). Use `python3 -m json.tool` or `jq` for readability.
- The log includes both seat 1 and seat 2 messages (the client receives both via the Familiar connection).
- `[UnityCrossThreadLogger]` prefix marks cross-thread dispatched messages — these are the game-relevant ones.
