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

For each mechanic, check if the Forge engine fires a corresponding game event:

```bash
grep -rn "class GameEvent" forge/forge-game/src/main/java/forge/game/event/ --include="*.java"
grep -rn "fireEvent.*GameEvent<Name>" forge/forge-game/src/main/java/forge/game/
```

The mechanics table has **4 required columns**:

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|

- **Forge event**: the `GameEvent*` class that fires, or **"none"** if missing. This determines whether leyline can observe via EventBus or must infer from zone transfers.

### 3. What it does

Plain English summary of the card's behavior from the game rules perspective. One numbered line per mode/ability.

**IMPORTANT: This section must be game-rules generic.** No session-specific details (turn numbers, mana sources, opponent life totals). Those belong in the Trace section. Write it as if explaining the card to someone who hasn't seen a recording.

### 4. Trace

Find sessions where this card was played:

```bash
for d in recordings/2026-*/; do
    just cards-in-session "$(basename $d)" 2>&1 | grep -i "<name>" && echo "  ^ $(basename $d)"
done
```

For the best session (seat 1 preferred — full visibility), trace the card:

```python
import json
with open("recordings/<session>/md-frames.jsonl") as f:
    for line in f:
        d = json.loads(line)
        for o in d.get("objects", []):
            if o.get("grpId") == <GRPID>:
                print(f"gsId={d['gsId']} file={d['file']} zone={o.get('zoneId')}")
```

Build a zone transition table: gsId, instanceId changes, zones, what happened.

### 5. Annotations

Decode raw proto for **novel or gap-filling** moments only:

```bash
just proto-inspect recordings/<session>/capture/seat-1/md-payloads/<file>.bin
```

**Only document annotations that are:**
- **New** — annotation type not previously documented
- **Different** — known annotation with unexpected field values
- **Gap-filling** — confirms or corrects assumptions about missing mechanics

**Skip well-known annotations** (ObjectIdChanged, ManaPaid, ResolutionStart/Complete, AbilityInstanceCreated/Deleted, TappedUntappedPermanent) unless they behave differently for this card. The goal is to keep this section tight — 3-5 annotation groups, not an exhaustive dump.

Focus on: ZoneTransfer categories, counter types, persistent annotations, transform mechanics, any SelectNReq/GroupReq shapes.

### 6. Key findings

Bullet list of what the trace revealed. Corrections to prior assumptions go here. Keep it to genuinely surprising or new discoveries.

### 7. Gaps for leyline

Numbered list of what's missing or broken. Each gap should be actionable — what code needs to change. Include any tasks like "update counter-type-reference" or "add to catalog" here, not in Supporting evidence.

### 8. Supporting evidence needed

**Cross-references only.** Other cards exercising the same mechanics, variance questions across sessions, puzzles to write.

Do NOT put tasks, doc updates, or action items here — those belong in Gaps.

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

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| ... | ... | `GameEvent...` or **none** | ... |

## What it does

1. ...

## Trace (session <timestamp>, seat <N>)

<context — how many times cast, by whom>

### <Event 1>

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| ... | ... | ... | ... |

### Annotations

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
- `docs/card-specs/think-twice.md` — canonical example (lean, focused)
- `docs/conformance/how-we-conform.md` — conformance workflow
- `docs/playbooks/card-lookup-playbook.md` — Arena DB schema
