# Annotation Investigation Playbook

How to trace an unknown annotation type from the variance report to a full
understanding of what it does, which cards trigger it, and how Arena models it.

Complements `card-lookup-playbook.md` (ID resolution) and
`annotation-variance.md` (running the variance tool).

## The workflow

```
Variance report entry
  │
  ▼
1. Resolve grpIds → card names (sqlite bulk lookup)
  │
  ▼
2. Read Forge card script → understand what the card does in-game
  │
  ▼
3. md-frames.jsonl at gsId → all annotations in that GSM (context)
  │
  ▼
4. proto-inspect → raw proto fields md-frames doesn't capture
   (affectorId, persistentAnnotations, diffDeletedPersistentAnnotationIds)
  │
  ▼
5. Cross-GSM trace → same annotation type at later gsIds (lifecycle)
  │
  ▼
6. Ability lookup → for grpIds that aren't cards (abilityIds on stack)
  │
  ▼
7. Conclusion: what triggers it, what the detail values mean,
   how Forge models the same mechanic
```

## Step-by-step

### 1. Resolve card names

Starting from a variance report entry:

```
## GainDesignation (8 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {DesignationType}
  Samples:   DesignationType=[19, 20]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={DesignationType=19}
```

Bulk-resolve all grpIds in the section:

```bash
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.GrpId IN (92196);"
# → 92196|Unholy Annex // Ritual Chamber
```

### 2. Read the Forge card script

```bash
find forge-gui/res/cardsfolder -iname "*unholy*annex*"
```

Then read the file. Key things to note:
- **Card type and mechanics** (Room, Enchantment, triggered abilities)
- **What it actually does** (draw, create tokens, grant designations?)
- **How Forge models it** (AlternateMode:Split, UnlockDoor triggers, SVars)

This is where assumptions get challenged. The card name alone
("Designation" annotation on "Unholy Annex") might suggest Monarch/Initiative,
but reading the script reveals it's a Room enchantment with door-unlock mechanics.

### 3. Check md-frames.jsonl for GSM context

```bash
python3 -c "
import json
for line in open('recordings/2026-03-01_00-18-46/md-frames.jsonl'):
    d = json.loads(line)
    if d.get('gsId') == 100:
        for a in d.get('annotations', []):
            print('  ann:', a.get('types'), 'affected:', a.get('affectedIds'), a.get('details'))
        for p in d.get('persistentAnnotations', []):
            print('  per:', p.get('types'), 'affected:', p.get('affectedIds'), p.get('details'))
        for o in d.get('objects', []):
            print(f\"  obj id={o['instanceId']} grp={o['grpId']} type={o.get('type')} zone={o.get('zoneId')}\")
        break
"
```

Look for:
- **Sibling annotations** in the same GSM (what else happened alongside?)
- **Objects** at that gsId (what was on the battlefield?)
- **persistentAnnotations** (does the annotation persist or is it one-shot?)

### 4. Inspect raw proto

md-frames.jsonl is a summary — it drops some fields. For full detail:

```bash
just proto-inspect recordings/2026-03-01_00-18-46/capture/payloads/000001064_MD_S-C_MATCH_DATA.bin \
  | grep -B 2 -A 15 "GainDesignation\|Designation"
```

Fields only visible in raw proto:
- **`affectorId`** — who caused this annotation (not always in md-frames)
- **`persistentAnnotations`** with full detail keys
- **`diffDeletedPersistentAnnotationIds`** — which persistent annotations were removed (lifecycle tracking)

### 5. Cross-GSM trace

Find later occurrences of the same annotation type to understand lifecycle:

```bash
python3 -c "
import json
for line in open('recordings/2026-03-01_00-18-46/md-frames.jsonl'):
    d = json.loads(line)
    for a in d.get('annotations', []) + d.get('persistentAnnotations', []):
        if 'GainDesignation' in a.get('types', []) or 'Designation' in a.get('types', []):
            print(f\"gsId={d['gsId']} {a['types']} affected={a.get('affectedIds')} details={a.get('details')}\")
"
```

