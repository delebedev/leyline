# Dissecting Game Recordings

Quick-reference for turning a raw recording session into a readable game timeline.

## Prerequisites

- Recording session dir under `recordings/` (created automatically by `just serve`)
- `just proto-decode-recording` target (decodes MD binary payloads → JSONL)

## Step 1: Decode

```bash
# Auto-decoded on server shutdown (writes md-frames.jsonl to session root).
# If missing (server killed hard), decode manually:
just proto-decode-recording recordings/<session>
# Or to a specific path:
just proto-decode-recording recordings/<session> /tmp/decoded.jsonl
```

Output: one JSON object per message, both directions:
- **S→C**: GRE messages (GameStateMessage, DeclareAttackersReq, etc.)
- **C→S**: Client responses (DeclareAttackersResp, SubmitBlockersReq, PerformActionResp, etc.)

## Step 2: Message type overview

```bash
REC=recordings/<session>/md-frames.jsonl
python3 -c "
import json
from collections import Counter
types = Counter()
with open('$REC') as f:
    for line in f:
        types[json.loads(line).get('greType','')] += 1
for t, c in types.most_common():
    print(f'  {c:>4}  {t}')
"
```

## Step 3: Annotation type overview

```bash
python3 -c "
import json
from collections import Counter
atypes = Counter()
with open('$REC') as f:
    for line in f:
        for ann in json.loads(line).get('annotations', []):
            for t in ann.get('types', []):
                atypes[t] += 1
for t, c in atypes.most_common():
    print(f'  {c:>4}  {t}')
"
```

## Step 4: Full game timeline

Builds instanceId→card info map from `objects`, then prints key game events.

```bash
python3 -c "
import json

with open('$REC') as f:
    lines = [json.loads(l) for l in f]

# Build instanceId -> card info from gameObjects
cards = {}  # instanceId -> {grpId, label}
for obj in lines:
    for go in obj.get('objects', []):
        iid = go.get('instanceId', 0)
        if not iid: continue
        grp = go.get('grpId', 0)
        parts = go.get('superTypes', []) + go.get('cardTypes', []) + go.get('subtypes', [])
        cards[iid] = {'grpId': grp, 'label': ' '.join(parts) or str(grp)}

def tag(iid):
    c = cards.get(iid, {})
    return f'{iid}({c.get(\"label\", \"?\")})'

# Key event categories to show
KEY_CATS = {'CastSpell','Resolve','PlayLand','Draw','Destroy','Exile',
            'Return','Sacrifice','Discard','Bounce','Countered','Mill'}
KEY_ANN_TYPES = {'AddAttachment','RemoveAttachment','TokenCreated','TokenDeleted',
                 'DamageDealt','ModifiedLife','CounterAdded','LayeredEffectCreated'}

for obj in lines:
    gsId = obj.get('gsId', '')
    gt = obj.get('greType', '')
    ti = obj.get('turnInfo', {})
    turn = ti.get('turnNumber', '')

    # Non-GSM prompt messages
    d = obj.get('dir', 'S-C')

    # C→S client messages
    if d == 'C-S':
        ca = obj.get('clientAttackers')
        cb = obj.get('clientBlockers')
        ct = obj.get('clientTargets')
        extra = ''
        if ca: extra = f' sel={ca.get(\"selectedAttackers\",[])}'
        if cb: extra = f' assign={cb}'
        if ct: extra = f' targets={ct.get(\"targetInstanceIds\",[])}'
        print(f'  gs={gsId:>3} T{turn} C→S {gt}{extra}')
        continue

    if gt in ('DeclareAttackersReq','DeclareBlockersReq','SelectTargetsReq',
              'ActionsAvailableReq','PromptReq','IntermissionReq','MulliganReq',
              'ChooseStartingPlayerReq','GroupReq'):
        print(f'  gs={gsId:>3} T{turn} {gt}')

    for ann in obj.get('annotations', []):
        types = ann.get('types', [])
        details = ann.get('details', {})
        cat = details.get('category', '')
        aids = ann.get('affectedIds', [])

        if cat in KEY_CATS:
            descs = ' '.join(tag(a) for a in aids)
            print(f'  gs={gsId:>3} T{turn} {cat:<12} {descs}')
        elif any(t in KEY_ANN_TYPES for t in types):
            descs = ' '.join(tag(a) for a in aids)
            print(f'  gs={gsId:>3} T{turn} {types[0]:<20} {descs}')
"
```

## Step 5: Inspect a specific gsId

```bash
GSID=168
python3 -c "
import json
with open('$REC') as f:
    for line in f:
        obj = json.loads(line)
        if obj.get('gsId') == $GSID:
            print(json.dumps(obj, indent=2))
            break
"
```

## Step 6: Transfer category breakdown

```bash
python3 -c "
import json
from collections import Counter
cats = Counter()
with open('$REC') as f:
    for line in f:
        for ann in json.loads(line).get('annotations', []):
            cat = ann.get('details', {}).get('category', '')
            if cat: cats[cat] += 1
for t, c in cats.most_common():
    print(f'  {c:>4}  {t}')
"
```

## Step 7: Cross-reference with catalog

Compare annotation types and categories against `docs/catalog.yaml` to find:
- Mechanics exercised in this game that we handle
- Gaps: annotation types present in recording but missing/broken in our pipeline
- Categories we emit vs what real server would emit

## JSONL format reference

Each line is a JSON object. Both directions included.

### Common fields

| Field | Description |
|-------|-------------|
| `index` | Sequential message number |
| `file` | Source `.bin` filename |
| `dir` | `"S-C"` (server→client) or `"C-S"` (client→server) |
| `greType` | Message type name |
| `gsId` | Game state ID |

### S→C fields (server→client)

| Field | Description |
|-------|-------------|
| `msgId` | Message sequence number |
| `gsmType` | `GameStateType_Diff` or `GameStateType_Full` |
| `objects` | Game objects in this state (cards/permanents) |
| `zones` | Zone definitions with `objectInstanceIds` |
| `annotations` | List of annotations (zone transfers, taps, damage, etc.) |
| `turnInfo` | Phase, step, turn number, active player |
| `players` | Player state (life, mana, timers) |
| `diffDeletedInstanceIds` | InstanceIds removed in this diff |
| `declareAttackers` | Attacker details (instanceIds, mustAttack, canSubmit) |
| `declareBlockers` | Blocker details (instanceId, attackerInstanceIds, maxAttackers) |

### C→S fields (client→server)

| Field | Description |
|-------|-------------|
| `clientType` | ClientMessageType name |
| `clientAttackers` | DeclareAttackersResp: selected attacker instanceIds, autoDeclare |
| `clientBlockers` | DeclareBlockersResp: blocker→attacker assignments |
| `clientAction` | PerformActionResp: action type, instanceId, grpId |
| `clientTargets` | SelectTargetsResp: targetIdx, target instanceIds |

### Annotation shape

```json
{
  "id": 42,
  "types": ["ZoneTransfer", "ObjectIdChanged"],
  "affectorId": 280,
  "affectedIds": [280, 281],
  "details": {
    "zone_src": 27,
    "zone_dest": 28,
    "category": "Resolve"
  }
}
```

### Object shape

```json
{
  "instanceId": 280,
  "grpId": 75485,
  "zoneId": 28,
  "type": "Card",
  "owner": 1,
  "controller": 1,
  "cardTypes": ["Creature"],
  "subtypes": ["Bird"],
  "power": 1,
  "toughness": 1
}
```
