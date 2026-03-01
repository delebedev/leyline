# Card Lookup Playbook

How to quickly answer "what cards were involved and what happened" when
looking at recordings, golden tests, or variance reports.

## The problem

Recordings and proto messages use numeric IDs everywhere:
- **grpId** — card definition ID (same across all games)
- **instanceId** — per-game object ID (changes every game)
- **abilityId** — ability definition ID (grpId on the stack for triggered/activated abilities)

The variance report and `md-frames.jsonl` show `grp:75515` — not "Hurloon Minotaur".

## Quick lookup: grpId → card name

```bash
# Single card
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.GrpId = 75515;"
# → 75515|Hurloon Minotaur

# Multiple cards at once
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.GrpId IN (75515, 75522, 75555);"
# → 75515|Hurloon Minotaur
# → 75522|Raging Goblin
# → 75555|Mountain
```

## When grpId is NOT a card: ability on the stack

Sometimes a grpId in a `ResolutionStart` annotation or `sourceId` field
doesn't match any card. This means it's an **abilityId** — a triggered or
activated ability living on the stack.

```bash
# Check if it's an ability
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT Id, TextId, Category, SubCategory FROM Abilities WHERE Id = 169561;"
# → 169561|866164|2|10

# Find which card OWNS this ability
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.AbilityIds LIKE '%169561%';"
# → 87246|Deep-Cavern Bat

# Read the ability text
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT l.Loc FROM Abilities a
   JOIN Localizations_enUS l ON a.TextId = l.LocId
   WHERE a.Id = 169561;"
```

## Full investigation workflow

### Starting from a variance report entry

```
session=09-33-05 gsId=126 msg=52 T8 CombatDamage
    affectedIds=[341 → grp:93848] details={damage=2, type=1}
    file: 000000168_MD_S-C_MATCH_DATA.bin
```

**Step 1: Resolve card names**

```bash
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.GrpId = 93848;"
```

**Step 2: See what else happened in that GSM**

```bash
# Check md-frames.jsonl for the same gsId
python3 -c "
import json
for line in open('recordings/2026-02-28_09-33-05/md-frames.jsonl'):
    d = json.loads(line)
    if d.get('gsId') == 126 and d.get('file') == '000000168_MD_S-C_MATCH_DATA.bin':
        for a in d.get('annotations', []):
            print('  ann:', a.get('types'), 'affected:', a.get('affectedIds'), a.get('details'))
        for o in d.get('objects', []):
            print(f\"  obj id={o['instanceId']} grp={o['grpId']} zone={o.get('zoneId')}\")
        break
"
```

**Step 3: Inspect the raw proto if needed**

```bash
just proto-inspect recordings/2026-02-28_09-33-05/capture/payloads/000000168_MD_S-C_MATCH_DATA.bin
```

**Step 4: Trace a card across the game**

```bash
just proto-trace 341 recordings/2026-02-28_09-33-05/capture/payloads
```

### Starting from a message type (e.g. SelectNreq)

**Step 1: Find instances in the index**

```bash
python3 -c "
import json
for line in open('recordings/2026-03-01_00-18-46/md-frames.jsonl'):
    d = json.loads(line)
    if d.get('greType') == 'SelectNreq':
        print(json.dumps(d, indent=2))
"
```

**Step 2: Inspect the raw proto for full field detail**

```bash
just proto-inspect recordings/.../capture/payloads/<file>.bin
```

Look for `selectNReq.ids` (the choices), `selectNReq.sourceId` (what triggered it),
and `prompt.parameters` with `CardId` values.

**Step 3: Resolve all IDs**

The `sourceId` in a request is often an ability on the stack, not a card.
If the grpId from `ResolutionStart` doesn't resolve as a card, check abilities:

```bash
# grpId from ResolutionStart annotation → not a card? → check abilities
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.AbilityIds LIKE '%169561%';"
# → Deep-Cavern Bat
```

**Step 4: Resolve the choice options**

The `ids` in `SelectNreq` are instanceIds. Find their grpIds from the
GSM objects at the same gsId, then look up names:

```bash
# Find objects in the same GSM
python3 -c "
import json
for line in open('recordings/.../md-frames.jsonl'):
    d = json.loads(line)
    if d.get('gsId') == 51 and d.get('file') == '000000943_MD_S-C_MATCH_DATA.bin':
        for o in d.get('objects', []):
            if o['instanceId'] in (229, 232):
                print(f\"id={o['instanceId']} grp={o['grpId']}\")
"
# → id=229 grp=75515
# → id=232 grp=75522

# Then resolve
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.GrpId IN (75515, 75522);"
# → Hurloon Minotaur, Raging Goblin
```

## Zone IDs

| ID | Zone |
|---|---|
| 27 | Stack |
| 28 | Battlefield |
| 29 | Exile |
| 30 | Limbo (temporary) |
| 31 | Hand |
| 32 | Library |
| 33 | Graveyard |
| 34 | Revealed |
| 35 | Library (opponent/hidden) |
| 37 | Command |

## Cheat sheet

| Question | Command |
|---|---|
| Card name from grpId | `sqlite3 ...mtga "SELECT l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId=l.LocId WHERE c.GrpId=X"` |
| Card from abilityId | `sqlite3 ...mtga "SELECT l.Loc FROM Cards c JOIN Localizations_enUS l ON c.TitleId=l.LocId WHERE c.AbilityIds LIKE '%X%'"` |
| Ability text | `sqlite3 ...mtga "SELECT l.Loc FROM Abilities a JOIN Localizations_enUS l ON a.TextId=l.LocId WHERE a.Id=X"` |
| All cards in a recording | `just rec-actions <session>` |
| Who played a card | `just rec-who-played <session> --card <name>` |
| Trace instanceId | `just proto-trace <id> recordings/<session>/capture/payloads` |
| Raw proto dump | `just proto-inspect recordings/<session>/capture/payloads/<file>.bin` |
| Annotations at a gsId | Check `md-frames.jsonl` (python one-liner above) |

## Example: Deep-Cavern Bat SelectNreq

Full trace of the SelectNreq from session `2026-03-01_00-18-46`:

1. **grpId 169561** in `ResolutionStart` → not a card → ability lookup → **Deep-Cavern Bat** (grpId 87246)
2. Deep-Cavern Bat ETB: "look at opponent's hand, choose a nonland card to exile"
3. `SelectNreq` with `ids: [229, 232]` → instanceId lookup → grpIds 75515, 75522
4. Card names: **Hurloon Minotaur** and **Raging Goblin** — the two nonland cards in opponent's hand
5. Player picks one to exile (minSel=1, maxSel=1, context=Resolution)
