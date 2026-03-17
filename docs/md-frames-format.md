# MD Frames JSONL Format

Schema for `md-frames.jsonl` — one JSON object per GRE message, both directions.

## Common fields

| Field | Description |
|-------|-------------|
| `index` | Sequential message number |
| `file` | Source `.bin` filename |
| `dir` | `"S-C"` (server→client) or `"C-S"` (client→server) |
| `greType` | Message type name |
| `gsId` | Game state ID |

## S→C fields (server→client)

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

## C→S fields (client→server)

| Field | Description |
|-------|-------------|
| `clientType` | ClientMessageType name |
| `clientAttackers` | DeclareAttackersResp: selected attacker instanceIds, autoDeclare |
| `clientBlockers` | DeclareBlockersResp: blocker→attacker assignments |
| `clientAction` | PerformActionResp: action type, instanceId, grpId |
| `clientTargets` | SelectTargetsResp: targetIdx, target instanceIds |

## Annotation shape

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

## Object shape

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
