---
name: card-spec
description: Build a card spec — Forge script decomposition, mechanic catalog mapping, trace from recordings, gap analysis for leyline implementation
---

## What I do

Build a complete card spec for a single MTG card, documenting everything needed to make it work in leyline. The spec is the unit of work for implementing card support.

## When to use me

- "write a card spec for Drake Hatcher"
- "spec out Kiora"
- "what do we need to make Think Twice work"
- Before implementing a new card or mechanic

## Input

- Card name (required)
- Session hint (optional — which recording session to trace)

## Process

### 1. Identity

Look up the card:

```bash
just card-grp "<name>"           # → grpId
just card-script "<name>"        # → Forge script path
```

Read the Forge script. Extract: name, grpId, set, type, costs (including alternate costs like flashback/kicker).

### 2. Mechanics decomposition

From the Forge script, list every mechanic the card exercises. Map each to catalog status:

```bash
# Read catalog for current status
cat docs/catalog.yaml
```

Use the Forge DSL keywords:
- `K:` = keyword (Flashback, Prowess, Vigilance, etc.)
- `T:` = triggered ability (ETB, attack trigger, damage trigger)
- `A:` = activated ability (tap, sacrifice, remove counters)
- `SVar:` = sub-abilities chained from triggers/activated
- `DB$` = effect type (Draw, Discard, Token, PutCounter, Destroy, etc.)

### 3. What it does

Plain English summary of the card's behavior. One numbered line per mode/ability.

### 4. Trace

Find sessions where this card was played:

```bash
# Search all sessions
for d in recordings/2026-*/; do
    just cards-in-session "$(basename $d)" 2>&1 | grep -i "<name>" && echo "  ^ $(basename $d)"
done
```

For the best session (seat 1 preferred — full visibility), trace the card:

```python
# Find which .bin files contain this card
import json
with open("recordings/<session>/md-frames.jsonl") as f:
    for line in f:
        d = json.loads(line)
        for o in d.get("objects", []):
            if o.get("grpId") == <GRPID>:
                print(f"gsId={d['gsId']} file={d['file']} zone={o.get('zoneId')}")
```

Build a zone transition table: gsId, instanceId changes, zones, what happened.

### 5. Annotations (raw proto)

For each **novel or gap-filling** moment in the card's lifecycle, decode the raw proto:

```bash
just proto-inspect recordings/<session>/capture/seat-1/md-payloads/<file>.bin
```

Document annotations that are:
- **New** — not previously documented (e.g. a new annotation type)
- **Different** — same annotation but with unexpected field values
- **Gap-filling** — confirms or corrects assumptions about missing mechanics

Skip annotations we already know well (ObjectIdChanged, ManaPaid, ResolutionStart/Complete) unless they behave differently for this card.

Focus on: ZoneTransfer categories, transfer detail keys, any card-specific annotations.

### 6. Key findings

Bullet list of what the trace revealed. Corrections to prior assumptions go here.

### 7. Gaps for leyline

Numbered list of what's missing or broken. Each gap should be actionable — what code needs to change.

### 8. Supporting evidence needed

Checklist of cross-references to validate. Other cards exercising the same mechanics, variance across sessions.

## Output

Write the spec to `docs/card-specs/<card-name-slug>.md`.

## Template

```markdown
# <Card Name> — Card Spec

## Identity

- **Name:** <name>
- **grpId:** <id>
- **Set:** <set code>
- **Type:** <card type>
- **Cost:** <mana cost> (alternate costs if any)
- **Forge script:** `forge/forge-gui/res/cardsfolder/<path>.txt`

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| ... | ... | ... |

## What it does

1. ...

## Trace (session <timestamp>, seat <N>)

<context — how many times cast, by whom>

### <Event 1>

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| ... | ... | ... | ... |

### Annotations (from raw proto)

**<Event> (gsId <N>):**
- `<AnnotationType>` — key details

### Key findings

- ...

## Gaps for leyline

1. ...

## Supporting evidence needed

- [ ] ...
```

## Reference

- Zone IDs: 27=Stack, 28=Battlefield, 29=Exile, 30=Limbo, 31=Hand, 32=Library, 33=Graveyard
- `docs/card-specs/think-twice.md` — canonical example
- `docs/conformance/how-we-conform.md` — conformance workflow
- `docs/playbooks/card-lookup-playbook.md` — Arena DB schema