Patterns to look for:
- **Accumulation** — same annotation growing across GSMs (e.g. AbilityExhausted adding abilityGrpIds)
- **Replacement** — new persistent annotation + old one in `diffDeletedPersistentAnnotationIds`
- **One-shot vs persistent** — event annotation (GainDesignation) vs state annotation (Designation)

### 6. Ability lookup for non-card grpIds

If an `affectorId` or detail value doesn't resolve as a card, it's likely an ability:

```bash
# Ability text
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT a.Id, l.Loc FROM Abilities a
   JOIN Localizations_enUS l ON a.TextId = l.LocId
   WHERE a.Id = 174405;"
# → 174405|When you unlock this door, create a 6/6 black Demon creature token with flying.

# Owning card
sqlite3 ~/Library/Application\ Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga \
  "SELECT c.GrpId, l.Loc FROM Cards c
   JOIN Localizations_enUS l ON c.TitleId = l.LocId
   WHERE c.AbilityIds LIKE '%174405%';"
```

### 7. Synthesize

Combine all findings into a conclusion:

- **What triggers it** (card mechanic, game event, state change)
- **What the detail values mean** (enum values, counters, effect IDs)
- **How Arena models it** (event vs state, persistent vs transient, which parser handles it)
- **How Forge models the same thing** (DSL keywords, game events, engine APIs)
- **Gap between them** (does Forge have the right events? do we need new NexusGameEvents?)

## Worked example: Unholy Annex // Ritual Chamber

**Starting point:** variance report shows `GainDesignation` and `Designation`
with `DesignationType=19,20` on grp:92196.

**Step 1:** grp:92196 = Unholy Annex // Ritual Chamber (a Room enchantment from Duskmourn).

**Step 2:** Forge card script shows `AlternateMode:Split` with two halves.
Left room (Unholy Annex): end-step draw + conditional life drain.
Right room (Ritual Chamber): `UnlockDoor` trigger creates a 6/6 Demon token.
No Monarch/Initiative/City's Blessing anywhere.

**Step 3:** md-frames at gsId=100 shows ResolutionStart/Complete for grp:92197
(the left room face), ZoneTransfer to battlefield, LayeredEffectCreated ×2.

**Step 4:** Raw proto reveals `affectorId` and persistent annotations:
- Transient `GainDesignation` (type=19) — the door unlock event
- Persistent `Designation` (type=19) with `affectorId=310` — the enchantment itself

**Step 5:** Later gsId=261 shows a second `GainDesignation` (type=20) from
`affectorId=350` (ability 174405 = "When you unlock this door, create a 6/6 Demon").
`diffDeletedPersistentAnnotationIds` removes the old Designation — it's a replacement,
not accumulation. The persistent Designation now has type=20.

**Step 6:** Ability 174405 = Ritual Chamber's unlock trigger.

**Conclusion:** Arena models Room door unlocks as Designations on the enchantment.
DesignationType 19 = left door (Unholy Annex), 20 = right door (Ritual Chamber).
These are NOT Monarch/Initiative — they're per-card state, not player-wide.
The `affectedIds` is the Room enchantment instanceId, not a player seat.

**Wiring implication:** to support GainDesignation/Designation, we need to handle
both Room unlocks (per-card, Forge uses `UnlockDoor` triggers) and classic
designations (per-player: Monarch, Initiative, City's Blessing — Forge uses
`GameEventPlayerDesignation`). Different source events, same annotation type.

## Red flags during investigation

| Signal | What it means |
|---|---|
| `affectedIds` is a card, not a player seat | Per-card state, not player-wide (Rooms, auras) |
| Detail values are comma-separated (`137955,138314`) | Accumulating state snapshot, not single event |
| `diffDeletedPersistentAnnotationIds` present | Annotation replaced, not added — lifecycle transition |
| grpId doesn't resolve as card or ability | Synthetic ID (layered effect IDs start at 7000+) |
| Same annotation type in both transient and persistent | Event + state pair (GainDesignation + Designation) |
| `affectorId` differs from `affectedIds` | Source ≠ target (ability on stack → permanent on battlefield) |
